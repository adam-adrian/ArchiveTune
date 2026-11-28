/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.storage

import androidx.compose.runtime.Immutable
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata

@Immutable
data class SongCacheBreakdown(
    val completeBytes: Long = 0L,
    val partialBytes: Long = 0L,
    val clearablePartialBytes: Long = 0L,
    val clearablePartialKeys: List<String> = emptyList(),
) {
    val totalBytes: Long get() = completeBytes + partialBytes
}

@OptIn(UnstableApi::class)
internal fun inspectSongCache(
    playerCache: Cache,
    protectedKey: String? = null,
): SongCacheBreakdown {
    val keys = runCatching { playerCache.keys.toList() }.getOrNull().orEmpty()
    var completeBytes = 0L
    var partialBytes = 0L
    var clearablePartialBytes = 0L
    val clearablePartialKeys = mutableListOf<String>()

    for (key in keys) {
        val spans = runCatching { playerCache.getCachedSpans(key) }.getOrNull().orEmpty()
        val cachedBytes = spans.sumOf { span -> span.length.coerceAtLeast(0L) }
        if (cachedBytes <= 0L) continue

        val contentLength =
            runCatching {
                ContentMetadata.getContentLength(playerCache.getContentMetadata(key))
            }.getOrDefault(-1L)
        val isComplete =
            contentLength > 0L &&
                runCatching { playerCache.isCached(key, 0L, contentLength) }.getOrDefault(false)

        if (isComplete) {
            completeBytes += cachedBytes
        } else {
            partialBytes += cachedBytes
            if (key != protectedKey) {
                clearablePartialBytes += cachedBytes
                clearablePartialKeys += key
            }
        }
    }

    return SongCacheBreakdown(
        completeBytes = completeBytes,
        partialBytes = partialBytes,
        clearablePartialBytes = clearablePartialBytes,
        clearablePartialKeys = clearablePartialKeys,
    )
}
