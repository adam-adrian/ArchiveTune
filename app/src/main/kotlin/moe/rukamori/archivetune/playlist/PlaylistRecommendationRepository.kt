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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.PlaylistSuggestionSource
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.PlaylistSong
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.extensions.filterBlockedArtists
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs
import moe.rukamori.archivetune.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig
import moe.rukamori.archivetune.innertube.models.filterExplicit
import moe.rukamori.archivetune.innertube.models.filterVideo
import moe.rukamori.archivetune.utils.isLocalMediaId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class PlaylistRecommendationRequest(
    val playlistName: String,
    val browseId: String?,
    val radioEndpointParams: String?,
    val songs: List<PlaylistSong>,
    val source: PlaylistSuggestionSource,
    val usedSeedIds: Set<String> = emptySet(),
    val previouslyShownIds: Set<String> = emptySet(),
    val radioContinuation: String? = null,
    val titlePlaylistOffset: Int = 0,
    val hideExplicit: Boolean = false,
    val hideVideos: Boolean = false,
)

data class PlaylistRecommendationResult(
    val items: List<SongItem>,
    val usedSeedIds: Set<String>,
    val nextRadioContinuation: String?,
    val nextTitlePlaylistOffset: Int,
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
                val hasSongs = request.songs.isNotEmpty()
                val playlistSongIds = request.songs.mapTo(HashSet(request.songs.size)) { it.song.id }
                val playlistSongSignatures =
                    request.songs.mapTo(HashSet(request.songs.size)) { playlistSong ->
                        PlaylistRecommendationPolicy.dedupSignature(
                            title = playlistSong.song.title,
                            primaryArtistName = playlistSong.song.artists.firstOrNull()?.name.orEmpty(),
                            fallbackId = playlistSong.song.id,
                        )
                    }

                // Never seed or boost artists from blocked-artist tracks, hidden videos, or hidden explicit tracks
                val eligibleSeedSongs =
                    request.songs.filter { playlistSong ->
                        val hasBlockedArtist =
                            playlistSong.song.artists.any { artist ->
                                artist.blockedAt != null || artist.id in blockedArtistIds
                            }
                        !hasBlockedArtist &&
                            (!request.hideVideos || !playlistSong.song.song.isMusicVideo) &&
                            (!request.hideExplicit || !playlistSong.song.song.explicit)
                    }
                val seedCandidates = eligibleSeedSongs.map { it.toSeedCandidate() }
                val playlistArtistKeys =
                    eligibleSeedSongs
                        .flatMap { it.song.artists }
                        .mapTo(HashSet()) { artist ->
                            artist.id.takeIf(String::isNotBlank) ?: artist.name.trim().lowercase(Locale.ROOT)
                        }

                val useContentSignals =
                    when (request.source) {
                        PlaylistSuggestionSource.PLAYLIST_CONTENT -> hasSongs || !request.browseId.isNullOrBlank()
                        PlaylistSuggestionSource.PLAYLIST_TITLE -> false
                        PlaylistSuggestionSource.BOTH -> hasSongs || !request.browseId.isNullOrBlank()
                    }
                val useTitleSignals =
                    when (request.source) {
                        PlaylistSuggestionSource.PLAYLIST_TITLE -> request.playlistName.isNotBlank()
                        PlaylistSuggestionSource.PLAYLIST_CONTENT -> false
                        PlaylistSuggestionSource.BOTH -> PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle(request.playlistName)
                    }

                if (!useContentSignals && !useTitleSignals) {
                    return@withContext PlaylistRecommendationResult(
                        items = emptyList(),
                        usedSeedIds = request.usedSeedIds,
                        nextRadioContinuation = null,
                        nextTitlePlaylistOffset = request.titlePlaylistOffset,
                    )
                }

                val batches = mutableListOf<RecommendationBatch>()
                val newlyUsedSeedIds = mutableSetOf<String>()
                var selectedYouTubeSeedIds: List<String> = emptyList()
                var usedLocalFallback = false
                var nextRadioContinuation: String? = request.radioContinuation
                var nextTitleOffset = request.titlePlaylistOffset

                coroutineScope {
                    val semaphore = Semaphore(permits = MAX_CONCURRENT_REQUESTS)

                    val radioDeferred =
                        if (useContentSignals && !request.browseId.isNullOrBlank()) {
                            async {
                                semaphore.withPermit {
                                    fetchPlaylistRadio(
                                        browseId = request.browseId,
                                        radioEndpointParams = request.radioEndpointParams,
                                        continuation = request.radioContinuation,
                                    )
                                }
                            }
                        } else {
                            null
                        }

                    val selectedSeeds =
                        if (useContentSignals) {
                            val seedCount =
                                if (!request.browseId.isNullOrBlank()) {
                                    SEEDS_WITH_SYNCED_RADIO
                                } else {
                                    SEEDS_FOR_LOCAL_PLAYLIST
                                }
                            PlaylistRecommendationPolicy.selectSeeds(
                                candidates = seedCandidates,
                                maxSeeds = seedCount,
                                excludedSeedIds = request.usedSeedIds,
                            )
                        } else {
                            emptyList()
                        }

                    selectedYouTubeSeedIds = selectedSeeds.map { it.id }
                    newlyUsedSeedIds.addAll(selectedYouTubeSeedIds)

                    val seedBatchesDeferred =
                        selectedSeeds.map { seed ->
                            async {
                                semaphore.withPermit {
                                    fetchSeedBatches(seed.id)
                                }
                            }
                        }

                    val localFallbackDeferred =
                        if (useContentSignals && selectedSeeds.isEmpty() && request.browseId.isNullOrBlank() && hasSongs) {
                            usedLocalFallback = true
                            val localQueries =
                                PlaylistRecommendationPolicy.selectLocalSearchQueries(
                                    candidates = seedCandidates,
                                    maxQueries = LOCAL_FALLBACK_QUERY_LIMIT,
                                    excludedSeedIds = request.usedSeedIds,
                                )
                            newlyUsedSeedIds.addAll(localQueries.map { it.first })
                            localQueries.map { (seedId, query) ->
                                async {
                                    semaphore.withPermit {
                                        val songs = searchSongs(query)
                                        if (songs.isNotEmpty()) {
                                            RecommendationBatch(
                                                kind = RecommendationBatchKind.LOCAL_FALLBACK,
                                                sourceKey = "local:$seedId",
                                                items = songs,
                                            )
                                        } else {
                                            null
                                        }
                                    }
                                }
                            }
                        } else {
                            emptyList()
                        }

                    val titleDeferred =
                        if (useTitleSignals) {
                            val maxPlaylistsToFetch =
                                if (useContentSignals) {
                                    TITLE_PLAYLISTS_IN_BOTH
                                } else {
                                    TITLE_PLAYLISTS_ONLY
                                }
                            async {
                                semaphore.withPermit {
                                    fetchTitleBatches(
                                        playlistName = request.playlistName,
                                        ownBrowseId = request.browseId,
                                        offset = request.titlePlaylistOffset,
                                        maxPlaylists = maxPlaylistsToFetch,
                                    )
                                }
                            }
                        } else {
                            null
                        }

                    radioDeferred?.await()?.let { radio ->
                        nextRadioContinuation = radio.nextContinuation
                        if (radio.items.isNotEmpty()) {
                            batches.add(
                                RecommendationBatch(
                                    kind = RecommendationBatchKind.PLAYLIST_RADIO,
                                    sourceKey = "playlist_radio",
                                    items = radio.items,
                                ),
                            )
                        }
                    }

                    seedBatchesDeferred.awaitAll().forEach { batches.addAll(it) }
                    localFallbackDeferred.awaitAll().filterNotNull().forEach { batches.add(it) }

                    titleDeferred?.await()?.let { titleResult ->
                        nextTitleOffset = titleResult.nextOffset
                        batches.addAll(titleResult.batches)
                    }
                }

                // Enrich with cached Room related songs only for valid YouTube seeds when online content is sparse
                if (useContentSignals && !usedLocalFallback) {
                    val onlineContentCount =
                        batches
                            .asSequence()
                            .filterNot { it.kind.isTitleSourced }
                            .sumOf { it.items.size }
                    if (onlineContentCount < SPARSE_CONTENT_THRESHOLD) {
                        val cachedSeedIds =
                            selectedYouTubeSeedIds
                                .ifEmpty {
                                    seedCandidates
                                        .filter { !it.isLocal && !it.isPodcast && it.isYouTubeId }
                                        .take(SEEDS_FOR_LOCAL_PLAYLIST)
                                        .map { it.id }
                                }
                        cachedSeedIds.forEach { seedId ->
                            val cachedSongs =
                                database
                                    .relatedSongs(seedId)
                                    .filter { related ->
                                        !related.song.isLocal &&
                                            !related.song.isPodcast &&
                                            related.id.length == PlaylistRecommendationPolicy.YOUTUBE_VIDEO_ID_LENGTH &&
                                            (!request.hideVideos || !related.song.isMusicVideo) &&
                                            (!request.hideExplicit || !related.song.explicit)
                                    }.map { it.toSongItem() }
                            if (cachedSongs.isNotEmpty()) {
                                batches.add(
                                    RecommendationBatch(
                                        kind = RecommendationBatchKind.CACHED_RELATED,
                                        sourceKey = "seed:$seedId",
                                        items = cachedSongs,
                                    ),
                                )
                            }
                        }
                    }
                }

                val filteredBatches =
                    batches.map { batch ->
                        val filtered =
                            batch.items
                                .filterBlockedArtists(blockedArtistIds)
                                .filterExplicit(request.hideExplicit)
                                .filterVideo(request.hideVideos)
                        batch.copy(items = filtered)
                    }

                val titleShareCap =
                    if (request.source == PlaylistSuggestionSource.BOTH && useContentSignals) {
                        PlaylistRecommendationPolicy.TITLE_SHARE_CAP_IN_BOTH
                    } else {
                        null
                    }

                val ranked =
                    PlaylistRecommendationPolicy.rankCandidates(
                        batches = filteredBatches,
                        playlistSongIds = playlistSongIds,
                        playlistArtistKeys = playlistArtistKeys,
                        playlistSongSignatures = playlistSongSignatures,
                        previouslyShownIds = request.previouslyShownIds,
                        titleShareCapInBoth = titleShareCap,
                    )

                val allEligibleSeedIds =
                    if (usedLocalFallback) {
                        seedCandidates
                            .filter { !it.isPodcast && it.title.isNotBlank() }
                            .mapTo(HashSet()) { it.id }
                    } else {
                        seedCandidates
                            .filter { !it.isLocal && !it.isPodcast && it.isYouTubeId }
                            .mapTo(HashSet()) { it.id }
                    }
                val updatedUsedSeeds = request.usedSeedIds + newlyUsedSeedIds
                val normalizedUsedSeeds =
                    if (allEligibleSeedIds.isNotEmpty() && updatedUsedSeeds.containsAll(allEligibleSeedIds)) {
                        newlyUsedSeedIds
                    } else {
                        updatedUsedSeeds
                    }

                PlaylistRecommendationResult(
                    items = ranked,
                    usedSeedIds = normalizedUsedSeeds,
                    nextRadioContinuation = nextRadioContinuation,
                    nextTitlePlaylistOffset = nextTitleOffset,
                )
            }

        private data class PlaylistRadioFetch(
            val items: List<SongItem>,
            val nextContinuation: String?,
        )

        private data class TitleBatchesFetch(
            val batches: List<RecommendationBatch>,
            val nextOffset: Int,
        )

        private suspend fun fetchPlaylistRadio(
            browseId: String,
            radioEndpointParams: String?,
            continuation: String?,
        ): PlaylistRadioFetch {
            val cleanBrowseId = browseId.removePrefix("VL")
            val endpoint =
                WatchEndpoint(
                    playlistId = "RDAMPL$cleanBrowseId",
                    params = radioEndpointParams,
                )
            val result =
                YouTube
                    .next(endpoint = endpoint, continuation = continuation)
                    .getOrNullPreservingCancellation()

            if (result != null && result.items.isNotEmpty()) {
                return PlaylistRadioFetch(
                    items = result.items,
                    nextContinuation = result.continuation,
                )
            }

            // Retry from fresh radio start if continuation expired or returned empty
            if (continuation != null) {
                val fresh =
                    YouTube
                        .next(endpoint = endpoint, continuation = null)
                        .getOrNullPreservingCancellation()
                if (fresh != null) {
                    return PlaylistRadioFetch(
                        items = fresh.items,
                        nextContinuation = fresh.continuation,
                    )
                }
            }

            return PlaylistRadioFetch(items = emptyList(), nextContinuation = null)
        }

        private suspend fun fetchSeedBatches(seedId: String): List<RecommendationBatch> {
            val nextResult =
                YouTube
                    .next(WatchEndpoint(videoId = seedId))
                    .getOrNullPreservingCancellation()
                    ?: return emptyList()

            val batches = mutableListOf<RecommendationBatch>()
            val sourceKey = "seed:$seedId"

            val relatedSongs =
                nextResult.relatedEndpoint
                    ?.let { endpoint ->
                        YouTube.related(endpoint).getOrNullPreservingCancellation()?.songs
                    }.orEmpty()

            if (relatedSongs.isNotEmpty()) {
                batches.add(
                    RecommendationBatch(
                        kind = RecommendationBatchKind.SONG_RELATED,
                        sourceKey = sourceKey,
                        items = relatedSongs,
                    ),
                )
            }

            if (nextResult.items.isNotEmpty()) {
                batches.add(
                    RecommendationBatch(
                        kind = RecommendationBatchKind.SONG_NEXT,
                        sourceKey = sourceKey,
                        items = nextResult.items,
                    ),
                )
            }

            return batches
        }

        private suspend fun fetchTitleBatches(
            playlistName: String,
            ownBrowseId: String?,
            offset: Int,
            maxPlaylists: Int,
        ): TitleBatchesFetch {
            val cleanOwnId = ownBrowseId?.removePrefix("VL")
            val communityPlaylists =
                YouTube
                    .search(
                        query = playlistName,
                        filter = YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST,
                        useAccountContext = false,
                    ).getOrNullPreservingCancellation()
                    ?.items
                    .orEmpty()
                    .filterIsInstance<PlaylistItem>()
                    .filter { it.id.removePrefix("VL") != cleanOwnId }

            val matchedPlaylists =
                communityPlaylists.ifEmpty {
                    YouTube
                        .search(
                            query = playlistName,
                            filter = YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST,
                            useAccountContext = false,
                        ).getOrNullPreservingCancellation()
                        ?.items
                        .orEmpty()
                        .filterIsInstance<PlaylistItem>()
                        .filter { it.id.removePrefix("VL") != cleanOwnId }
                }

            val batches = mutableListOf<RecommendationBatch>()
            var nextOffset = offset

            if (matchedPlaylists.isNotEmpty()) {
                val windowSize = minOf(matchedPlaylists.size, TITLE_PLAYLIST_CANDIDATE_WINDOW)
                val startIndex = offset % windowSize
                val picked =
                    (0 until minOf(maxPlaylists, windowSize)).map { delta ->
                        matchedPlaylists[(startIndex + delta) % windowSize]
                    }
                nextOffset = (startIndex + picked.size) % windowSize

                picked.forEach { playlistItem ->
                    val songs =
                        YouTube
                            .playlist(playlistItem.id.removePrefix("VL"))
                            .getOrNullPreservingCancellation()
                            ?.songs
                            .orEmpty()
                            .take(MAX_SONGS_PER_TITLE_PLAYLIST)
                    if (songs.isNotEmpty()) {
                        batches.add(
                            RecommendationBatch(
                                kind = RecommendationBatchKind.TITLE_PLAYLIST,
                                sourceKey = "title_playlist:${playlistItem.id}",
                                items = songs,
                            ),
                        )
                    }
                }
            }

            if (batches.isEmpty()) {
                val fallbackSongs = searchSongs(playlistName)
                if (fallbackSongs.isNotEmpty()) {
                    batches.add(
                        RecommendationBatch(
                            kind = RecommendationBatchKind.TITLE_SEARCH,
                            sourceKey = "title_search",
                            items = fallbackSongs,
                        ),
                    )
                }
            }

            return TitleBatchesFetch(batches = batches, nextOffset = nextOffset)
        }

        private suspend fun searchSongs(query: String): List<SongItem> =
            YouTube
                .search(
                    query = query,
                    filter = YouTube.SearchFilter.FILTER_SONG,
                    useAccountContext = false,
                ).getOrNullPreservingCancellation()
                ?.items
                .orEmpty()
                .filterIsInstance<SongItem>()

        private fun PlaylistSong.toSeedCandidate(): PlaylistSeedCandidate {
            val primaryArtist = song.artists.firstOrNull()
            val artistKey =
                primaryArtist?.id?.takeIf(String::isNotBlank)
                    ?: primaryArtist?.name?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val songId = song.id
            return PlaylistSeedCandidate(
                id = songId,
                title = song.title,
                artistKey = artistKey,
                artistName = primaryArtist?.name.orEmpty(),
                totalPlayTime = song.song.totalPlayTime,
                position = map.position,
                isLocal = song.song.isLocal || songId.isLocalMediaId(),
                isPodcast = song.song.isPodcast,
                isYouTubeId = songId.length == PlaylistRecommendationPolicy.YOUTUBE_VIDEO_ID_LENGTH,
            )
        }

        private fun Song.toSongItem(): SongItem =
            SongItem(
                id = id,
                title = title,
                artists = artists.map { artist -> Artist(name = artist.name, id = artist.id) },
                album = album?.let { album -> Album(name = album.title, id = album.id) },
                duration = song.duration.takeIf { duration -> duration >= 0 },
                thumbnail = song.thumbnailUrl.orEmpty(),
                explicit = song.explicit,
                endpoint =
                    WatchEndpoint(
                        videoId = id,
                        watchEndpointMusicSupportedConfigs =
                            WatchEndpointMusicSupportedConfigs(
                                watchEndpointMusicConfig =
                                    WatchEndpointMusicConfig(
                                        musicVideoType =
                                            if (song.isMusicVideo) {
                                                WatchEndpointMusicConfig.MUSIC_VIDEO_TYPE_OMV
                                            } else {
                                                WatchEndpointMusicConfig.MUSIC_VIDEO_TYPE_ATV
                                            },
                                    ),
                            ),
                    ),
                isPodcast = song.isPodcast,
            )

        private fun <T> Result<T>.getOrNullPreservingCancellation(): T? {
            exceptionOrNull()?.let { throwable ->
                if (throwable is CancellationException) throw throwable
            }
            return getOrNull()
        }

        private companion object {
            const val MAX_CONCURRENT_REQUESTS = 3
            const val SEEDS_WITH_SYNCED_RADIO = 3
            const val SEEDS_FOR_LOCAL_PLAYLIST = 4
            const val LOCAL_FALLBACK_QUERY_LIMIT = 3
            const val TITLE_PLAYLISTS_IN_BOTH = 1
            const val TITLE_PLAYLISTS_ONLY = 2
            const val TITLE_PLAYLIST_CANDIDATE_WINDOW = 6
            const val MAX_SONGS_PER_TITLE_PLAYLIST = 25
            const val SPARSE_CONTENT_THRESHOLD = 15
        }
    }
