/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.di

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheSpan
import moe.rukamori.archivetune.cache.FakeCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class DynamicLruCacheEvictorTest {
    private class EvictingFakeCache(
        private val evictor: DynamicLruCacheEvictor,
    ) : FakeCache() {
        val removedSpans = mutableListOf<CacheSpan>()

        fun addSpan(
            key: String,
            position: Long,
            length: Long,
            lastTouchTimestamp: Long,
        ): CacheSpan {
            evictor.onStartFile(this, key, position, length)
            val span = putSpan(key, position, length, lastTouchTimestamp)
            evictor.onSpanAdded(this, span)
            return span
        }

        fun touchSpan(
            oldSpan: CacheSpan,
            newLastTouchTimestamp: Long,
        ): CacheSpan {
            super.removeSpan(oldSpan)
            val newSpan = putSpan(oldSpan.key, oldSpan.position, oldSpan.length, newLastTouchTimestamp)
            evictor.onSpanTouched(this, oldSpan, newSpan)
            return newSpan
        }

        override fun removeSpan(span: CacheSpan) {
            removedSpans += span
            super.removeSpan(span)
            evictor.onSpanRemoved(this, span)
        }
    }

    @Test
    fun cacheLimitMegabytesToMaxBytes_handlesNegativeZeroPositiveAndIntMax() {
        assertEquals(UNLIMITED_PLAYER_CACHE_BYTES, cacheLimitMegabytesToMaxBytes(-1))
        assertEquals(UNLIMITED_PLAYER_CACHE_BYTES, cacheLimitMegabytesToMaxBytes(-5))
        assertEquals(0L, cacheLimitMegabytesToMaxBytes(0))
        assertEquals(128L * 1024L * 1024L, cacheLimitMegabytesToMaxBytes(128))
        assertEquals(Int.MAX_VALUE.toLong() * 1024L * 1024L, cacheLimitMegabytesToMaxBytes(Int.MAX_VALUE))
    }

    @Test
    fun requiresCacheSpanTouches_isAlwaysTrueEvenWhenUnlimited() {
        val evictor = DynamicLruCacheEvictor(UNLIMITED_PLAYER_CACHE_BYTES)
        assertTrue(evictor.requiresCacheSpanTouches())
        evictor.updateMaxSizeMegabytes(512)
        assertTrue(evictor.requiresCacheSpanTouches())
    }

    @Test
    fun evictsLeastRecentlyUsedAndBreaksEqualTimestampTiesCleanly() {
        val evictor = DynamicLruCacheEvictor(initialMaxBytes = 250L)
        val cache = EvictingFakeCache(evictor)

        // Include extreme timestamps to verify comparison avoids subtraction overflow
        cache.addSpan(key = "oldest-extreme", position = 0L, length = 100L, lastTouchTimestamp = Long.MIN_VALUE)
        cache.addSpan(key = "tie-b", position = 0L, length = 100L, lastTouchTimestamp = 100L)
        cache.addSpan(key = "tie-a", position = 0L, length = 100L, lastTouchTimestamp = 100L)
        cache.addSpan(key = "newest-extreme", position = 0L, length = 100L, lastTouchTimestamp = Long.MAX_VALUE)

        assertTrue(cache.getCachedSpans("oldest-extreme").isEmpty())
        assertEquals(1, cache.getCachedSpans("newest-extreme").size)
        assertTrue(cache.cacheSpace <= 250L)
        // Both equal-timestamp spans were distinct in TreeSet; only one was needed to stay <= 250L
        val remainingTieCount =
            cache.getCachedSpans("tie-a").size + cache.getCachedSpans("tie-b").size
        assertEquals(1, remainingTieCount)
    }

    @Test
    fun dynamicLimitTransitions_unlimitedToBoundedAndLimitIncrease() {
        val evictor = DynamicLruCacheEvictor(UNLIMITED_PLAYER_CACHE_BYTES)
        val cache = EvictingFakeCache(evictor)

        cache.addSpan(key = "song-1", position = 0L, length = 400L, lastTouchTimestamp = 10L)
        cache.addSpan(key = "song-2", position = 0L, length = 400L, lastTouchTimestamp = 20L)
        assertEquals(800L, cache.cacheSpace)

        // Transition Unlimited -> 500 bytes: next start/add trims oldest spans down to <= 500 bytes
        evictor.updateMaxBytes(500L)
        cache.addSpan(key = "song-3", position = 0L, length = 100L, lastTouchTimestamp = 30L)

        assertTrue(cache.getCachedSpans("song-1").isEmpty())
        assertEquals(1, cache.getCachedSpans("song-2").size)
        assertEquals(1, cache.getCachedSpans("song-3").size)
        assertEquals(500L, cache.cacheSpace)

        // Increase limit to 1000 bytes: existing spans are kept when adding up to new limit
        evictor.updateMaxBytes(1_000L)
        cache.addSpan(key = "song-4", position = 0L, length = 400L, lastTouchTimestamp = 40L)

        assertEquals(1, cache.getCachedSpans("song-2").size)
        assertEquals(1, cache.getCachedSpans("song-3").size)
        assertEquals(1, cache.getCachedSpans("song-4").size)
        assertEquals(900L, cache.cacheSpace)
    }

    @Test
    fun protectsActiveKeyAndWritingKeyAndEvictsAfterProtectionCleared() {
        val evictor = DynamicLruCacheEvictor(initialMaxBytes = 250L)
        val cache = EvictingFakeCache(evictor)

        cache.addSpan(key = "active-song", position = 0L, length = 150L, lastTouchTimestamp = 1L)
        cache.addSpan(key = "evictable-song", position = 0L, length = 100L, lastTouchTimestamp = 2L)
        evictor.setProtectedKey("active-song")

        // Writing a new span should evict evictable-song, NOT active-song
        cache.addSpan(key = "writing-song", position = 0L, length = 120L, lastTouchTimestamp = 3L)

        assertEquals(1, cache.getCachedSpans("active-song").size)
        assertTrue(cache.getCachedSpans("evictable-song").isEmpty())
        assertEquals(1, cache.getCachedSpans("writing-song").size)

        // Only protectedKey + writingKey remain; writing another span for writing-song terminates cleanly
        cache.addSpan(key = "writing-song", position = 120L, length = 120L, lastTouchTimestamp = 4L)
        assertEquals(1, cache.getCachedSpans("active-song").size)
        assertEquals(2, cache.getCachedSpans("writing-song").size)

        // Once protection is cleared, active-song (oldest) becomes eligible for eviction
        evictor.setProtectedKey(null)
        cache.addSpan(key = "next-song", position = 0L, length = 50L, lastTouchTimestamp = 5L)

        assertTrue(cache.getCachedSpans("active-song").isEmpty())
    }

    @Test
    fun onSpanTouched_refreshesRecencySoOlderUntouchedSpanIsEvictedFirst() {
        val evictor = DynamicLruCacheEvictor(initialMaxBytes = 200L)
        val cache = EvictingFakeCache(evictor)

        val first = cache.addSpan(key = "song-1", position = 0L, length = 100L, lastTouchTimestamp = 10L)
        cache.addSpan(key = "song-2", position = 0L, length = 100L, lastTouchTimestamp = 20L)

        cache.touchSpan(first, newLastTouchTimestamp = 30L)
        cache.addSpan(key = "song-3", position = 0L, length = 100L, lastTouchTimestamp = 40L)

        assertEquals(1, cache.getCachedSpans("song-1").size)
        assertTrue(cache.getCachedSpans("song-2").isEmpty())
        assertEquals(1, cache.getCachedSpans("song-3").size)
    }

    @Test
    fun onStartFileWithUnsetLength_doesNotEvict() {
        val evictor = DynamicLruCacheEvictor(initialMaxBytes = 100L)
        val cache = EvictingFakeCache(evictor)

        cache.addSpan(key = "song-1", position = 0L, length = 100L, lastTouchTimestamp = 10L)
        evictor.onStartFile(cache, key = "song-2", position = 0L, length = C.LENGTH_UNSET.toLong())

        assertEquals(1, cache.getCachedSpans("song-1").size)
        assertTrue(cache.removedSpans.isEmpty())
    }

    @Test
    fun defensiveTerminationGuard_breaksWhenCacheRemoveSpanDoesNotRemoveFromSet() {
        val evictor = DynamicLruCacheEvictor(initialMaxBytes = 50L)
        // Plain FakeCache.removeSpan does NOT invoke evictor.onSpanRemoved
        val cache = FakeCache()
        val span = cache.putSpan(key = "song-1", position = 0L, length = 100L, lastTouchTimestamp = 10L)

        evictor.onSpanAdded(cache, span)

        // Should return cleanly without infinite loop
        assertEquals(1, cache.getCachedSpans("song-1").size)
    }

    @Test
    fun trimCacheToBytes_skipsProtectedKeyAndRespectsLimitsAndFallback() {
        val cache = FakeCache()
        cache.putSpan(key = "active", position = 0L, length = 300L, lastTouchTimestamp = 1L)
        cache.putSpan(key = "oldest", position = 0L, length = 200L, lastTouchTimestamp = 5L)
        cache.putSpan(key = "middle", position = 0L, length = 200L, lastTouchTimestamp = 10L)
        cache.putSpan(key = "newest", position = 0L, length = 200L, lastTouchTimestamp = 20L)

        // limitBytes <= 0 is a no-op
        trimCacheToBytes(cache = cache, limitBytes = 0L, protectedKey = "active")
        assertTrue(cache.removedResources.isEmpty())

        // Trim to 500 bytes while protecting oldest-touched "active" (300 bytes)
        trimCacheToBytes(cache = cache, limitBytes = 500L, protectedKey = "active")

        assertFalse(cache.getCachedSpans("active").isEmpty())
        assertTrue(cache.getCachedSpans("oldest").isEmpty())
        assertTrue(cache.getCachedSpans("middle").isEmpty())
        assertFalse(cache.getCachedSpans("newest").isEmpty())
        assertEquals(listOf("oldest", "middle"), cache.removedResources)
    }

    @Test
    fun trimCacheToBytes_usesFallbackTotalBytesWhenCacheSpaceIsZero() {
        val cache =
            object : FakeCache() {
                override fun getCacheSpace(): Long = 0L
            }
        cache.putSpan(key = "song-a", position = 0L, length = 150L, lastTouchTimestamp = 1L)
        cache.putSpan(key = "song-b", position = 0L, length = 150L, lastTouchTimestamp = 2L)

        trimCacheToBytes(
            cache = cache,
            limitBytes = 200L,
            protectedKey = null,
            fallbackTotalBytes = { 300L },
        )

        assertEquals(listOf("song-a"), cache.removedResources)
        assertTrue(cache.getCachedSpans("song-a").isEmpty())
        assertFalse(cache.getCachedSpans("song-b").isEmpty())
    }
}
