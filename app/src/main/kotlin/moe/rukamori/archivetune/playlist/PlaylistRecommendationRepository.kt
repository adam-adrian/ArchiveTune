/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playlist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.PlaylistSong
import moe.rukamori.archivetune.extensions.filterBlockedArtists
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.filterExplicit
import moe.rukamori.archivetune.innertube.models.filterVideo
import moe.rukamori.archivetune.utils.isLocalMediaId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class PlaylistRecommendationRequest(
    val playlistName: String,
    val browseId: String?,
    val radioEndpointParams: String?,
    val songs: List<PlaylistSong>,
    val usedSeedIds: Set<String> = emptySet(),
    val previouslyShownIds: Set<String> = emptySet(),
    val radioContinuation: String? = null,
    val hideExplicit: Boolean = false,
    val hideVideos: Boolean = false,
)

data class PlaylistRecommendationResult(
    val items: List<SongItem>,
    val usedSeedIds: Set<String>,
    val nextRadioContinuation: String?,
)

internal data class RecommendationSeed(
    val id: String,
    val artistKey: String,
)

@Singleton
class PlaylistRecommendationRepository
    @Inject
    constructor(
        private val database: MusicDatabase,
    ) {
        suspend fun loadRecommendations(
            request: PlaylistRecommendationRequest,
        ): PlaylistRecommendationResult =
            withContext(Dispatchers.IO) {
                val blockedArtistIds = database.getBlockedArtistIds().toSet()
                val playlistSongIds = request.songs.mapTo(HashSet(request.songs.size)) { it.song.id }

                fun filterSongs(items: List<SongItem>): List<SongItem> =
                    items
                        .filter { item ->
                            item.id.length == YOUTUBE_VIDEO_ID_LENGTH &&
                                item.id !in playlistSongIds &&
                                !item.isPodcast &&
                                item.endpoint?.isPodcastEpisodeEndpoint != true &&
                                item.title.isNotBlank() &&
                                item.artists.isNotEmpty()
                        }.filterBlockedArtists(blockedArtistIds)
                        .filterExplicit(request.hideExplicit)
                        .filterVideo(request.hideVideos)

                var nextRadioContinuation = request.radioContinuation
                var updatedUsedSeedIds = request.usedSeedIds
                var candidates: List<SongItem> = emptyList()
                var anyNetworkSuccess = false
                var lastFailure: Throwable? = null

                // 1. Synced playlists: use YouTube's playlist radio (RDAMPL)
                val cleanBrowseId = cleanPlaylistBrowseId(request.browseId)
                if (cleanBrowseId != null) {
                    val radioResult =
                        fetchPlaylistRadio(
                            cleanBrowseId = cleanBrowseId,
                            radioEndpointParams = request.radioEndpointParams,
                            continuation = request.radioContinuation,
                        )
                    radioResult.exceptionOrNull()?.let { throwable ->
                        if (throwable is CancellationException) throw throwable
                        lastFailure = throwable
                    }
                    radioResult.getOrNull()?.let { radio ->
                        anyNetworkSuccess = true
                        nextRadioContinuation = radio.nextContinuation
                        val filteredRadio = filterSongs(radio.items)
                        if (filteredRadio.isNotEmpty()) {
                            candidates =
                                mergeSuggestionLists(
                                    lists = listOf(filteredRadio),
                                    previouslyShownIds = request.previouslyShownIds,
                                    maxPoolSize = MAX_POOL_SIZE,
                                )
                        }
                    }
                }

                // 2. Local playlists (or empty RDAMPL): pull radio from a few songs in the playlist
                if (candidates.isEmpty()) {
                    val eligibleSeeds =
                        request.songs
                            .asSequence()
                            .filter { playlistSong ->
                                isEligibleYouTubeSeed(
                                    playlistSong = playlistSong,
                                    blockedArtistIds = blockedArtistIds,
                                    hideExplicit = request.hideExplicit,
                                    hideVideos = request.hideVideos,
                                )
                            }.map { playlistSong ->
                                val primaryArtist = playlistSong.song.artists.firstOrNull()
                                val artistKey =
                                    primaryArtist?.id?.takeIf(String::isNotBlank)
                                        ?: primaryArtist?.name?.trim()?.lowercase(Locale.ROOT).orEmpty()
                                RecommendationSeed(id = playlistSong.song.id, artistKey = artistKey)
                            }.distinctBy { it.id }
                            .toList()

                    val pickedSeeds =
                        selectRecommendationSeeds(
                            candidates = eligibleSeeds,
                            maxSeeds = SEED_COUNT,
                            usedSeedIds = request.usedSeedIds,
                        )

                    if (pickedSeeds.isNotEmpty()) {
                        val seedResults =
                            coroutineScope {
                                pickedSeeds
                                    .map { seed ->
                                        async {
                                            seed.id to
                                                YouTube.next(
                                                    endpoint =
                                                        WatchEndpoint(
                                                            videoId = seed.id,
                                                            playlistId = "RDAMVM${seed.id}",
                                                        ),
                                                    followAutomixPreview = false,
                                                )
                                        }
                                    }.awaitAll()
                            }

                        val succeededSeedIds = HashSet<String>()
                        val perSeedLists = ArrayList<List<SongItem>>(seedResults.size)
                        for ((seedId, result) in seedResults) {
                            result.exceptionOrNull()?.let { throwable ->
                                if (throwable is CancellationException) throw throwable
                                lastFailure = throwable
                            }
                            result.getOrNull()?.let { nextResult ->
                                anyNetworkSuccess = true
                                succeededSeedIds.add(seedId)
                                val filtered = filterSongs(nextResult.items)
                                if (filtered.isNotEmpty()) {
                                    perSeedLists.add(filtered)
                                }
                            }
                        }

                        if (succeededSeedIds.isNotEmpty()) {
                            val allEligibleIds = eligibleSeeds.mapTo(HashSet(eligibleSeeds.size)) { it.id }
                            val combinedUsed = request.usedSeedIds + succeededSeedIds
                            updatedUsedSeedIds =
                                if (combinedUsed.containsAll(allEligibleIds)) {
                                    succeededSeedIds
                                } else {
                                    combinedUsed
                                }
                        }

                        if (perSeedLists.isNotEmpty()) {
                            candidates =
                                mergeSuggestionLists(
                                    lists = perSeedLists,
                                    previouslyShownIds = request.previouslyShownIds,
                                    maxPoolSize = MAX_POOL_SIZE,
                                )
                        }
                    }
                }

                // 3. Fallback: search by playlist title when nothing came from playlist content
                if (candidates.isEmpty() && request.playlistName.isNotBlank()) {
                    val searchResult =
                        YouTube.search(
                            query = request.playlistName,
                            filter = YouTube.SearchFilter.FILTER_SONG,
                            useAccountContext = false,
                        )
                    searchResult.exceptionOrNull()?.let { throwable ->
                        if (throwable is CancellationException) throw throwable
                        lastFailure = throwable
                    }
                    searchResult.getOrNull()?.let { page ->
                        anyNetworkSuccess = true
                        val filteredSearch = filterSongs(page.items.filterIsInstance<SongItem>())
                        if (filteredSearch.isNotEmpty()) {
                            candidates =
                                mergeSuggestionLists(
                                    lists = listOf(filteredSearch),
                                    previouslyShownIds = request.previouslyShownIds,
                                    maxPoolSize = MAX_POOL_SIZE,
                                )
                        }
                    }
                }

                if (candidates.isEmpty() && !anyNetworkSuccess) {
                    lastFailure?.let { throw it }
                }

                PlaylistRecommendationResult(
                    items = candidates,
                    usedSeedIds = updatedUsedSeedIds,
                    nextRadioContinuation = nextRadioContinuation,
                )
            }

        private data class PlaylistRadioFetch(
            val items: List<SongItem>,
            val nextContinuation: String?,
        )

        private suspend fun fetchPlaylistRadio(
            cleanBrowseId: String,
            radioEndpointParams: String?,
            continuation: String?,
        ): Result<PlaylistRadioFetch> {
            val endpoint =
                WatchEndpoint(
                    playlistId = "RDAMPL$cleanBrowseId",
                    params = radioEndpointParams,
                )
            val firstResult =
                YouTube.next(
                    endpoint = endpoint,
                    continuation = continuation,
                    followAutomixPreview = false,
                )
            firstResult.exceptionOrNull()?.let { throwable ->
                if (throwable is CancellationException) throw throwable
                return Result.failure(throwable)
            }

            val firstPage = firstResult.getOrNull()
            if (firstPage != null && firstPage.items.isNotEmpty()) {
                return Result.success(
                    PlaylistRadioFetch(
                        items = firstPage.items,
                        nextContinuation = firstPage.continuation,
                    ),
                )
            }

            // Continuation succeeded but returned empty: restart radio from the beginning
            if (continuation != null) {
                val freshResult =
                    YouTube.next(
                        endpoint = endpoint,
                        continuation = null,
                        followAutomixPreview = false,
                    )
                freshResult.exceptionOrNull()?.let { throwable ->
                    if (throwable is CancellationException) throw throwable
                    return Result.failure(throwable)
                }
                val freshPage = freshResult.getOrNull()
                return Result.success(
                    PlaylistRadioFetch(
                        items = freshPage?.items.orEmpty(),
                        nextContinuation = freshPage?.continuation,
                    ),
                )
            }

            return Result.success(PlaylistRadioFetch(items = emptyList(), nextContinuation = null))
        }

        companion object {
            const val YOUTUBE_VIDEO_ID_LENGTH = 11
            const val DEFAULT_PAGE_SIZE = 10
            const val MAX_POOL_SIZE = 50
            private const val SEED_COUNT = 3

            fun cleanPlaylistBrowseId(browseId: String?): String? =
                browseId?.trim()?.removePrefix("VL")?.takeIf { it.isNotBlank() }

            fun isEligibleYouTubeSeed(
                playlistSong: PlaylistSong,
                blockedArtistIds: Set<String> = emptySet(),
                hideExplicit: Boolean = false,
                hideVideos: Boolean = false,
            ): Boolean {
                val song = playlistSong.song
                val hasBlockedArtist =
                    song.artists.any { artist ->
                        artist.blockedAt != null || artist.id in blockedArtistIds
                    }
                return !song.song.isLocal &&
                    !song.id.isLocalMediaId() &&
                    !song.song.isPodcast &&
                    song.id.length == YOUTUBE_VIDEO_ID_LENGTH &&
                    !hasBlockedArtist &&
                    (!hideVideos || !song.song.isMusicVideo) &&
                    (!hideExplicit || !song.song.explicit)
            }
        }
    }

internal fun selectRecommendationSeeds(
    candidates: List<RecommendationSeed>,
    maxSeeds: Int,
    usedSeedIds: Set<String> = emptySet(),
    random: Random = Random.Default,
): List<RecommendationSeed> {
    if (maxSeeds <= 0 || candidates.isEmpty()) return emptyList()
    val unique = candidates.distinctBy { it.id }
    val (fresh, stale) = unique.partition { it.id !in usedSeedIds }

    val selected = ArrayList<RecommendationSeed>(minOf(maxSeeds, unique.size))
    val selectedIds = HashSet<String>()
    val selectedArtists = HashSet<String>()

    fun pickFrom(pool: List<RecommendationSeed>) {
        if (pool.isEmpty() || selected.size >= maxSeeds) return
        val shuffled = pool.shuffled(random)
        for (seed in shuffled) {
            if (selected.size >= maxSeeds) break
            if (seed.id in selectedIds) continue
            if (seed.artistKey.isNotEmpty() && seed.artistKey in selectedArtists) continue
            selected.add(seed)
            selectedIds.add(seed.id)
            if (seed.artistKey.isNotEmpty()) selectedArtists.add(seed.artistKey)
        }
        if (selected.size < maxSeeds) {
            for (seed in shuffled) {
                if (selected.size >= maxSeeds) break
                if (selectedIds.add(seed.id)) {
                    selected.add(seed)
                }
            }
        }
    }

    pickFrom(fresh)
    pickFrom(stale)
    return selected
}

internal fun mergeSuggestionLists(
    lists: List<List<SongItem>>,
    previouslyShownIds: Set<String> = emptySet(),
    maxPoolSize: Int = PlaylistRecommendationRepository.MAX_POOL_SIZE,
): List<SongItem> {
    if (lists.isEmpty() || maxPoolSize <= 0) return emptyList()

    val interleaved = ArrayList<SongItem>()
    val seenIds = HashSet<String>()
    val maxLength = lists.maxOfOrNull { it.size } ?: 0

    for (index in 0 until maxLength) {
        for (list in lists) {
            val item = list.getOrNull(index) ?: continue
            if (seenIds.add(item.id)) {
                interleaved.add(item)
            }
        }
    }

    val (unseen, shown) = interleaved.partition { it.id !in previouslyShownIds }
    return (unseen + shown).take(maxPoolSize)
}
