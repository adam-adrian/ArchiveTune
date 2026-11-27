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

class PlayerCacheWritePolicyTest {
    @Test
    fun shouldWriteToPlayerCache_disablesOnlyWhenZeroOrLowData() {
        assertFalse(shouldWriteToPlayerCache(lowDataModeActive = false, maxSongCacheSizeMb = 0))
        assertFalse(shouldWriteToPlayerCache(lowDataModeActive = true, maxSongCacheSizeMb = 0))
        assertTrue(shouldWriteToPlayerCache(lowDataModeActive = false, maxSongCacheSizeMb = -1))
        assertFalse(shouldWriteToPlayerCache(lowDataModeActive = true, maxSongCacheSizeMb = -1))
        assertTrue(shouldWriteToPlayerCache(lowDataModeActive = false, maxSongCacheSizeMb = 128))
        assertTrue(shouldWriteToPlayerCache(lowDataModeActive = false, maxSongCacheSizeMb = 1024))
        assertFalse(shouldWriteToPlayerCache(lowDataModeActive = true, maxSongCacheSizeMb = 128))
        assertFalse(shouldWriteToPlayerCache(lowDataModeActive = true, maxSongCacheSizeMb = 1024))
    }

    @Test
    fun shouldPersistPlayerCacheMetadata_alwaysFalseWhenBypassed() {
        assertFalse(
            shouldPersistPlayerCacheMetadata(
                cacheBypassed = true,
                lowDataModeActive = false,
                maxSongCacheSizeMb = -1,
                hasExistingCachedSpans = true,
            ),
        )
        assertFalse(
            shouldPersistPlayerCacheMetadata(
                cacheBypassed = true,
                lowDataModeActive = false,
                maxSongCacheSizeMb = 1024,
                hasExistingCachedSpans = false,
            ),
        )
        assertFalse(
            shouldPersistPlayerCacheMetadata(
                cacheBypassed = true,
                lowDataModeActive = true,
                maxSongCacheSizeMb = 0,
                hasExistingCachedSpans = true,
            ),
        )
    }

    @Test
    fun shouldPersistPlayerCacheMetadata_allowsExistingSpansEvenWhenWritesDisabled() {
        assertFalse(
            shouldPersistPlayerCacheMetadata(
                cacheBypassed = false,
                lowDataModeActive = false,
                maxSongCacheSizeMb = 0,
                hasExistingCachedSpans = false,
            ),
        )
        assertTrue(
            shouldPersistPlayerCacheMetadata(
                cacheBypassed = false,
                lowDataModeActive = false,
                maxSongCacheSizeMb = 0,
                hasExistingCachedSpans = true,
            ),
        )
        assertFalse(
            shouldPersistPlayerCacheMetadata(
                cacheBypassed = false,
                lowDataModeActive = true,
                maxSongCacheSizeMb = 1024,
                hasExistingCachedSpans = false,
            ),
        )
        assertTrue(
            shouldPersistPlayerCacheMetadata(
                cacheBypassed = false,
                lowDataModeActive = true,
                maxSongCacheSizeMb = 1024,
                hasExistingCachedSpans = true,
            ),
        )
    }

    @Test
    fun shouldPersistPlayerCacheMetadata_allowsWhenUnlimitedOrPositiveWithoutLowData() {
        assertTrue(
            shouldPersistPlayerCacheMetadata(
                cacheBypassed = false,
                lowDataModeActive = false,
                maxSongCacheSizeMb = -1,
                hasExistingCachedSpans = false,
            ),
        )
        assertTrue(
            shouldPersistPlayerCacheMetadata(
                cacheBypassed = false,
                lowDataModeActive = false,
                maxSongCacheSizeMb = -1,
                hasExistingCachedSpans = true,
            ),
        )
        assertTrue(
            shouldPersistPlayerCacheMetadata(
                cacheBypassed = false,
                lowDataModeActive = false,
                maxSongCacheSizeMb = 512,
                hasExistingCachedSpans = false,
            ),
        )
        assertTrue(
            shouldPersistPlayerCacheMetadata(
                cacheBypassed = false,
                lowDataModeActive = false,
                maxSongCacheSizeMb = 512,
                hasExistingCachedSpans = true,
            ),
        )
    }
}
