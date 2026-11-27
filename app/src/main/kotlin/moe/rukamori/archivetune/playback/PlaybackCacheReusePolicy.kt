/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import moe.rukamori.archivetune.playback.stream.ResolvedAudioStream

internal const val PLAYBACK_FORMAT_ID_METADATA_KEY = "archivetune_playback_format_id"
internal const val DOWNLOAD_FORMAT_ID_METADATA_KEY = "archivetune_download_format_id"
internal const val DOWNLOAD_CONTENT_LENGTH_METADATA_KEY = "archivetune_download_content_length"

internal sealed interface PlaybackCacheReuseDecision {
    data object ReuseExistingSession : PlaybackCacheReuseDecision

    data class ReuseComplete(
        val formatId: Int?,
        val contentLength: Long,
    ) : PlaybackCacheReuseDecision

    data class ReusePartial(
        val resolvedStream: ResolvedAudioStream,
    ) : PlaybackCacheReuseDecision

    data object Bypass : PlaybackCacheReuseDecision
}

@OptIn(UnstableApi::class)
internal fun applyDownloadCacheReuseMetadata(
    downloadCache: Cache,
    mediaId: String,
    formatId: Int?,
    contentLength: Long,
) {
    val mutations = ContentMetadataMutations()
    if (formatId != null && formatId > 0) {
        mutations.set(DOWNLOAD_FORMAT_ID_METADATA_KEY, formatId.toLong())
    }
    if (contentLength > 0L) {
        mutations.set(DOWNLOAD_CONTENT_LENGTH_METADATA_KEY, contentLength)
    }
    downloadCache.applyContentMetadataMutations(mediaId, mutations)
}

@OptIn(UnstableApi::class)
internal fun decidePlaybackCacheReuse(
    mediaId: String,
    downloadCache: Cache,
    playerCache: Cache,
    playbackCacheReuseIds: MutableSet<String>,
    resolveDownloadStream: (String) -> ResolvedAudioStream,
): PlaybackCacheReuseDecision {
    if (mediaId in playbackCacheReuseIds) {
        return PlaybackCacheReuseDecision.ReuseExistingSession
    }
    if (downloadCache.getCachedSpans(mediaId).isNotEmpty()) {
        return PlaybackCacheReuseDecision.Bypass
    }

    val playerMetadata = playerCache.getContentMetadata(mediaId)
    val playbackContentLength =
        ContentMetadata
            .getContentLength(playerMetadata)
            .takeIf { it > 0L }
            ?: return PlaybackCacheReuseDecision.Bypass
    val playbackFormatId =
        playerMetadata
            .get(PLAYBACK_FORMAT_ID_METADATA_KEY, -1L)
            .toInt()
            .takeIf { it > 0 }

    if (playerCache.isCached(mediaId, 0L, playbackContentLength)) {
        applyDownloadCacheReuseMetadata(
            downloadCache = downloadCache,
            mediaId = mediaId,
            formatId = playbackFormatId,
            contentLength = playbackContentLength,
        )
        playbackCacheReuseIds += mediaId
        return PlaybackCacheReuseDecision.ReuseComplete(
            formatId = playbackFormatId,
            contentLength = playbackContentLength,
        )
    }

    val cachedBytes = playerCache.getCachedBytes(mediaId, 0L, playbackContentLength)
    if (cachedBytes <= 0L || playbackFormatId == null) {
        return PlaybackCacheReuseDecision.Bypass
    }

    val resolved = resolveDownloadStream(mediaId)
    if (resolved.formatId != playbackFormatId || resolved.contentLength != playbackContentLength) {
        return PlaybackCacheReuseDecision.Bypass
    }

    applyDownloadCacheReuseMetadata(
        downloadCache = downloadCache,
        mediaId = mediaId,
        formatId = resolved.formatId,
        contentLength = resolved.contentLength,
    )
    playbackCacheReuseIds += mediaId
    return PlaybackCacheReuseDecision.ReusePartial(resolved)
}
