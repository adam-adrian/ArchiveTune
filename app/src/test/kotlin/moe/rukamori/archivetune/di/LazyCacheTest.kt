/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.di

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheSpan
import moe.rukamori.archivetune.cache.FakeCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
class LazyCacheTest {
    @Test
    fun concurrentBlockingStartReadWriteAndReleaseHoleSpanDoesNotDeadlock() {
        val enteredBlockingCall = CountDownLatch(1)
        val holeReleased = CountDownLatch(1)
        val returnedSpan = CacheSpan("song-1", 0L, 1024L)
        val holeSpan = CacheSpan("song-1", 0L, 1024L)

        val delegate =
            object : FakeCache() {
                override fun startReadWrite(
                    key: String,
                    position: Long,
                    length: Long,
                ): CacheSpan {
                    enteredBlockingCall.countDown()
                    assertTrue(
                        "Timed out waiting for releaseHoleSpan while startReadWrite blocked",
                        holeReleased.await(5, TimeUnit.SECONDS),
                    )
                    return returnedSpan
                }

                override fun releaseHoleSpan(holeSpan: CacheSpan) {
                    holeReleased.countDown()
                }
            }

        val lazyCache = LazyCache { delegate }
        val resultRef = AtomicReference<CacheSpan?>()
        val errorRef = AtomicReference<Throwable?>()

        val readerThread =
            Thread {
                try {
                    resultRef.set(lazyCache.startReadWrite("song-1", 0L, 1024L))
                } catch (throwable: Throwable) {
                    errorRef.set(throwable)
                }
            }
        readerThread.start()

        assertTrue(
            "Timed out waiting for worker thread to enter startReadWrite",
            enteredBlockingCall.await(5, TimeUnit.SECONDS),
        )
        lazyCache.releaseHoleSpan(holeSpan)

        readerThread.join(5_000)
        assertTrue("Worker thread did not finish in time", !readerThread.isAlive)
        assertNull(errorRef.get())
        assertSame(returnedSpan, resultRef.get())
    }

    @Test
    fun initializesLazilyAndOnlyOnceAcrossConcurrentCalls() {
        val createCount = AtomicInteger(0)
        val delegate = FakeCache().apply { putSpan("song-1", 0L, 512L) }
        val lazyCache =
            LazyCache {
                createCount.incrementAndGet()
                delegate
            }

        lazyCache.release()
        assertEquals(0, createCount.get())

        val threadCount = 8
        val startGate = CountDownLatch(1)
        val finishedGate = CountDownLatch(threadCount)
        val errors = AtomicReference<Throwable?>()

        val threads =
            List(threadCount) { index ->
                Thread {
                    try {
                        startGate.await(5, TimeUnit.SECONDS)
                        if (index % 2 == 0) {
                            assertEquals(512L, lazyCache.cacheSpace)
                        } else {
                            assertEquals(setOf("song-1"), lazyCache.keys)
                        }
                    } catch (throwable: Throwable) {
                        errors.compareAndSet(null, throwable)
                    } finally {
                        finishedGate.countDown()
                    }
                }
            }

        threads.forEach(Thread::start)
        startGate.countDown()

        assertTrue(finishedGate.await(5, TimeUnit.SECONDS))
        threads.forEach { it.join(5_000) }
        assertNull(errors.get())
        assertEquals(1, createCount.get())
    }

    @Test
    fun releaseClearsDelegateAndInvokesReleaseOutsideLock() {
        val enteredRelease = CountDownLatch(1)
        val allowReleaseReturn = CountDownLatch(1)
        val releaseCalls = AtomicInteger(0)

        val delegate =
            object : FakeCache() {
                override fun release() {
                    releaseCalls.incrementAndGet()
                    enteredRelease.countDown()
                    assertTrue(allowReleaseReturn.await(5, TimeUnit.SECONDS))
                }
            }

        val lazyCache = LazyCache { delegate }
        lazyCache.keys

        val firstReleaseError = AtomicReference<Throwable?>()
        val firstReleaseThread =
            Thread {
                try {
                    lazyCache.release()
                } catch (throwable: Throwable) {
                    firstReleaseError.set(throwable)
                }
            }
        firstReleaseThread.start()

        assertTrue(enteredRelease.await(5, TimeUnit.SECONDS))

        // Second release must return immediately without blocking on the first delegate.release()
        lazyCache.release()

        allowReleaseReturn.countDown()
        firstReleaseThread.join(5_000)
        assertTrue(!firstReleaseThread.isAlive)
        assertNull(firstReleaseError.get())
        assertEquals(1, releaseCalls.get())
    }
}
