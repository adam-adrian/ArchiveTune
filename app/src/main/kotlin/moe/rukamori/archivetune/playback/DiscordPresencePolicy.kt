/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.db.entities.Song

enum class PausedPresenceGate {
    FollowPreference,
    HiddenByNotificationDismiss,
}

enum class HiddenReason {
    Disabled,
    NoToken,
    NoSong,
    PausedByPreference,
    PausedByNotificationDismiss,
    ServiceStopping,
}

sealed interface DiscordPresenceDecision {
    data class Visible(
        val songId: String,
        val isPaused: Boolean,
    ) : DiscordPresenceDecision

    data class Hidden(
        val reason: HiddenReason,
    ) : DiscordPresenceDecision
}

data class DiscordPresenceSnapshot(
    val song: Song,
    val positionMs: Long,
    val isPaused: Boolean,
)

data class DiscordPresenceInputs(
    val enabled: Boolean,
    val hasToken: Boolean,
    val song: Song?,
    val isPlaying: Boolean,
    val showWhenPaused: Boolean,
    val pausedPresenceGate: PausedPresenceGate = PausedPresenceGate.FollowPreference,
    val serviceStopping: Boolean = false,
)

fun deriveDiscordPresenceDecision(input: DiscordPresenceInputs): DiscordPresenceDecision {
    if (input.serviceStopping) {
        return DiscordPresenceDecision.Hidden(HiddenReason.ServiceStopping)
    }
    if (!input.enabled) {
        return DiscordPresenceDecision.Hidden(HiddenReason.Disabled)
    }
    if (!input.hasToken) {
        return DiscordPresenceDecision.Hidden(HiddenReason.NoToken)
    }

    val song = input.song ?: return DiscordPresenceDecision.Hidden(HiddenReason.NoSong)

    if (!input.isPlaying) {
        if (!input.showWhenPaused) {
            return DiscordPresenceDecision.Hidden(HiddenReason.PausedByPreference)
        }
        if (input.pausedPresenceGate == PausedPresenceGate.HiddenByNotificationDismiss) {
            return DiscordPresenceDecision.Hidden(HiddenReason.PausedByNotificationDismiss)
        }
    }

    return DiscordPresenceDecision.Visible(
        songId = song.song.id,
        isPaused = !input.isPlaying,
    )
}
