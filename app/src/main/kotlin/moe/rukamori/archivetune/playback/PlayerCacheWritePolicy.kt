/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

internal fun shouldWriteToPlayerCache(
    lowDataModeActive: Boolean,
    maxSongCacheSizeMb: Int,
): Boolean = !lowDataModeActive && maxSongCacheSizeMb != 0

internal fun shouldPersistPlayerCacheMetadata(
    cacheBypassed: Boolean,
    lowDataModeActive: Boolean,
    maxSongCacheSizeMb: Int,
    hasExistingCachedSpans: Boolean,
): Boolean =
    !cacheBypassed &&
        (shouldWriteToPlayerCache(lowDataModeActive, maxSongCacheSizeMb) || hasExistingCachedSpans)
