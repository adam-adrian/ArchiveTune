/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.di

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.cache.FakeCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(UnstableApi::class)
class NotifyingCacheEvictorTest {
    @Test
    fun delegatesAllMethodsAndNotifiesOnlyOnStructuralChanges() =
        runBlocking {
            val cache = FakeCache()
            val span = CacheSpan("song-1", 0L, 512L, 100L, File("span-1"))
            val touchedSpan = CacheSpan("song-1", 0L, 512L, 200L, File("span-1"))
            val calls = mutableListOf<String>()

            val delegate =
                object : CacheEvictor {
                    override fun requiresCacheSpanTouches(): Boolean {
                        calls += "requiresCacheSpanTouches"
                        return true
                    }

                    override fun onCacheInitialized() {
                        calls += "onCacheInitialized"
                    }

                    override fun onStartFile(
                        cache: Cache,
                        key: String,
                        position: Long,
                        length: Long,
                    ) {
                        calls += "onStartFile:$key:$position:$length"
                    }

                    override fun onSpanAdded(
                        cache: Cache,
                        span: CacheSpan,
                    ) {
                        calls += "onSpanAdded:${span.key}"
                    }

                    override fun onSpanRemoved(
                        cache: Cache,
                        span: CacheSpan,
                    ) {
                        calls += "onSpanRemoved:${span.key}"
                    }

                    override fun onSpanTouched(
                        cache: Cache,
                        oldSpan: CacheSpan,
                        newSpan: CacheSpan,
                    ) {
                        calls += "onSpanTouched:${oldSpan.key}"
                    }
                }

            val signal = CacheInvalidationSignal()
            val evictor = NotifyingCacheEvictor(delegate, signal::notifyChanged)

            // Non-blocking when nobody is collecting
            signal.notifyChanged()
            signal.notifyChanged()

            assertTrue(evictor.requiresCacheSpanTouches())

            signal.invalidations.test {
                evictor.onStartFile(cache, "song-1", 0L, 512L)
                evictor.onSpanTouched(cache, span, touchedSpan)
                expectNoEvents()

                evictor.onCacheInitialized()
                assertEquals(Unit, awaitItem())

                evictor.onSpanAdded(cache, span)
                assertEquals(Unit, awaitItem())

                evictor.onSpanRemoved(cache, span)
                assertEquals(Unit, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(
                listOf(
                    "requiresCacheSpanTouches",
                    "onStartFile:song-1:0:512",
                    "onSpanTouched:song-1",
                    "onCacheInitialized",
                    "onSpanAdded:song-1",
                    "onSpanRemoved:song-1",
                ),
                calls,
            )
        }
}
