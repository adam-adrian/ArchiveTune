/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.ContentMetadataMutations
import moe.rukamori.archivetune.cache.FakeCache
import moe.rukamori.archivetune.playback.stream.ResolvedAudioStream
import moe.rukamori.archivetune.playback.stream.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class PlaybackCacheReusePolicyTest {
    @Test
    fun completePlayerCache_reusesWithoutResolvingAndCopiesMetadata() {
        val downloadCache = FakeCache()
        val playerCache = FakeCache()
        val reuseIds = mutableSetOf<String>()
        var resolveCalls = 0

        playerCache.putSpan(MEDIA_ID, 0L, 1_000L)
        playerCache.applyContentMetadataMutations(
            MEDIA_ID,
            ContentMetadataMutations().apply {
                set(PLAYBACK_FORMAT_ID_METADATA_KEY, 251L)
                ContentMetadataMutations.setContentLength(this, 1_000L)
            },
        )

        val decision =
            decidePlaybackCacheReuse(
                mediaId = MEDIA_ID,
                downloadCache = downloadCache,
                playerCache = playerCache,
                playbackCacheReuseIds = reuseIds,
                resolveDownloadStream = {
                    resolveCalls += 1
                    resolvedStream(formatId = 251, contentLength = 1_000L)
                },
            )

        assertEquals(PlaybackCacheReuseDecision.ReuseComplete(formatId = 251, contentLength = 1_000L), decision)
        assertEquals(0, resolveCalls)
        assertTrue(MEDIA_ID in reuseIds)
        val downloadMetadata = downloadCache.getContentMetadata(MEDIA_ID)
        assertEquals(251L, downloadMetadata.get(DOWNLOAD_FORMAT_ID_METADATA_KEY, -1L))
        assertEquals(1_000L, downloadMetadata.get(DOWNLOAD_CONTENT_LENGTH_METADATA_KEY, -1L))
    }

    @Test
    fun completePlayerCacheWithoutFormatId_stillReusesAndCopiesContentLength() {
        val downloadCache = FakeCache()
        val playerCache = FakeCache()
        val reuseIds = mutableSetOf<String>()
        var resolveCalls = 0

        playerCache.putSpan(MEDIA_ID, 0L, 1_000L)
        playerCache.applyContentMetadataMutations(
            MEDIA_ID,
            ContentMetadataMutations().apply {
                ContentMetadataMutations.setContentLength(this, 1_000L)
            },
        )

        val decision =
            decidePlaybackCacheReuse(
                mediaId = MEDIA_ID,
                downloadCache = downloadCache,
                playerCache = playerCache,
                playbackCacheReuseIds = reuseIds,
                resolveDownloadStream = {
                    resolveCalls += 1
                    resolvedStream(formatId = 251, contentLength = 1_000L)
                },
            )

        assertEquals(PlaybackCacheReuseDecision.ReuseComplete(formatId = null, contentLength = 1_000L), decision)
        assertEquals(0, resolveCalls)
        assertTrue(MEDIA_ID in reuseIds)
        val downloadMetadata = downloadCache.getContentMetadata(MEDIA_ID)
        assertEquals(-1L, downloadMetadata.get(DOWNLOAD_FORMAT_ID_METADATA_KEY, -1L))
        assertEquals(1_000L, downloadMetadata.get(DOWNLOAD_CONTENT_LENGTH_METADATA_KEY, -1L))
    }

    @Test
    fun partialPlayerCache_reusesWhenFormatAndLengthMatch() {
        val downloadCache = FakeCache()
        val playerCache = FakeCache()
        val reuseIds = mutableSetOf<String>()
        val expected = resolvedStream(formatId = 251, contentLength = 1_000L)
        var resolveCalls = 0

        playerCache.putSpan(MEDIA_ID, 0L, 400L)
        playerCache.applyContentMetadataMutations(
            MEDIA_ID,
            ContentMetadataMutations().apply {
                set(PLAYBACK_FORMAT_ID_METADATA_KEY, 251L)
                ContentMetadataMutations.setContentLength(this, 1_000L)
            },
        )

        val decision =
            decidePlaybackCacheReuse(
                mediaId = MEDIA_ID,
                downloadCache = downloadCache,
                playerCache = playerCache,
                playbackCacheReuseIds = reuseIds,
                resolveDownloadStream = {
                    resolveCalls += 1
                    expected
                },
            )

        assertEquals(PlaybackCacheReuseDecision.ReusePartial(expected), decision)
        assertEquals(1, resolveCalls)
        assertTrue(MEDIA_ID in reuseIds)
        val downloadMetadata = downloadCache.getContentMetadata(MEDIA_ID)
        assertEquals(251L, downloadMetadata.get(DOWNLOAD_FORMAT_ID_METADATA_KEY, -1L))
        assertEquals(1_000L, downloadMetadata.get(DOWNLOAD_CONTENT_LENGTH_METADATA_KEY, -1L))
    }

    @Test
    fun partialPlayerCache_bypassesWhenFormatMismatches() {
        val downloadCache = FakeCache()
        val playerCache = FakeCache()
        val reuseIds = mutableSetOf<String>()

        playerCache.putSpan(MEDIA_ID, 0L, 400L)
        playerCache.applyContentMetadataMutations(
            MEDIA_ID,
            ContentMetadataMutations().apply {
                set(PLAYBACK_FORMAT_ID_METADATA_KEY, 250L)
                ContentMetadataMutations.setContentLength(this, 1_000L)
            },
        )

        val decision =
            decidePlaybackCacheReuse(
                mediaId = MEDIA_ID,
                downloadCache = downloadCache,
                playerCache = playerCache,
                playbackCacheReuseIds = reuseIds,
                resolveDownloadStream = { resolvedStream(formatId = 251, contentLength = 1_000L) },
            )

        assertEquals(PlaybackCacheReuseDecision.Bypass, decision)
        assertFalse(MEDIA_ID in reuseIds)
        val downloadMetadata = downloadCache.getContentMetadata(MEDIA_ID)
        assertEquals(-1L, downloadMetadata.get(DOWNLOAD_FORMAT_ID_METADATA_KEY, -1L))
        assertEquals(-1L, downloadMetadata.get(DOWNLOAD_CONTENT_LENGTH_METADATA_KEY, -1L))
    }

    @Test
    fun partialPlayerCache_bypassesWhenLengthMismatches() {
        val downloadCache = FakeCache()
        val playerCache = FakeCache()
        val reuseIds = mutableSetOf<String>()

        playerCache.putSpan(MEDIA_ID, 0L, 400L)
        playerCache.applyContentMetadataMutations(
            MEDIA_ID,
            ContentMetadataMutations().apply {
                set(PLAYBACK_FORMAT_ID_METADATA_KEY, 251L)
                ContentMetadataMutations.setContentLength(this, 1_000L)
            },
        )

        val decision =
            decidePlaybackCacheReuse(
                mediaId = MEDIA_ID,
                downloadCache = downloadCache,
                playerCache = playerCache,
                playbackCacheReuseIds = reuseIds,
                resolveDownloadStream = { resolvedStream(formatId = 251, contentLength = 1_200L) },
            )

        assertEquals(PlaybackCacheReuseDecision.Bypass, decision)
        assertFalse(MEDIA_ID in reuseIds)
        val downloadMetadata = downloadCache.getContentMetadata(MEDIA_ID)
        assertEquals(-1L, downloadMetadata.get(DOWNLOAD_FORMAT_ID_METADATA_KEY, -1L))
        assertEquals(-1L, downloadMetadata.get(DOWNLOAD_CONTENT_LENGTH_METADATA_KEY, -1L))
    }

    @Test
    fun partialPlayerCache_bypassesWithoutResolvingWhenFormatMetadataMissing() {
        val downloadCache = FakeCache()
        val playerCache = FakeCache()
        val reuseIds = mutableSetOf<String>()
        var resolveCalls = 0

        playerCache.putSpan(MEDIA_ID, 0L, 400L)
        playerCache.applyContentMetadataMutations(
            MEDIA_ID,
            ContentMetadataMutations().apply {
                ContentMetadataMutations.setContentLength(this, 1_000L)
            },
        )

        val decision =
            decidePlaybackCacheReuse(
                mediaId = MEDIA_ID,
                downloadCache = downloadCache,
                playerCache = playerCache,
                playbackCacheReuseIds = reuseIds,
                resolveDownloadStream = {
                    resolveCalls += 1
                    resolvedStream(formatId = 251, contentLength = 1_000L)
                },
            )

        assertEquals(PlaybackCacheReuseDecision.Bypass, decision)
        assertEquals(0, resolveCalls)
        assertFalse(MEDIA_ID in reuseIds)
    }

    @Test
    fun partialPlayerCache_bypassesWithoutResolvingWhenContentLengthMissing() {
        val downloadCache = FakeCache()
        val playerCache = FakeCache()
        val reuseIds = mutableSetOf<String>()
        var resolveCalls = 0

        playerCache.putSpan(MEDIA_ID, 0L, 400L)
        playerCache.applyContentMetadataMutations(
            MEDIA_ID,
            ContentMetadataMutations().apply {
                set(PLAYBACK_FORMAT_ID_METADATA_KEY, 251L)
            },
        )

        val decision =
            decidePlaybackCacheReuse(
                mediaId = MEDIA_ID,
                downloadCache = downloadCache,
                playerCache = playerCache,
                playbackCacheReuseIds = reuseIds,
                resolveDownloadStream = {
                    resolveCalls += 1
                    resolvedStream(formatId = 251, contentLength = 1_000L)
                },
            )

        assertEquals(PlaybackCacheReuseDecision.Bypass, decision)
        assertEquals(0, resolveCalls)
        assertFalse(MEDIA_ID in reuseIds)
    }

    @Test
    fun metadataWithZeroCachedBytes_bypassesWithoutResolving() {
        val downloadCache = FakeCache()
        val playerCache = FakeCache()
        val reuseIds = mutableSetOf<String>()
        var resolveCalls = 0

        playerCache.applyContentMetadataMutations(
            MEDIA_ID,
            ContentMetadataMutations().apply {
                set(PLAYBACK_FORMAT_ID_METADATA_KEY, 251L)
                ContentMetadataMutations.setContentLength(this, 1_000L)
            },
        )

        val decision =
            decidePlaybackCacheReuse(
                mediaId = MEDIA_ID,
                downloadCache = downloadCache,
                playerCache = playerCache,
                playbackCacheReuseIds = reuseIds,
                resolveDownloadStream = {
                    resolveCalls += 1
                    resolvedStream(formatId = 251, contentLength = 1_000L)
                },
            )

        assertEquals(PlaybackCacheReuseDecision.Bypass, decision)
        assertEquals(0, resolveCalls)
        assertFalse(MEDIA_ID in reuseIds)
    }

    @Test
    fun existingDownloadCacheSpans_bypassesPlayerCacheReuse() {
        val downloadCache = FakeCache()
        val playerCache = FakeCache()
        val reuseIds = mutableSetOf<String>()
        var resolveCalls = 0

        downloadCache.putSpan(MEDIA_ID, 0L, 256L)
        playerCache.putSpan(MEDIA_ID, 0L, 1_000L)
        playerCache.applyContentMetadataMutations(
            MEDIA_ID,
            ContentMetadataMutations().apply {
                set(PLAYBACK_FORMAT_ID_METADATA_KEY, 251L)
                ContentMetadataMutations.setContentLength(this, 1_000L)
            },
        )

        val decision =
            decidePlaybackCacheReuse(
                mediaId = MEDIA_ID,
                downloadCache = downloadCache,
                playerCache = playerCache,
                playbackCacheReuseIds = reuseIds,
                resolveDownloadStream = {
                    resolveCalls += 1
                    resolvedStream(formatId = 251, contentLength = 1_000L)
                },
            )

        assertEquals(PlaybackCacheReuseDecision.Bypass, decision)
        assertEquals(0, resolveCalls)
        assertFalse(MEDIA_ID in reuseIds)
    }

    @Test
    fun subsequentOpenWithReuseSessionId_reusesEvenAfterDownloadCacheSpansExist() {
        val downloadCache = FakeCache()
        val playerCache = FakeCache()
        val reuseIds = mutableSetOf(MEDIA_ID)
        var resolveCalls = 0

        downloadCache.putSpan(MEDIA_ID, 0L, 400L)

        val decision =
            decidePlaybackCacheReuse(
                mediaId = MEDIA_ID,
                downloadCache = downloadCache,
                playerCache = playerCache,
                playbackCacheReuseIds = reuseIds,
                resolveDownloadStream = {
                    resolveCalls += 1
                    resolvedStream(formatId = 251, contentLength = 1_000L)
                },
            )

        assertEquals(PlaybackCacheReuseDecision.ReuseExistingSession, decision)
        assertEquals(0, resolveCalls)
    }

    private fun resolvedStream(
        formatId: Int,
        contentLength: Long,
    ): ResolvedAudioStream =
        ResolvedAudioStream(
            url = "https://example.com/audio",
            requestHeaders = emptyMap(),
            formatId = formatId,
            mimeType = "audio/webm",
            codecs = "opus",
            bitrate = 128_000,
            sampleRate = 48_000,
            contentLength = contentLength,
            expiresAtMs = Long.MAX_VALUE,
            authFingerprint = "anon",
            source = StreamSource.YOUTUBEI,
        )

    private companion object {
        const val MEDIA_ID = "video-123"
    }
}
