/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.storage

import app.cash.turbine.test
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageUsageRefreshTriggerTest {
    @Test
    fun emitsInitialTriggerImmediatelyWithoutWaitingForDebounce() =
        runBlocking {
            val mediaInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val canvasInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val manualRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

            observeStorageUsageRefreshTriggers(
                mediaCacheInvalidations = mediaInvalidations,
                canvasCacheInvalidations = canvasInvalidations,
                manualRefreshes = manualRefreshes,
                debounceMillis = 1_000L,
            ).test {
                assertEquals(Unit, awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun emitsManualRefreshImmediatelyWithoutWaitingForDebounce() =
        runBlocking {
            val mediaInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val canvasInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val manualRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

            observeStorageUsageRefreshTriggers(
                mediaCacheInvalidations = mediaInvalidations,
                canvasCacheInvalidations = canvasInvalidations,
                manualRefreshes = manualRefreshes,
                debounceMillis = 1_000L,
            ).test {
                assertEquals(Unit, awaitItem())

                manualRefreshes.tryEmit(Unit)
                assertEquals(Unit, awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun coalescesRapidBurstOfMediaAndCanvasInvalidationsIntoSingleTrigger() =
        runBlocking {
            val mediaInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
            val canvasInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
            val manualRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

            observeStorageUsageRefreshTriggers(
                mediaCacheInvalidations = mediaInvalidations,
                canvasCacheInvalidations = canvasInvalidations,
                manualRefreshes = manualRefreshes,
                debounceMillis = 60L,
            ).test {
                assertEquals(Unit, awaitItem())

                repeat(4) {
                    mediaInvalidations.tryEmit(Unit)
                    canvasInvalidations.tryEmit(Unit)
                }

                assertEquals(Unit, awaitItem())
                delay(120L)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun doesNotEmitPeriodicallyWhileIdle() =
        runBlocking {
            val mediaInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val canvasInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
            val manualRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

            observeStorageUsageRefreshTriggers(
                mediaCacheInvalidations = mediaInvalidations,
                canvasCacheInvalidations = canvasInvalidations,
                manualRefreshes = manualRefreshes,
                debounceMillis = 40L,
            ).test {
                assertEquals(Unit, awaitItem())
                delay(160L)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
}
