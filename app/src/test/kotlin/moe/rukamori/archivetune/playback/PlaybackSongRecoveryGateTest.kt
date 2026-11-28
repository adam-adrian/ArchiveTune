/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSongRecoveryGateTest {
    @Test
    fun deduplicatesConcurrentInFlightRequestsAndAllowsDistinctMediaIds() {
        val gate = PlaybackSongRecoveryGate()

        assertTrue(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        assertFalse(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        assertFalse(gate.tryAcquire(SONG_A, hasPlaybackData = true))
        assertTrue(gate.tryAcquire(SONG_B, hasPlaybackData = false))
    }

    @Test
    fun deduplicatesCompletedRequestsWhenDurationWasResolved() {
        val gate = PlaybackSongRecoveryGate()

        assertTrue(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        gate.finish(SONG_A, succeeded = true, hasResolvedDuration = true)

        assertFalse(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        assertFalse(gate.tryAcquire(SONG_A, hasPlaybackData = true))
    }

    @Test
    fun allowsOneRetryWithPlaybackDataWhenDurationWasUnresolved() {
        val gate = PlaybackSongRecoveryGate()

        assertTrue(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        gate.finish(SONG_A, succeeded = true, hasResolvedDuration = false)

        assertFalse(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        assertTrue(gate.tryAcquire(SONG_A, hasPlaybackData = true))

        gate.finish(SONG_A, succeeded = true, hasResolvedDuration = true)
        assertFalse(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        assertFalse(gate.tryAcquire(SONG_A, hasPlaybackData = true))
    }

    @Test
    fun preservesResolvedDurationTrueIfSubsequentRetryFailsOrReportsUnresolved() {
        val gate = PlaybackSongRecoveryGate()

        assertTrue(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        gate.finish(SONG_A, succeeded = true, hasResolvedDuration = false)

        // Retry with playbackData still keeps false if it still cannot resolve duration
        assertTrue(gate.tryAcquire(SONG_A, hasPlaybackData = true))
        gate.finish(SONG_A, succeeded = true, hasResolvedDuration = false)
        assertFalse(gate.tryAcquire(SONG_A, hasPlaybackData = false))
    }

    @Test
    fun allowsRetryAfterFailureRegardlessOfDurationFlag() {
        val gate = PlaybackSongRecoveryGate()

        assertTrue(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        gate.finish(SONG_A, succeeded = false, hasResolvedDuration = false)
        assertTrue(gate.tryAcquire(SONG_A, hasPlaybackData = false))

        gate.finish(SONG_A, succeeded = false, hasResolvedDuration = true)
        assertTrue(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        assertFalse(gate.tryAcquire(SONG_A, hasPlaybackData = false))
    }

    @Test
    fun evictsLeastRecentlyAccessedCompletedEntryBeyondCapacity() {
        val gate = PlaybackSongRecoveryGate(maxCompletedEntries = 2)

        assertTrue(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        gate.finish(SONG_A, succeeded = true, hasResolvedDuration = true)
        assertTrue(gate.tryAcquire(SONG_B, hasPlaybackData = false))
        gate.finish(SONG_B, succeeded = true, hasResolvedDuration = true)

        // Access SONG_A via tryAcquire so SONG_B becomes least recently accessed
        assertFalse(gate.tryAcquire(SONG_A, hasPlaybackData = false))

        assertTrue(gate.tryAcquire(SONG_C, hasPlaybackData = false))
        gate.finish(SONG_C, succeeded = true, hasResolvedDuration = true)

        assertFalse(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        assertFalse(gate.tryAcquire(SONG_C, hasPlaybackData = false))
        assertTrue(gate.tryAcquire(SONG_B, hasPlaybackData = false))
    }

    @Test
    fun clearResetsBothInFlightAndCompletedState() {
        val gate = PlaybackSongRecoveryGate()

        assertTrue(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        assertTrue(gate.tryAcquire(SONG_B, hasPlaybackData = false))
        gate.finish(SONG_B, succeeded = true, hasResolvedDuration = true)

        gate.clear()

        assertTrue(gate.tryAcquire(SONG_A, hasPlaybackData = false))
        assertTrue(gate.tryAcquire(SONG_B, hasPlaybackData = false))
    }

    private companion object {
        const val SONG_A = "song-a"
        const val SONG_B = "song-b"
        const val SONG_C = "song-c"
    }
}
