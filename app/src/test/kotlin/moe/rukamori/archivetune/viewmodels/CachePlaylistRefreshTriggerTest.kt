/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import app.cash.turbine.test
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CachePlaylistRefreshTriggerTest {
    @Test
    fun emitsInitialFilterImmediatelyWithoutWaitingForCacheInvalidation() =
        runBlocking {
            val initial = CacheFilterPreferences(hideExplicit = false, hideVideo = true)
            val filters = MutableStateFlow(initial)
            val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

            observeCacheRefreshTriggers(
                filterPreferences = filters,
                cacheInvalidations = invalidations,
                debounceMs = 1_000L,
            ).test {
                assertEquals(initial, awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun coalescesRapidBurstOfCacheInvalidationsIntoSingleRefresh() =
        runBlocking {
            val initial = CacheFilterPreferences(hideExplicit = false, hideVideo = false)
            val filters = MutableStateFlow(initial)
            val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

            observeCacheRefreshTriggers(
                filterPreferences = filters,
                cacheInvalidations = invalidations,
                debounceMs = 60L,
            ).test {
                assertEquals(initial, awaitItem())

                repeat(5) {
                    invalidations.tryEmit(Unit)
                }

                assertEquals(initial, awaitItem())
                delay(120L)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun filterChangesTriggerImmediatelyAndDeduplicateIdenticalEmissions() =
        runBlocking {
            val initial = CacheFilterPreferences(hideExplicit = false, hideVideo = false)
            val filters = MutableSharedFlow<CacheFilterPreferences>(replay = 1, extraBufferCapacity = 8)
            filters.tryEmit(initial)
            val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

            observeCacheRefreshTriggers(
                filterPreferences = filters,
                cacheInvalidations = invalidations,
                debounceMs = 60L,
            ).test {
                assertEquals(initial, awaitItem())

                // Duplicate identical filter preference must not trigger a refresh
                filters.tryEmit(CacheFilterPreferences(hideExplicit = false, hideVideo = false))
                delay(100L)
                expectNoEvents()

                val hideExplicitOnly = CacheFilterPreferences(hideExplicit = true, hideVideo = false)
                filters.tryEmit(hideExplicitOnly)
                assertEquals(hideExplicitOnly, awaitItem())

                val hideBoth = CacheFilterPreferences(hideExplicit = true, hideVideo = true)
                filters.tryEmit(hideBoth)
                assertEquals(hideBoth, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }
}
