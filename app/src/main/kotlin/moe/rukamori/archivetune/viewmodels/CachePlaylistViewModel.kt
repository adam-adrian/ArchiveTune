/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.di.CacheInvalidationSignal
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.di.PlayerCache
import moe.rukamori.archivetune.extensions.filterExplicit
import moe.rukamori.archivetune.extensions.filterVideo
import moe.rukamori.archivetune.utils.dataStore
import java.time.LocalDateTime
import javax.inject.Inject

internal data class CacheFilterPreferences(
    val hideExplicit: Boolean,
    val hideVideo: Boolean,
)

@OptIn(FlowPreview::class)
internal fun observeCacheRefreshTriggers(
    filterPreferences: Flow<CacheFilterPreferences>,
    cacheInvalidations: Flow<Unit>,
    debounceMs: Long,
): Flow<CacheFilterPreferences> =
    combine(
        filterPreferences.distinctUntilChanged(),
        cacheInvalidations
            .debounce(debounceMs)
            .onStart { emit(Unit) },
    ) { filters, _ -> filters }

@HiltViewModel
class CachePlaylistViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        @PlayerCache private val playerCache: Cache,
        @DownloadCache private val downloadCache: Cache,
        private val cacheInvalidationSignal: CacheInvalidationSignal,
    ) : ViewModel() {
        private val _cachedSongs = MutableStateFlow<List<Song>>(emptyList())
        val cachedSongs: StateFlow<List<Song>> = _cachedSongs

        init {
            val filterPreferences =
                context.dataStore.data.map { prefs ->
                    CacheFilterPreferences(
                        hideExplicit = prefs[HideExplicitKey] ?: false,
                        hideVideo = prefs[HideVideoKey] ?: false,
                    )
                }
            viewModelScope.launch(Dispatchers.IO) {
                observeCacheRefreshTriggers(
                    filterPreferences = filterPreferences,
                    cacheInvalidations = cacheInvalidationSignal.invalidations,
                    debounceMs = CACHE_REFRESH_DEBOUNCE_MS,
                ).conflate()
                    .collect(::refreshCachedSongs)
            }
        }

        private suspend fun refreshCachedSongs(filters: CacheFilterPreferences) {
            val pureCacheIds = playerCache.keys.toSet().subtract(downloadCache.keys.toSet())

            val songs =
                pureCacheIds
                    .chunked(SONG_QUERY_BATCH_SIZE)
                    .flatMap { ids -> database.getSongsByIds(ids) }

            val completeSongs = songs.filter { it.isFullyCached() }

            val cachedAt = LocalDateTime.now()
            val undatedSongs = completeSongs.filter { it.song.dateDownload == null }
            if (undatedSongs.isNotEmpty()) {
                database.query {
                    undatedSongs.forEach { update(it.song.copy(dateDownload = cachedAt)) }
                }
            }

            _cachedSongs.value =
                completeSongs
                    .map { if (it.song.dateDownload == null) it.copy(song = it.song.copy(dateDownload = cachedAt)) else it }
                    .sortedByDescending { it.song.dateDownload }
                    .filterExplicit(filters.hideExplicit)
                    .filterVideo(filters.hideVideo)
        }

        fun removeSongFromCache(songId: String) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { playerCache.removeResource(songId) }
                cacheInvalidationSignal.notifyChanged()
            }
        }

        private fun Song.isFullyCached(): Boolean {
            val contentLength =
                ContentMetadata
                    .getContentLength(playerCache.getContentMetadata(id))
                    .takeIf { it > 0L }
                    ?: format?.takeIf { it.sourceId == null }?.contentLength?.takeIf { it > 0L }
                    ?: return false
            return playerCache.isCached(id, 0L, contentLength)
        }

        private companion object {
            const val SONG_QUERY_BATCH_SIZE = 500
            const val CACHE_REFRESH_DEBOUNCE_MS = 300L
        }
    }
