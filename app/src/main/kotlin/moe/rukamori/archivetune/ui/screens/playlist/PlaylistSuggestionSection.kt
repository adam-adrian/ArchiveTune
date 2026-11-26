/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.playlist

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.NavigationTitle
import moe.rukamori.archivetune.ui.component.YouTubeListItem
import moe.rukamori.archivetune.viewmodels.LocalPlaylistViewModel

@Composable
fun PlaylistSuggestionsSection(
    modifier: Modifier = Modifier,
    swipeContentBackgroundColor: Color? = null,
    viewModel: LocalPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val isPlaying = playerConnection?.isPlaying?.collectAsStateWithLifecycle()?.value == true
    val mediaMetadata = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle()?.value

    val playlistSuggestions by viewModel.playlistSuggestions.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoadingSuggestions.collectAsStateWithLifecycle()

    // State for duplicate check dialog & in-flight add guard
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var songToCheck by remember { mutableStateOf<SongItem?>(null) }
    var addingSongIds by remember { mutableStateOf(emptySet<String>()) }

    val currentSuggestions = playlistSuggestions
    if (currentSuggestions == null && !isLoading) return

    val addSongAndNotify: suspend (SongItem) -> Unit = { song ->
        val browseId =
            viewModel.playlist.value
                ?.playlist
                ?.browseId
        val success = viewModel.addSongToPlaylist(song = song, browseId = browseId)
        if (success) {
            val playlistName =
                viewModel.playlist.value
                    ?.playlist
                    ?.name
            val message =
                if (playlistName != null) {
                    context.getString(R.string.added_to_playlist, playlistName)
                } else {
                    context.getString(R.string.add_to_playlist)
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.error_unknown, Toast.LENGTH_SHORT).show()
        }
    }

    // Duplicate Check Dialog
    if (showDuplicateDialog && songToCheck != null) {
        val song = songToCheck!!
        DefaultDialog(
            title = { Text(stringResource(R.string.duplicates)) },
            buttons = {
                TextButton(
                    onClick = {
                        showDuplicateDialog = false
                        songToCheck = null
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        if (song.id !in addingSongIds) {
                            addingSongIds = addingSongIds + song.id
                            coroutineScope.launch {
                                try {
                                    addSongAndNotify(song)
                                } finally {
                                    addingSongIds = addingSongIds - song.id
                                }
                            }
                        }
                        showDuplicateDialog = false
                        songToCheck = null
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.add_anyway))
                }
            },
            onDismiss = {
                showDuplicateDialog = false
                songToCheck = null
            },
        ) {
            Text(text = stringResource(R.string.duplicates_description_single))
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavigationTitle(
                title = stringResource(R.string.you_might_like),
                subtitle =
                    currentSuggestions?.let { s ->
                        if (s.totalPages > 1) {
                            "${s.currentPage + 1} / ${s.totalPages}"
                        } else {
                            null
                        }
                    },
                modifier = Modifier.weight(1f),
            )
            if (currentSuggestions != null && currentSuggestions.totalPages > 1) {
                androidx.compose.material3.IconButton(
                    onClick = remember(viewModel) { { viewModel.changeSuggestionPage(-1) } },
                    enabled = !isLoading && currentSuggestions.currentPage > 0,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = stringResource(R.string.widget_previous),
                    )
                }
                androidx.compose.material3.IconButton(
                    onClick = remember(viewModel) { { viewModel.changeSuggestionPage(1) } },
                    enabled = !isLoading &&
                        currentSuggestions.currentPage < currentSuggestions.totalPages - 1,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward),
                        contentDescription = stringResource(R.string.next),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        currentSuggestions?.let { suggestions ->
            // Suggestions List (Vertical)
            suggestions.items.forEach { songItem ->
                key(songItem.id) {
                    val isAdding = songItem.id in addingSongIds
                    YouTubeListItem(
                        item = songItem,
                        swipeContentBackgroundColor = swipeContentBackgroundColor,
                        isActive = songItem.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        trailingContent = {
                            IconButton(
                                enabled = !isAdding,
                                onClick = {
                                    if (songItem.id in addingSongIds) return@IconButton
                                    addingSongIds = addingSongIds + songItem.id
                                    coroutineScope.launch {
                                        try {
                                            val isDuplicate =
                                                withContext(Dispatchers.IO) {
                                                    val duplicates =
                                                        database.playlistDuplicates(
                                                            viewModel.playlistId,
                                                            listOf(songItem.id),
                                                        )
                                                    duplicates.isNotEmpty()
                                                }

                                            if (isDuplicate) {
                                                songToCheck = songItem
                                                showDuplicateDialog = true
                                            } else {
                                                addSongAndNotify(songItem)
                                            }
                                        } finally {
                                            addingSongIds = addingSongIds - songItem.id
                                        }
                                    }
                                },
                                onLongClick = {},
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.playlist_add),
                                    contentDescription = stringResource(R.string.add_to_playlist),
                                )
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    if (playerConnection == null) return@clickable
                                    if (songItem.id == mediaMetadata?.id) {
                                        playerConnection.player.togglePlayPause()
                                    } else {
                                        val songItems = suggestions.items
                                        val startIndex = songItems.indexOfFirst { it.id == songItem.id }
                                        if (startIndex != -1) {
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = context.getString(R.string.you_might_like),
                                                    items = songItems.map { it.toMediaItem() },
                                                    startIndex = startIndex,
                                                ),
                                            )
                                        }
                                    }
                                },
                    )
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                TextButton(
                    onClick = { viewModel.resetAndLoadPlaylistSuggestions() },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.sync),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = stringResource(R.string.refresh_suggestions))
                    }
                }
            }
        }
    }
}
