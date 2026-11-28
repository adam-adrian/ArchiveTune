/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.storage

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.ContentMetadataMutations
import moe.rukamori.archivetune.cache.FakeCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class SongCachePartitionTest {
    @Test
    fun emptyCache_returnsZerosAndEmptyKeyList() {
        val cache = FakeCache()

        val breakdown = inspectSongCache(cache)

        assertEquals(0L, breakdown.completeBytes)
        assertEquals(0L, breakdown.partialBytes)
        assertEquals(0L, breakdown.clearablePartialBytes)
        assertEquals(0L, breakdown.totalBytes)
        assertTrue(breakdown.clearablePartialKeys.isEmpty())
    }

    @Test
    fun completeSingleSpanAndContiguousMultiSpan_countTowardCompleteBytesOnly() {
        val cache = FakeCache()
        cache.putSpan("song-single", 0L, 1_000L)
        cache.setContentLength("song-single", 1_000L)

        cache.putSpan("song-multi", 0L, 400L)
        cache.putSpan("song-multi", 400L, 600L)
        cache.setContentLength("song-multi", 1_000L)

        val breakdown = inspectSongCache(cache)

        assertEquals(2_000L, breakdown.completeBytes)
        assertEquals(0L, breakdown.partialBytes)
        assertEquals(0L, breakdown.clearablePartialBytes)
        assertEquals(2_000L, breakdown.totalBytes)
        assertTrue(breakdown.clearablePartialKeys.isEmpty())
    }

    @Test
    fun incompletePrefixGappedSpansAndMissingContentLength_countAsPartialAndClearable() {
        val cache = FakeCache()
        cache.putSpan("partial-prefix", 0L, 400L)
        cache.setContentLength("partial-prefix", 1_000L)

        cache.putSpan("partial-gapped", 0L, 300L)
        cache.putSpan("partial-gapped", 500L, 500L)
        cache.setContentLength("partial-gapped", 1_000L)

        cache.putSpan("missing-length", 0L, 600L)

        val breakdown = inspectSongCache(cache)

        assertEquals(0L, breakdown.completeBytes)
        assertEquals(1_800L, breakdown.partialBytes)
        assertEquals(1_800L, breakdown.clearablePartialBytes)
        assertEquals(1_800L, breakdown.totalBytes)
        assertEquals(
            setOf("partial-prefix", "partial-gapped", "missing-length"),
            breakdown.clearablePartialKeys.toSet(),
        )
    }

    @Test
    fun protectedKeyOnPartialEntry_includedInPartialBytesButExcludedFromClearable() {
        val cache = FakeCache()
        cache.putSpan("complete-song", 0L, 1_000L)
        cache.setContentLength("complete-song", 1_000L)

        cache.putSpan("active-partial", 0L, 350L)
        cache.setContentLength("active-partial", 1_000L)

        cache.putSpan("stale-partial", 0L, 250L)
        cache.setContentLength("stale-partial", 1_000L)

        val breakdown = inspectSongCache(cache, protectedKey = "active-partial")

        assertEquals(1_000L, breakdown.completeBytes)
        assertEquals(600L, breakdown.partialBytes)
        assertEquals(250L, breakdown.clearablePartialBytes)
        assertEquals(breakdown.completeBytes + breakdown.partialBytes, breakdown.totalBytes)
        assertEquals(listOf("stale-partial"), breakdown.clearablePartialKeys)
    }

    @Test
    fun protectedKeyOnCompleteEntry_keepsPartialBytesZeroAndClearableEmpty() {
        val cache = FakeCache()
        cache.putSpan("active-complete", 0L, 1_200L)
        cache.setContentLength("active-complete", 1_200L)

        val breakdown = inspectSongCache(cache, protectedKey = "active-complete")

        assertEquals(1_200L, breakdown.completeBytes)
        assertEquals(0L, breakdown.partialBytes)
        assertEquals(0L, breakdown.clearablePartialBytes)
        assertEquals(1_200L, breakdown.totalBytes)
        assertTrue(breakdown.clearablePartialKeys.isEmpty())
    }

    private fun FakeCache.setContentLength(
        key: String,
        length: Long,
    ) {
        applyContentMetadataMutations(
            key,
            ContentMetadataMutations().apply {
                ContentMetadataMutations.setContentLength(this, length)
            },
        )
    }
}
