/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import androidx.media3.common.util.UnstableApi
import moe.rukamori.archivetune.cache.FakeCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class PlayerCacheDuplicateEvictorTest {
    @Test
    fun evictsImmediatelyWhenIdle() {
        val playerCache = FakeCache().apply { putSpan(SONG_A, 0L, 1_000L) }
        val completedIds = mutableSetOf(SONG_A)
        lateinit var evictor: PlayerCacheDuplicateEvictor
        evictor =
            PlayerCacheDuplicateEvictor(
                playerCache = playerCache,
                isDownloadCompleted = { it in completedIds },
                scheduleEviction = { evictor.evictIfEligible(it) },
            )

        evictor.onDownloadCompleted(SONG_A)

        assertTrue(playerCache.getCachedSpans(SONG_A).isEmpty())
        assertEquals(listOf(SONG_A), playerCache.removedResources)
    }

    @Test
    fun skipsRemoveResourceWhenPlayerCacheHasNoSpans() {
        val playerCache = FakeCache()
        val completedIds = mutableSetOf(SONG_A)
        lateinit var evictor: PlayerCacheDuplicateEvictor
        evictor =
            PlayerCacheDuplicateEvictor(
                playerCache = playerCache,
                isDownloadCompleted = { it in completedIds },
                scheduleEviction = { evictor.evictIfEligible(it) },
            )

        evictor.onDownloadCompleted(SONG_A)

        assertTrue(playerCache.removedResources.isEmpty())
    }

    @Test
    fun defersEvictionWhileActivelyPlayingAndEvictsOnTrackTransition() {
        val playerCache = FakeCache().apply { putSpan(SONG_A, 0L, 1_000L) }
        val completedIds = mutableSetOf(SONG_A)
        lateinit var evictor: PlayerCacheDuplicateEvictor
        evictor =
            PlayerCacheDuplicateEvictor(
                playerCache = playerCache,
                isDownloadCompleted = { it in completedIds },
                scheduleEviction = { evictor.evictIfEligible(it) },
            )

        evictor.setActivePlaybackMediaId(SONG_A)
        evictor.onDownloadCompleted(SONG_A)

        assertFalse(playerCache.getCachedSpans(SONG_A).isEmpty())
        assertTrue(playerCache.removedResources.isEmpty())

        evictor.setActivePlaybackMediaId(SONG_B)

        assertTrue(playerCache.getCachedSpans(SONG_A).isEmpty())
        assertEquals(listOf(SONG_A), playerCache.removedResources)
    }

    @Test
    fun evictsPendingEntryWhenPlaybackStops() {
        val playerCache = FakeCache().apply { putSpan(SONG_A, 0L, 1_000L) }
        val completedIds = mutableSetOf(SONG_A)
        lateinit var evictor: PlayerCacheDuplicateEvictor
        evictor =
            PlayerCacheDuplicateEvictor(
                playerCache = playerCache,
                isDownloadCompleted = { it in completedIds },
                scheduleEviction = { evictor.evictIfEligible(it) },
            )

        evictor.setActivePlaybackMediaId(SONG_A)
        evictor.onDownloadCompleted(SONG_A)
        assertTrue(playerCache.removedResources.isEmpty())

        evictor.setActivePlaybackMediaId(null)

        assertTrue(playerCache.getCachedSpans(SONG_A).isEmpty())
        assertEquals(listOf(SONG_A), playerCache.removedResources)
    }

    @Test
    fun skipsDeferredEvictionIfDownloadWasRemovedBeforeTransition() {
        val playerCache =
            FakeCache().apply {
                putSpan(SONG_A, 0L, 1_000L)
                putSpan(SONG_B, 0L, 1_000L)
            }
        val completedIds = mutableSetOf(SONG_A, SONG_B)
        val scheduled = mutableListOf<String>()
        lateinit var evictor: PlayerCacheDuplicateEvictor
        evictor =
            PlayerCacheDuplicateEvictor(
                playerCache = playerCache,
                isDownloadCompleted = { it in completedIds },
                scheduleEviction = {
                    scheduled += it
                    evictor.evictIfEligible(it)
                },
            )

        evictor.setActivePlaybackMediaId(SONG_A)
        evictor.onDownloadCompleted(SONG_A)
        completedIds.remove(SONG_A)
        evictor.onDownloadRemoved(SONG_A)
        evictor.setActivePlaybackMediaId(SONG_B)

        // Also cover case where state flips out of COMPLETED without explicit onDownloadRemoved
        evictor.onDownloadCompleted(SONG_B)
        completedIds.remove(SONG_B)
        evictor.setActivePlaybackMediaId(null)

        assertFalse(playerCache.getCachedSpans(SONG_A).isEmpty())
        assertFalse(playerCache.getCachedSpans(SONG_B).isEmpty())
        assertTrue(playerCache.removedResources.isEmpty())
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun defersWhenPlaybackStartsBetweenSchedulingAndAsyncExecution() {
        val playerCache = FakeCache().apply { putSpan(SONG_A, 0L, 1_000L) }
        val completedIds = mutableSetOf(SONG_A)
        val queued = mutableListOf<String>()
        val evictor =
            PlayerCacheDuplicateEvictor(
                playerCache = playerCache,
                isDownloadCompleted = { it in completedIds },
                scheduleEviction = { queued += it },
            )

        evictor.onDownloadCompleted(SONG_A)
        assertEquals(listOf(SONG_A), queued)

        // Playback begins before the queued coroutine executes evictIfEligible
        evictor.setActivePlaybackMediaId(SONG_A)
        assertFalse(evictor.evictIfEligible(SONG_A))
        assertFalse(playerCache.getCachedSpans(SONG_A).isEmpty())

        // Transitioning away re-schedules and executes eviction
        queued.clear()
        evictor.setActivePlaybackMediaId(null)
        assertEquals(listOf(SONG_A), queued)
        assertTrue(evictor.evictIfEligible(SONG_A))
        assertTrue(playerCache.getCachedSpans(SONG_A).isEmpty())
    }

    @Test
    fun catchesRemoveResourceExceptionsSafely() {
        val failure = IllegalStateException("cache I/O error")
        var reportedError: Throwable? = null
        val playerCache =
            object : FakeCache() {
                override fun removeResource(key: String) {
                    throw failure
                }
            }.apply { putSpan(SONG_A, 0L, 1_000L) }

        val evictor =
            PlayerCacheDuplicateEvictor(
                playerCache = playerCache,
                isDownloadCompleted = { true },
                scheduleEviction = {},
                onEvictionFailure = { _, throwable -> reportedError = throwable },
            )

        assertFalse(evictor.evictIfEligible(SONG_A))
        assertEquals(failure, reportedError)
    }

    private companion object {
        const val SONG_A = "song-a"
        const val SONG_B = "song-b"
    }
}
