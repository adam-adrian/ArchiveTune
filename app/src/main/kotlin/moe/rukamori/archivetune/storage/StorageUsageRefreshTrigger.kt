/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.storage

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge

internal const val STORAGE_USAGE_REFRESH_DEBOUNCE_MILLIS = 250L

@OptIn(FlowPreview::class)
internal fun observeStorageUsageRefreshTriggers(
    mediaCacheInvalidations: Flow<Unit>,
    canvasCacheInvalidations: Flow<Unit>,
    manualRefreshes: Flow<Unit>,
    debounceMillis: Long = STORAGE_USAGE_REFRESH_DEBOUNCE_MILLIS,
): Flow<Unit> =
    merge(
        flowOf(Unit),
        manualRefreshes,
        merge(
            mediaCacheInvalidations,
            canvasCacheInvalidations,
        ).debounce(debounceMillis),
    )
