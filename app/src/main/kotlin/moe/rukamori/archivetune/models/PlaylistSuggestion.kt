/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.models

import androidx.compose.runtime.Immutable
import moe.rukamori.archivetune.innertube.models.SongItem

@Immutable
data class PlaylistSuggestion(
    val items: List<SongItem>,
    val currentPage: Int,
    val totalPages: Int,
)
