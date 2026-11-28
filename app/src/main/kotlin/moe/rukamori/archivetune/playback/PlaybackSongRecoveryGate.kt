/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

internal class PlaybackSongRecoveryGate(
    private val maxCompletedEntries: Int = DEFAULT_MAX_COMPLETED_ENTRIES,
) {
    init {
        require(maxCompletedEntries > 0) { "maxCompletedEntries must be positive" }
    }

    private val lock = Any()
    private val inFlight = mutableSetOf<String>()
    private val completed = LinkedHashMap<String, Boolean>(16, 0.75f, true)

    fun tryAcquire(
        mediaId: String,
        hasPlaybackData: Boolean,
    ): Boolean =
        synchronized(lock) {
            if (mediaId in inFlight) return false
            val resolvedDuration = completed[mediaId]
            if (resolvedDuration == true) return false
            if (resolvedDuration == false && !hasPlaybackData) return false
            inFlight += mediaId
            true
        }

    fun finish(
        mediaId: String,
        succeeded: Boolean,
        hasResolvedDuration: Boolean,
    ) {
        synchronized(lock) {
            inFlight.remove(mediaId)
            if (!succeeded) return
            val mergedHasResolvedDuration = (completed[mediaId] == true) || hasResolvedDuration
            completed[mediaId] = mergedHasResolvedDuration
            while (completed.size > maxCompletedEntries) {
                val eldestKey = completed.entries.iterator().next().key
                completed.remove(eldestKey)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            inFlight.clear()
            completed.clear()
        }
    }

    private companion object {
        const val DEFAULT_MAX_COMPLETED_ENTRIES = 512
    }
}
