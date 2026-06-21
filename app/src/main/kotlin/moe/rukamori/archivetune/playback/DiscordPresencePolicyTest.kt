/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordPresencePolicyTest {
    @Test
    fun returnsServiceStoppingBeforeAnyOtherCondition() {
        val result =
            deriveDiscordPresenceDecision(
                DiscordPresenceInputs(
                    enabled = true,
                    hasToken = true,
                    song = testSong(),
                    isPlaying = true,
                    showWhenPaused = true,
                    serviceStopping = true,
                ),
            )

        assertEquals(
            DiscordPresenceDecision.Hidden(HiddenReason.ServiceStopping),
            result,
        )
    }

    @Test
    fun returnsDisabledWhenRpcIsOff() {
        val result =
            deriveDiscordPresenceDecision(
                DiscordPresenceInputs(
                    enabled = false,
                    hasToken = true,
                    song = testSong(),
                    isPlaying = true,
                    showWhenPaused = true,
                ),
            )

        assertEquals(
            DiscordPresenceDecision.Hidden(HiddenReason.Disabled),
            result,
        )
    }

    @Test
    fun returnsNoTokenWhenTokenIsMissing() {
        val result =
            deriveDiscordPresenceDecision(
                DiscordPresenceInputs(
                    enabled = true,
                    hasToken = false,
                    song = testSong(),
                    isPlaying = true,
                    showWhenPaused = true,
                ),
            )

        assertEquals(
            DiscordPresenceDecision.Hidden(HiddenReason.NoToken),
            result,
        )
    }

    @Test
    fun returnsNoSongWhenSongIsMissing() {
        val result =
            deriveDiscordPresenceDecision(
                DiscordPresenceInputs(
                    enabled = true,
                    hasToken = true,
                    song = null,
                    isPlaying = true,
                    showWhenPaused = true,
                ),
            )

        assertEquals(
            DiscordPresenceDecision.Hidden(HiddenReason.NoSong),
            result,
        )
    }

    @Test
    fun returnsPausedByPreferenceWhenPlaybackIsPausedAndPreferenceIsOff() {
        val result =
            deriveDiscordPresenceDecision(
                DiscordPresenceInputs(
                    enabled = true,
                    hasToken = true,
                    song = testSong(),
                    isPlaying = false,
                    showWhenPaused = false,
                ),
            )

        assertEquals(
            DiscordPresenceDecision.Hidden(HiddenReason.PausedByPreference),
            result,
        )
    }

    @Test
    fun returnsPausedByNotificationDismissWhenGateOverridesPreference() {
        val result =
            deriveDiscordPresenceDecision(
                DiscordPresenceInputs(
                    enabled = true,
                    hasToken = true,
                    song = testSong(),
                    isPlaying = false,
                    showWhenPaused = true,
                    pausedPresenceGate = PausedPresenceGate.HiddenByNotificationDismiss,
                ),
            )

        assertEquals(
            DiscordPresenceDecision.Hidden(HiddenReason.PausedByNotificationDismiss),
            result,
        )
    }

    @Test
    fun returnsVisiblePlayingWhenAllRequirementsAreMet() {
        val result =
            deriveDiscordPresenceDecision(
                DiscordPresenceInputs(
                    enabled = true,
                    hasToken = true,
                    song = testSong("song-playing"),
                    isPlaying = true,
                    showWhenPaused = true,
                ),
            )

        assertEquals(
            DiscordPresenceDecision.Visible(
                songId = "song-playing",
                isPaused = false,
            ),
            result,
        )
    }

    @Test
    fun returnsVisiblePausedWhenPreferenceAllowsPausedPresence() {
        val result =
            deriveDiscordPresenceDecision(
                DiscordPresenceInputs(
                    enabled = true,
                    hasToken = true,
                    song = testSong("song-paused"),
                    isPlaying = false,
                    showWhenPaused = true,
                ),
            )

        assertEquals(
            DiscordPresenceDecision.Visible(
                songId = "song-paused",
                isPaused = true,
            ),
            result,
        )
    }

    private fun testSong(id: String = "song-id"): Song =
        Song(
            song = SongEntity(id = id, title = "Test song"),
            artists = emptyList(),
        )
}
