/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playlist

import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs
import moe.rukamori.archivetune.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaylistRecommendationPolicyTest {
    @Test
    fun `isMeaningfulPlaylistTitle rejects generic and numeric titles`() {
        assertFalse(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle(""))
        assertFalse(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("   "))
        assertFalse(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("My Playlist"))
        assertFalse(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("My Playlist #3"))
        assertFalse(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("My Playlist Vol. 2"))
        assertFalse(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("Playlist Part 2"))
        assertFalse(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("Favorit No. 2"))
        assertFalse(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("Playlist 2026"))
        assertFalse(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("Favorites"))
        assertFalse(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("Favorit 2"))
        assertFalse(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("2026"))
        assertFalse(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("ab"))
    }

    @Test
    fun `isMeaningfulPlaylistTitle accepts descriptive, short genre, decade, and non-Latin titles`() {
        assertTrue(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("Late Night Drive"))
        assertTrue(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("90s R&B"))
        assertTrue(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("80s"))
        assertTrue(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("R&B"))
        assertTrue(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("City Pop"))
        assertTrue(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("Lagu Galau Indonesia"))
        assertTrue(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("アニメ"))
        assertTrue(PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle("邦楽"))
    }

    @Test
    fun `selectSeeds filters local and podcast items and prioritizes artist diversity`() {
        val candidates =
            listOf(
                seed(id = "local_file_1", artist = "a1", playTime = 9999L, position = 0, isLocal = true, isYouTubeId = false),
                seed(id = "podcast1234", artist = "a2", playTime = 9999L, position = 1, isPodcast = true),
                seed(id = "vid00000001", artist = "a1", playTime = 5000L, position = 2),
                seed(id = "vid00000002", artist = "a1", playTime = 4500L, position = 3),
                seed(id = "vid00000003", artist = "a2", playTime = 3000L, position = 4),
                seed(id = "vid00000004", artist = "a3", playTime = 100L, position = 5),
                seed(id = "vid00000005", artist = "a4", playTime = 0L, position = 6),
            )

        val picked = PlaylistRecommendationPolicy.selectSeeds(candidates, maxSeeds = 4, random = Random(42))

        assertEquals(4, picked.size)
        assertTrue(picked.none { it.isLocal || it.isPodcast })
        assertEquals(4, picked.map { it.artistKey }.toSet().size)
        assertTrue("vid00000001" in picked.map { it.id })
        assertTrue("vid00000003" in picked.map { it.id })
    }

    @Test
    fun `selectSeeds always includes remaining fresh seeds when fewer than maxSeeds remain`() {
        val candidates =
            (1..6).map { i ->
                seed(id = "vid0000000$i", artist = "artist$i", playTime = (7 - i) * 1000L, position = i)
            }

        val first = PlaylistRecommendationPolicy.selectSeeds(candidates, maxSeeds = 4, random = Random(1))
        val firstIds = first.map { it.id }.toSet()
        val remainingFreshIds = candidates.map { it.id }.toSet() - firstIds
        assertEquals(2, remainingFreshIds.size)

        val second =
            PlaylistRecommendationPolicy.selectSeeds(
                candidates = candidates,
                maxSeeds = 4,
                excludedSeedIds = firstIds,
                random = Random(2),
            )

        assertEquals(4, second.size)
        assertTrue(second.map { it.id }.containsAll(remainingFreshIds))
    }

    @Test
    fun `rankCandidates boosts cross-seed consensus without double-counting same seed`() {
        val consensusSong = song(id = "cons0000001", title = "Shared Track", artist = "Artist C")
        val singleSeedSong = song(id = "sing0000001", title = "Single Seed", artist = "Artist S")

        val batches =
            listOf(
                RecommendationBatch(
                    kind = RecommendationBatchKind.SONG_RELATED,
                    sourceKey = "seed:S1",
                    items = listOf(singleSeedSong, consensusSong),
                ),
                RecommendationBatch(
                    kind = RecommendationBatchKind.SONG_NEXT,
                    sourceKey = "seed:S1",
                    items = listOf(singleSeedSong),
                ),
                RecommendationBatch(
                    kind = RecommendationBatchKind.SONG_RELATED,
                    sourceKey = "seed:S2",
                    items = listOf(consensusSong),
                ),
            )

        val ranked =
            PlaylistRecommendationPolicy.rankCandidates(
                batches = batches,
                playlistSongIds = emptySet(),
                playlistArtistKeys = emptySet(),
            )

        assertEquals(listOf("cons0000001", "sing0000001"), ranked.map { it.id })
    }

    @Test
    fun `rankCandidates merges alt-ID OMV and ATV versions for consensus and prefers official audio`() {
        val videoVersion =
            song(
                id = "vid00000001",
                title = "Midnight City",
                artist = "M83",
                musicVideoType = WatchEndpointMusicConfig.MUSIC_VIDEO_TYPE_OMV,
            )
        val audioVersion =
            song(
                id = "aud00000001",
                title = "Midnight City",
                artist = "M83",
                musicVideoType = WatchEndpointMusicConfig.MUSIC_VIDEO_TYPE_ATV,
            )
        val singleSeedTopRank = song(id = "oth00000001", title = "Other Song", artist = "Other Artist")

        val ranked =
            PlaylistRecommendationPolicy.rankCandidates(
                batches =
                    listOf(
                        RecommendationBatch(
                            kind = RecommendationBatchKind.SONG_RELATED,
                            sourceKey = "seed:S1",
                            items = listOf(singleSeedTopRank, videoVersion),
                        ),
                        RecommendationBatch(
                            kind = RecommendationBatchKind.SONG_RELATED,
                            sourceKey = "seed:S2",
                            items = listOf(audioVersion),
                        ),
                    ),
                playlistSongIds = emptySet(),
                playlistArtistKeys = emptySet(),
            )

        assertEquals(listOf("aud00000001", "oth00000001"), ranked.map { it.id })
    }

    @Test
    fun `rankCandidates excludes alt-ID copies already in playlist and prevents OMV resurrection of shown ATV`() {
        val inPlaylistCopy = song(id = "alt00000001", title = "Already Here", artist = "Artist P")
        val shownAtv =
            song(
                id = "atv00000001",
                title = "Seen Song",
                artist = "Artist S",
                musicVideoType = WatchEndpointMusicConfig.MUSIC_VIDEO_TYPE_ATV,
            )
        val shownOmv =
            song(
                id = "omv00000001",
                title = "Seen Song",
                artist = "Artist S",
                musicVideoType = WatchEndpointMusicConfig.MUSIC_VIDEO_TYPE_OMV,
            )
        val freshSong = song(id = "new00000001", title = "Fresh Track", artist = "Artist F")

        val ranked =
            PlaylistRecommendationPolicy.rankCandidates(
                batches =
                    listOf(
                        RecommendationBatch(
                            kind = RecommendationBatchKind.PLAYLIST_RADIO,
                            sourceKey = "playlist_radio",
                            items = listOf(inPlaylistCopy, shownOmv, shownAtv, freshSong),
                        ),
                    ),
                playlistSongIds = setOf("orig0000001"),
                playlistArtistKeys = emptySet(),
                playlistSongSignatures = setOf(PlaylistRecommendationPolicy.dedupSignature("Already Here", "Artist P", "orig0000001")),
                previouslyShownIds = setOf("atv00000001"),
            )

        assertEquals(listOf("new00000001", "atv00000001"), ranked.map { it.id })
    }

    @Test
    fun `rankCandidates enforces artist cap and title share cap per page`() {
        val spamArtistSongs = (1..5).map { song(id = "spam000000$it", title = "Spam $it", artist = "Same Artist") }
        val otherContentSongs = (1..8).map { song(id = "cont000000$it", title = "Content $it", artist = "Artist $it") }
        val titleSongs = (1..5).map { song(id = "titl000000$it", title = "Title $it", artist = "Title Artist $it") }

        val batches =
            listOf(
                RecommendationBatch(
                    kind = RecommendationBatchKind.PLAYLIST_RADIO,
                    sourceKey = "playlist_radio",
                    items = spamArtistSongs + otherContentSongs,
                ),
                RecommendationBatch(
                    kind = RecommendationBatchKind.TITLE_PLAYLIST,
                    sourceKey = "title_playlist:1",
                    items = titleSongs,
                ),
            )

        val ranked =
            PlaylistRecommendationPolicy.rankCandidates(
                batches = batches,
                playlistSongIds = emptySet(),
                playlistArtistKeys = emptySet(),
                maxPerArtistPerPage = 2,
                pageSize = 10,
                titleShareCapInBoth = 2,
            )

        val firstPage = ranked.take(10)
        assertEquals(10, firstPage.size)
        assertTrue(firstPage.count { it.artists.first().name == "Same Artist" } <= 2)
        assertTrue(firstPage.count { it.id.startsWith("titl") } <= 2)
    }

    private fun seed(
        id: String,
        artist: String,
        playTime: Long = 0L,
        position: Int = 0,
        isLocal: Boolean = false,
        isPodcast: Boolean = false,
        isYouTubeId: Boolean = true,
    ) = PlaylistSeedCandidate(
        id = id,
        title = "Title $id",
        artistKey = artist,
        artistName = artist,
        totalPlayTime = playTime,
        position = position,
        isLocal = isLocal,
        isPodcast = isPodcast,
        isYouTubeId = isYouTubeId,
    )

    private fun song(
        id: String,
        title: String,
        artist: String,
        musicVideoType: String? = null,
    ) = SongItem(
        id = id,
        title = title,
        artists = listOf(Artist(name = artist, id = "UC_$artist")),
        thumbnail = "https://example.com/$id.jpg",
        endpoint =
            musicVideoType?.let { type ->
                WatchEndpoint(
                    videoId = id,
                    watchEndpointMusicSupportedConfigs =
                        WatchEndpointMusicSupportedConfigs(
                            watchEndpointMusicConfig = WatchEndpointMusicConfig(musicVideoType = type),
                        ),
                )
            },
    )
}
