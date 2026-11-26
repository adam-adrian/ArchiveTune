/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.constants.PlaylistSongSortType
import moe.rukamori.archivetune.constants.PlaylistSuggestionSource
import moe.rukamori.archivetune.constants.PlaylistSuggestionSourceKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.PlaylistSong
import moe.rukamori.archivetune.extensions.reversed
import moe.rukamori.archivetune.extensions.toEnum
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.models.PlaylistSuggestion
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.playlist.PlaylistRecommendationPolicy
import moe.rukamori.archivetune.playlist.PlaylistRecommendationRepository
import moe.rukamori.archivetune.playlist.PlaylistRecommendationRequest
import moe.rukamori.archivetune.playlist.RemovePlaylistCoverUseCase
import moe.rukamori.archivetune.playlist.UpdatePlaylistCoverUseCase
import moe.rukamori.archivetune.utils.SyncUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.reportException
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LocalPlaylistViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val syncUtils: SyncUtils,
        private val updatePlaylistCover: UpdatePlaylistCoverUseCase,
        private val removePlaylistCover: RemovePlaylistCoverUseCase,
        private val recommendationRepository: PlaylistRecommendationRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        val playlistId = savedStateHandle.get<String>("playlistId")!!
        val playlist =
            database
                .playlist(playlistId)
                .stateIn(viewModelScope, SharingStarted.Lazily, null)

        val sortType: StateFlow<PlaylistSongSortType> =
            playlist
                .map { it?.playlist?.songSortType.toEnum(PlaylistSongSortType.CUSTOM) }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, PlaylistSongSortType.CUSTOM)

        val sortDescending: StateFlow<Boolean> =
            playlist
                .map { it?.playlist?.songSortDescending ?: true }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, true)

        val playlistSongs: StateFlow<List<PlaylistSong>> =
            combine(
                database.playlistSongs(playlistId),
                sortType,
                sortDescending,
                context.dataStore.data
                    .map { preferences -> preferences[HideVideoKey] ?: false }
                    .distinctUntilChanged(),
            ) { songs, sortType, sortDescending, hideVideo ->
                val visibleSongs =
                    songs
                        .filter { playlistSong -> playlistSong.song.artists.none { it.blockedAt != null } }
                        .filter { playlistSong -> !hideVideo || !playlistSong.song.song.isMusicVideo }
                when (sortType) {
                    PlaylistSongSortType.CUSTOM -> {
                        visibleSongs
                    }

                    PlaylistSongSortType.CREATE_DATE -> {
                        visibleSongs.sortedBy { it.map.id }
                    }

                    PlaylistSongSortType.NAME -> {
                        visibleSongs.sortedBy { it.song.song.title }
                    }

                    PlaylistSongSortType.ARTIST -> {
                        val collator = Collator.getInstance(Locale.getDefault())
                        collator.strength = Collator.PRIMARY
                        visibleSongs.sortedWith(
                            compareBy(collator) { song ->
                                song.song.artists.joinToString("") { artist -> artist.name }
                            },
                        )
                    }

                    PlaylistSongSortType.PLAY_TIME -> {
                        visibleSongs.sortedBy { it.song.song.totalPlayTime }
                    }
                }.reversed(sortDescending && sortType != PlaylistSongSortType.CUSTOM)
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

        fun updateSortPreference(
            newSortType: PlaylistSongSortType,
            newSortDescending: Boolean,
        ) {
            viewModelScope.launch(Dispatchers.IO) {
                database.updatePlaylistSortPreference(
                    playlistId = playlistId,
                    sortType = newSortType.name,
                    descending = newSortDescending,
                )
            }
        }

        private val _viewCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
        val viewCounts = _viewCounts.asStateFlow()

        private val viewCountsMutex = Mutex()
        private val viewCountsInFlight = mutableSetOf<String>()
        private val viewCountsSemaphore = Semaphore(permits = 4)

        // Playlist Suggestions State
        private val _suggestionPool = MutableStateFlow<List<SongItem>?>(null)
        private val _currentSuggestionPage = MutableStateFlow(0)
        private val playlistSongIds =
            database
                .playlistSongs(playlistId)
                .map { songs -> songs.mapTo(HashSet(songs.size)) { it.song.id } }
                .distinctUntilChanged()

        val playlistSuggestions: StateFlow<PlaylistSuggestion?> =
            combine(_suggestionPool, _currentSuggestionPage, playlistSongIds) { pool, pageIndex, songIds ->
                if (pool == null) return@combine null
                val available = pool.filter { it.id !in songIds }
                if (available.isEmpty()) {
                    return@combine PlaylistSuggestion(
                        items = emptyList(),
                        currentPage = 0,
                        totalPages = 0,
                    )
                }
                val pageSize = PlaylistRecommendationPolicy.DEFAULT_PAGE_SIZE
                val totalPages = (available.size + pageSize - 1) / pageSize
                val safePage = pageIndex.coerceIn(0, totalPages - 1)
                val fromIndex = safePage * pageSize
                val toIndex = (fromIndex + pageSize).coerceAtMost(available.size)
                PlaylistSuggestion(
                    items = available.subList(fromIndex, toIndex),
                    currentPage = safePage,
                    totalPages = totalPages,
                )
            }.stateIn(viewModelScope, SharingStarted.Lazily, null)

        private val _isLoadingSuggestions = MutableStateFlow(false)
        val isLoadingSuggestions = _isLoadingSuggestions.asStateFlow()

        private var usedSuggestionSeedIds = emptySet<String>()
        private var previouslyShownSuggestionIds = ArrayDeque<String>()
        private var viewedSuggestionIdsInCurrentPool = LinkedHashSet<String>()
        private var suggestionRadioContinuation: String? = null
        private var suggestionTitlePlaylistOffset = 0
        private var suggestionLoadJob: Job? = null
        private var suggestionLoadGeneration = 0L

        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing = _isRefreshing.asStateFlow()

        private val _coverState = MutableStateFlow<PlaylistCoverState>(PlaylistCoverState.Empty)
        val coverState = _coverState.asStateFlow()

        private val coverEventChannel = Channel<PlaylistCoverEvent>(Channel.BUFFERED)
        val coverEvents = coverEventChannel.receiveAsFlow()

        private var coverMutationJob: Job? = null

        init {
            viewModelScope.launch(Dispatchers.IO) {
                val sortedSongs =
                    database
                        .playlistSongs(playlistId)
                        .first()
                        .sortedWith(compareBy({ it.map.position }, { it.map.id }))
                database.withTransaction {
                    sortedSongs.forEachIndexed { index, playlistSong ->
                        if (playlistSong.map.position != index) {
                            update(playlistSong.map.copy(position = index))
                        }
                    }
                }
            }

            viewModelScope.launch {
                playlistSongs.collect { songs ->
                    prefetchViewCounts(
                        songs
                            .asSequence()
                            .filter { !it.song.song.isLocal }
                            .map { it.song.id }
                            .toList(),
                    )
                }
            }

            // Auto-load suggestions once Room emits real playlist & songs, and react to setting/title/emptiness changes
            val suggestionPreferences =
                context.dataStore.data
                    .map { preferences ->
                        SuggestionPreferences(
                            source = preferences[PlaylistSuggestionSourceKey].toEnum(PlaylistSuggestionSource.BOTH),
                            hideExplicit = preferences[HideExplicitKey] ?: false,
                            hideVideos = preferences[HideVideoKey] ?: false,
                        )
                    }.distinctUntilChanged()

            viewModelScope.launch {
                combine(
                    database.playlist(playlistId),
                    database.playlistSongs(playlistId),
                    suggestionPreferences,
                ) { playlist, songs, prefs ->
                    if (playlist == null) return@combine null
                    val effectiveTitle =
                        if (prefs.source == PlaylistSuggestionSource.PLAYLIST_CONTENT) {
                            ""
                        } else {
                            playlist.playlist.name
                        }
                    SuggestionTriggerInput(
                        trigger =
                            SuggestionTrigger(
                                effectiveTitle = effectiveTitle,
                                browseId = playlist.playlist.browseId,
                                radioEndpointParams = playlist.playlist.radioEndpointParams,
                                hasSongs = songs.isNotEmpty(),
                                preferences = prefs,
                            ),
                        playlistName = playlist.playlist.name,
                        songs = songs,
                    )
                }.distinctUntilChangedBy { it?.trigger }
                    .collect { input ->
                        if (input == null) return@collect
                        loadPlaylistSuggestions(
                            playlistName = input.playlistName,
                            browseId = input.trigger.browseId,
                            radioEndpointParams = input.trigger.radioEndpointParams,
                            songs = input.songs,
                            preferences = input.trigger.preferences,
                            resetHistory = true,
                        )
                    }
            }
        }

        private fun prefetchViewCounts(videoIds: List<String>) {
            val uniqueIds = videoIds.distinct().filter { it.isNotBlank() }
            if (uniqueIds.isEmpty()) return

            viewModelScope.launch(Dispatchers.IO) {
                coroutineScope {
                    uniqueIds
                        .map { videoId ->
                            async {
                                val shouldFetch =
                                    viewCountsMutex.withLock {
                                        if (_viewCounts.value.containsKey(videoId) || viewCountsInFlight.contains(videoId)) {
                                            false
                                        } else {
                                            viewCountsInFlight.add(videoId)
                                            true
                                        }
                                    }

                                if (!shouldFetch) return@async

                                try {
                                    viewCountsSemaphore.withPermit {
                                        val count = YouTube.getMediaInfo(videoId).getOrNull()?.viewCount
                                        if (count != null && count >= 0) {
                                            _viewCounts.update { current -> current + (videoId to count) }
                                        }
                                    }
                                } finally {
                                    viewCountsMutex.withLock { viewCountsInFlight.remove(videoId) }
                                }
                            }
                        }.awaitAll()
                }
            }
        }

        fun refresh() {
            if (_isRefreshing.value) return
            _isRefreshing.value = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val p = playlist.first() ?: return@launch
                    val browseId = p.playlist.browseId ?: return@launch
                    syncUtils.syncPlaylistNow(browseId = browseId, playlistId = playlistId)
                } catch (e: Exception) {
                    reportException(e)
                } finally {
                    _isRefreshing.value = false
                }
            }
        }

        fun updateCover(uri: Uri) {
            mutateCover { updatePlaylistCover(playlistId, uri) }
        }

        fun removeCover() {
            mutateCover { removePlaylistCover(playlistId) }
        }

        private fun mutateCover(mutation: suspend () -> Unit) {
            if (coverMutationJob?.isActive == true) return
            coverMutationJob =
                viewModelScope.launch(Dispatchers.IO) {
                    _coverState.value = PlaylistCoverState.Loading
                    try {
                        mutation()
                        _coverState.value = PlaylistCoverState.Success
                        coverEventChannel.send(PlaylistCoverEvent.ShowMessage(R.string.playlist_cover_updated))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        reportException(throwable)
                        _coverState.value = PlaylistCoverState.Error(R.string.playlist_cover_update_failed)
                        coverEventChannel.send(PlaylistCoverEvent.ShowMessage(R.string.playlist_cover_update_failed))
                    }
                }
        }

        // Playlist Suggestions Functions

        private fun hasActiveSignals(
            playlistName: String,
            browseId: String?,
            songs: List<PlaylistSong>,
            source: PlaylistSuggestionSource,
        ): Boolean =
            when (source) {
                PlaylistSuggestionSource.PLAYLIST_CONTENT -> songs.isNotEmpty() || !browseId.isNullOrBlank()
                PlaylistSuggestionSource.PLAYLIST_TITLE -> playlistName.isNotBlank()
                PlaylistSuggestionSource.BOTH -> {
                    songs.isNotEmpty() ||
                        !browseId.isNullOrBlank() ||
                        PlaylistRecommendationPolicy.isMeaningfulPlaylistTitle(playlistName)
                }
            }

        private fun recordShownSuggestionIds(ids: Iterable<String>) {
            for (id in ids) {
                if (id in previouslyShownSuggestionIds) continue
                previouslyShownSuggestionIds.addLast(id)
                while (previouslyShownSuggestionIds.size > MAX_REMEMBERED_SHOWN_SUGGESTIONS) {
                    previouslyShownSuggestionIds.removeFirst()
                }
            }
        }

        private fun markCurrentPageViewed() {
            playlistSuggestions.value?.items?.forEach { item ->
                viewedSuggestionIdsInCurrentPool.add(item.id)
            }
        }

        private fun loadPlaylistSuggestions(
            playlistName: String,
            browseId: String?,
            radioEndpointParams: String?,
            songs: List<PlaylistSong>,
            preferences: SuggestionPreferences,
            resetHistory: Boolean,
        ) {
            suggestionLoadJob?.cancel()
            val generation = ++suggestionLoadGeneration

            if (!hasActiveSignals(playlistName, browseId, songs, preferences.source)) {
                _suggestionPool.value = null
                _currentSuggestionPage.value = 0
                _isLoadingSuggestions.value = false
                viewedSuggestionIdsInCurrentPool.clear()
                if (resetHistory) {
                    usedSuggestionSeedIds = emptySet()
                    previouslyShownSuggestionIds.clear()
                    suggestionRadioContinuation = null
                    suggestionTitlePlaylistOffset = 0
                }
                return
            }

            if (resetHistory) {
                _suggestionPool.value = null
                _currentSuggestionPage.value = 0
                usedSuggestionSeedIds = emptySet()
                previouslyShownSuggestionIds.clear()
                viewedSuggestionIdsInCurrentPool.clear()
                suggestionRadioContinuation = null
                suggestionTitlePlaylistOffset = 0
            } else {
                markCurrentPageViewed()
                recordShownSuggestionIds(viewedSuggestionIdsInCurrentPool)
            }

            _isLoadingSuggestions.value = true
            val request =
                PlaylistRecommendationRequest(
                    playlistName = playlistName,
                    browseId = browseId,
                    radioEndpointParams = radioEndpointParams,
                    songs = songs,
                    source = preferences.source,
                    usedSeedIds = usedSuggestionSeedIds,
                    previouslyShownIds = previouslyShownSuggestionIds.toSet(),
                    radioContinuation = suggestionRadioContinuation,
                    titlePlaylistOffset = suggestionTitlePlaylistOffset,
                    hideExplicit = preferences.hideExplicit,
                    hideVideos = preferences.hideVideos,
                )

            suggestionLoadJob =
                viewModelScope.launch {
                    try {
                        val result = recommendationRepository.loadRecommendations(request)
                        if (generation != suggestionLoadGeneration) return@launch
                        usedSuggestionSeedIds = result.usedSeedIds
                        suggestionRadioContinuation = result.nextRadioContinuation
                        suggestionTitlePlaylistOffset = result.nextTitlePlaylistOffset
                        viewedSuggestionIdsInCurrentPool.clear()
                        result.items
                            .take(PlaylistRecommendationPolicy.DEFAULT_PAGE_SIZE)
                            .forEach { viewedSuggestionIdsInCurrentPool.add(it.id) }
                        _currentSuggestionPage.value = 0
                        _suggestionPool.value = result.items
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        reportException(e)
                        if (generation == suggestionLoadGeneration && _suggestionPool.value == null) {
                            _suggestionPool.value = emptyList()
                        }
                    } finally {
                        if (generation == suggestionLoadGeneration) {
                            _isLoadingSuggestions.value = false
                        }
                    }
                }
        }

        fun changeSuggestionPage(direction: Int) {
            if (direction != -1 && direction != 1) return
            if (_isLoadingSuggestions.value) return
            val current = playlistSuggestions.value ?: return
            if (current.totalPages <= 1) return
            markCurrentPageViewed()
            val targetPage = (current.currentPage + direction).coerceIn(0, current.totalPages - 1)
            if (targetPage != current.currentPage) {
                _currentSuggestionPage.value = targetPage
                val pool = _suggestionPool.value.orEmpty()
                val pageSize = PlaylistRecommendationPolicy.DEFAULT_PAGE_SIZE
                val currentSongIds = playlistSongs.value.mapTo(HashSet()) { it.song.id }
                val available = pool.filter { it.id !in currentSongIds }
                val fromIndex = targetPage * pageSize
                val toIndex = (fromIndex + pageSize).coerceAtMost(available.size)
                if (fromIndex < toIndex) {
                    available.subList(fromIndex, toIndex).forEach { viewedSuggestionIdsInCurrentPool.add(it.id) }
                }
            }
        }

        fun resetAndLoadPlaylistSuggestions() {
            suggestionLoadJob?.cancel()
            val generation = ++suggestionLoadGeneration
            markCurrentPageViewed()
            recordShownSuggestionIds(viewedSuggestionIdsInCurrentPool)
            _isLoadingSuggestions.value = true

            suggestionLoadJob =
                viewModelScope.launch {
                    try {
                        val currentPlaylist = database.playlist(playlistId).first()
                        val currentSongs = database.playlistSongs(playlistId).first()
                        val dataStorePrefs = context.dataStore.data.first()
                        if (generation != suggestionLoadGeneration) return@launch
                        if (currentPlaylist == null) {
                            _suggestionPool.value = null
                            return@launch
                        }
                        val prefs =
                            SuggestionPreferences(
                                source = dataStorePrefs[PlaylistSuggestionSourceKey].toEnum(PlaylistSuggestionSource.BOTH),
                                hideExplicit = dataStorePrefs[HideExplicitKey] ?: false,
                                hideVideos = dataStorePrefs[HideVideoKey] ?: false,
                            )
                        if (!hasActiveSignals(currentPlaylist.playlist.name, currentPlaylist.playlist.browseId, currentSongs, prefs.source)) {
                            _suggestionPool.value = null
                            _currentSuggestionPage.value = 0
                            return@launch
                        }
                        val request =
                            PlaylistRecommendationRequest(
                                playlistName = currentPlaylist.playlist.name,
                                browseId = currentPlaylist.playlist.browseId,
                                radioEndpointParams = currentPlaylist.playlist.radioEndpointParams,
                                songs = currentSongs,
                                source = prefs.source,
                                usedSeedIds = usedSuggestionSeedIds,
                                previouslyShownIds = previouslyShownSuggestionIds.toSet(),
                                radioContinuation = suggestionRadioContinuation,
                                titlePlaylistOffset = suggestionTitlePlaylistOffset,
                                hideExplicit = prefs.hideExplicit,
                                hideVideos = prefs.hideVideos,
                            )
                        val result = recommendationRepository.loadRecommendations(request)
                        if (generation != suggestionLoadGeneration) return@launch
                        usedSuggestionSeedIds = result.usedSeedIds
                        suggestionRadioContinuation = result.nextRadioContinuation
                        suggestionTitlePlaylistOffset = result.nextTitlePlaylistOffset
                        viewedSuggestionIdsInCurrentPool.clear()
                        result.items
                            .take(PlaylistRecommendationPolicy.DEFAULT_PAGE_SIZE)
                            .forEach { viewedSuggestionIdsInCurrentPool.add(it.id) }
                        _currentSuggestionPage.value = 0
                        _suggestionPool.value = result.items
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        reportException(e)
                        if (generation == suggestionLoadGeneration && _suggestionPool.value == null) {
                            _suggestionPool.value = emptyList()
                        }
                    } finally {
                        if (generation == suggestionLoadGeneration) {
                            _isLoadingSuggestions.value = false
                        }
                    }
                }
        }

        suspend fun addSongToPlaylist(
            song: moe.rukamori.archivetune.innertube.models.SongItem,
            browseId: String?,
        ): Boolean {
            return try {
                val playlistSetVideoId =
                    if (browseId != null) {
                        withContext(Dispatchers.IO) {
                            YouTube.addToPlaylist(browseId, song.id).getOrThrow()
                        }
                    } else {
                        null
                    }

                val added =
                    database.withTransaction {
                        // Ensure playlist exists in local database (it should, but just in case)
                        val p = getPlaylistById(playlistId)
                        if (p == null) {
                            // If not found, we can't add to it.
                            // This might happen if it's a special playlist that hasn't been created yet.
                            if (playlistId == moe.rukamori.archivetune.db.entities.PlaylistEntity.LIKED_PLAYLIST_ID) {
                                insert(
                                    moe.rukamori.archivetune.db.entities.PlaylistEntity(
                                        id = playlistId,
                                        name = context.getString(R.string.liked_songs),
                                        isEditable = false,
                                        bookmarkedAt = java.time.LocalDateTime.now(),
                                    ),
                                )
                            } else {
                                return@withTransaction false
                            }
                        }

                        // First, ensure the song and its artists are in the database
                        insert(song.toMediaMetadata())

                        // Add to local playlist
                        val maxPosition = maxPlaylistSongPosition(playlistId)
                        val position = (maxPosition ?: -1) + 1
                        insert(
                            moe.rukamori.archivetune.db.entities.PlaylistSongMap(
                                songId = song.id,
                                playlistId = playlistId,
                                position = position,
                                setVideoId = playlistSetVideoId,
                            ),
                        )
                        true
                    }

                if (!added) {
                    return false
                }

                withContext(Dispatchers.Main.immediate) {
                    recordShownSuggestionIds(listOf(song.id))
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                reportException(e)
                false
            }
        }

        private data class SuggestionPreferences(
            val source: PlaylistSuggestionSource,
            val hideExplicit: Boolean,
            val hideVideos: Boolean,
        )

        private data class SuggestionTrigger(
            val effectiveTitle: String,
            val browseId: String?,
            val radioEndpointParams: String?,
            val hasSongs: Boolean,
            val preferences: SuggestionPreferences,
        )

        private data class SuggestionTriggerInput(
            val trigger: SuggestionTrigger,
            val playlistName: String,
            val songs: List<PlaylistSong>,
        )

        private companion object {
            const val MAX_REMEMBERED_SHOWN_SUGGESTIONS = 150
        }
    }

@Immutable
sealed interface PlaylistCoverState {
    data object Loading : PlaylistCoverState

    data object Success : PlaylistCoverState

    data object Empty : PlaylistCoverState

    data class Error(
        @StringRes val messageRes: Int,
    ) : PlaylistCoverState
}

sealed interface PlaylistCoverEvent {
    data class ShowMessage(
        @StringRes val messageRes: Int,
    ) : PlaylistCoverEvent
}
