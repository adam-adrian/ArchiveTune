/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import kotlinx.coroutines.CancellationException

@OptIn(UnstableApi::class)
internal class PlayerCacheDuplicateEvictor(
    private val playerCache: Cache,
    private val isDownloadCompleted: (String) -> Boolean,
    private val scheduleEviction: (String) -> Unit,
    private val onEvictionFailure: (String, Throwable) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var activePlaybackMediaId: String? = null
    private val pendingPlayerCacheEvictions = mutableSetOf<String>()

    fun setActivePlaybackMediaId(mediaId: String?) {
        val candidatesToEvict =
            synchronized(lock) {
                if (activePlaybackMediaId == mediaId) return
                activePlaybackMediaId = mediaId
                val ready = pendingPlayerCacheEvictions.filter { it != mediaId }
                ready.forEach(pendingPlayerCacheEvictions::remove)
                ready
            }
        for (candidateId in candidatesToEvict) {
            if (isDownloadCompleted(candidateId)) {
                scheduleEviction(candidateId)
            }
        }
    }

    fun onDownloadCompleted(mediaId: String) {
        val shouldSchedule =
            synchronized(lock) {
                if (activePlaybackMediaId == mediaId) {
                    pendingPlayerCacheEvictions += mediaId
                    false
                } else {
                    pendingPlayerCacheEvictions.remove(mediaId)
                    true
                }
            }
        if (shouldSchedule) {
            scheduleEviction(mediaId)
        }
    }

    fun onDownloadRemoved(mediaId: String) {
        synchronized(lock) {
            pendingPlayerCacheEvictions.remove(mediaId)
        }
    }

    fun evictIfEligible(mediaId: String): Boolean {
        if (!isDownloadCompleted(mediaId)) return false
        synchronized(lock) {
            if (activePlaybackMediaId == mediaId) {
                pendingPlayerCacheEvictions += mediaId
                return false
            }
        }
        return try {
            if (playerCache.getCachedSpans(mediaId).isEmpty()) {
                false
            } else {
                playerCache.removeResource(mediaId)
                true
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            onEvictionFailure(mediaId, exception)
            false
        }
    }
}
