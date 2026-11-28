/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune

import coil3.disk.DiskCache
import coil3.request.CachePolicy
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImageDiskCacheTest {
    @Test
    fun resolveImageDiskCacheConfigMapsLimitsCorrectly() {
        val mib = 1024L * 1024L

        assertEquals(
            ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = 512L * mib),
            resolveImageDiskCacheConfig(null),
        )
        assertEquals(
            ImageDiskCacheConfig(policy = CachePolicy.DISABLED, maxSizeBytes = 0L),
            resolveImageDiskCacheConfig(0),
        )
        assertEquals(
            ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = Long.MAX_VALUE),
            resolveImageDiskCacheConfig(-1),
        )
        assertEquals(
            ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = 256L * mib),
            resolveImageDiskCacheConfig(256),
        )
        assertEquals(
            ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = Int.MAX_VALUE.toLong() * mib),
            resolveImageDiskCacheConfig(Int.MAX_VALUE),
        )
    }

    @Test
    fun startupDisabledNeverCreatesDelegateAndClearsUnopenedDirectory() {
        val cacheDir = "/virtual/coil".toPath()
        var createCalls = 0
        var unopenedClears = 0

        val cache =
            LazyToggleableImageDiskCache(
                directory = cacheDir,
                initialConfig = resolveImageDiskCacheConfig(0),
                trimOnFirstInit = true,
                createDelegate = { limit ->
                    createCalls++
                    FakeDiskCache(directory = cacheDir, maxSize = limit)
                },
                clearUnopenedDirectory = { unopenedClears++ },
            )

        assertEquals(0L, cache.size)
        assertEquals(0L, cache.maxSize)
        assertNull(cache.openSnapshot("cover-1"))
        assertNull(cache.openEditor("cover-1"))
        assertFalse(cache.remove("cover-1"))
        assertEquals(0, createCalls)

        cache.clear()
        assertEquals(1, unopenedClears)
        assertEquals(0, createCalls)
    }

    @Test
    fun liveToggleZeroToPositiveLazilyCreatesDelegateWithUpdatedLimit() {
        val cacheDir = "/virtual/coil".toPath()
        val createdLimits = mutableListOf<Long>()
        val delegates = mutableListOf<FakeDiskCache>()

        val cache =
            LazyToggleableImageDiskCache(
                directory = cacheDir,
                initialConfig = resolveImageDiskCacheConfig(0),
                trimOnFirstInit = false,
                createDelegate = { limit ->
                    createdLimits += limit
                    FakeDiskCache(directory = cacheDir, maxSize = limit).also(delegates::add)
                },
            )

        assertNull(cache.openEditor("cover-1"))
        assertEquals(0, delegates.size)

        cache.updateLimitMegabytes(512)
        assertEquals(512L * 1024L * 1024L, cache.maxSize)

        val editor = cache.openEditor("cover-1")
        assertNotNull(editor)
        editor?.commit()

        val snapshot = cache.openSnapshot("cover-1")
        assertNotNull(snapshot)
        snapshot?.close()
        assertEquals(listOf(512L * 1024L * 1024L), createdLimits)
        assertEquals(1, delegates.size)
    }

    @Test
    fun liveTogglePositiveToZeroDisablesReadsWritesWhileStillAllowingClearWithoutDuplicateDelegate() {
        val cacheDir = "/virtual/coil".toPath()
        val delegates = mutableListOf<FakeDiskCache>()

        val cache =
            LazyToggleableImageDiskCache(
                directory = cacheDir,
                initialConfig = resolveImageDiskCacheConfig(512),
                trimOnFirstInit = false,
                createDelegate = { limit ->
                    FakeDiskCache(directory = cacheDir, maxSize = limit, initialSize = 4_096L).also(delegates::add)
                },
            )

        cache.openEditor("cover-1")?.commit()
        assertEquals(1, delegates.size)
        val snapshot = cache.openSnapshot("cover-1")
        assertNotNull(snapshot)
        snapshot?.close()

        cache.updateLimitMegabytes(0)
        assertEquals(0L, cache.maxSize)
        assertNull(cache.openSnapshot("cover-1"))
        assertNull(cache.openEditor("cover-2"))

        cache.clear()
        assertEquals(1, delegates.single().clearCalls)
        assertEquals(0L, cache.size)

        cache.updateLimitMegabytes(512)
        val editor2 = cache.openEditor("cover-2")
        assertNotNull(editor2)
        editor2?.commit()
        assertEquals(1, delegates.size)
    }

    @Test
    fun startupTrimRecreatesDelegateOnlyWhenInitialSizeExceedsFiniteLimit() {
        val cacheDir = "/virtual/coil".toPath()
        val limitBytes = 10L * 1024L * 1024L

        // initialSize > limitBytes -> shutdown first delegate once and create fresh second delegate
        val overLimitDelegates = mutableListOf<FakeDiskCache>()
        val trimmedCache =
            LazyToggleableImageDiskCache(
                directory = cacheDir,
                initialConfig = resolveImageDiskCacheConfig(10),
                trimOnFirstInit = true,
                createDelegate = { limit ->
                    val size = if (overLimitDelegates.isEmpty()) limitBytes + 1_024L else limitBytes - 512L
                    FakeDiskCache(directory = cacheDir, maxSize = limit, initialSize = size).also(overLimitDelegates::add)
                },
            )
        trimmedCache.openSnapshot("probe")
        assertEquals(2, overLimitDelegates.size)
        assertEquals(1, overLimitDelegates[0].shutdownCalls)
        assertEquals(0, overLimitDelegates[1].shutdownCalls)

        // initialSize <= limitBytes -> keep first delegate
        val underLimitDelegates = mutableListOf<FakeDiskCache>()
        val untrimmedUnderLimitCache =
            LazyToggleableImageDiskCache(
                directory = cacheDir,
                initialConfig = resolveImageDiskCacheConfig(10),
                trimOnFirstInit = true,
                createDelegate = { limit ->
                    FakeDiskCache(directory = cacheDir, maxSize = limit, initialSize = limitBytes).also(underLimitDelegates::add)
                },
            )
        untrimmedUnderLimitCache.openSnapshot("probe")
        assertEquals(1, underLimitDelegates.size)
        assertEquals(0, underLimitDelegates[0].shutdownCalls)

        // Unlimited (-1) -> skip startup trim
        val unlimitedDelegates = mutableListOf<FakeDiskCache>()
        val unlimitedCache =
            LazyToggleableImageDiskCache(
                directory = cacheDir,
                initialConfig = resolveImageDiskCacheConfig(-1),
                trimOnFirstInit = true,
                createDelegate = { limit ->
                    FakeDiskCache(directory = cacheDir, maxSize = limit, initialSize = limitBytes * 4L).also(unlimitedDelegates::add)
                },
            )
        unlimitedCache.openSnapshot("probe")
        assertEquals(1, unlimitedDelegates.size)
        assertEquals(0, unlimitedDelegates[0].shutdownCalls)

        // trimOnFirstInit = false -> skip startup trim even when over limit
        val disabledTrimDelegates = mutableListOf<FakeDiskCache>()
        val noTrimCache =
            LazyToggleableImageDiskCache(
                directory = cacheDir,
                initialConfig = resolveImageDiskCacheConfig(10),
                trimOnFirstInit = false,
                createDelegate = { limit ->
                    FakeDiskCache(directory = cacheDir, maxSize = limit, initialSize = limitBytes + 1_024L).also(disabledTrimDelegates::add)
                },
            )
        noTrimCache.openSnapshot("probe")
        assertEquals(1, disabledTrimDelegates.size)
        assertEquals(0, disabledTrimDelegates[0].shutdownCalls)
    }

    @Test
    fun shutdownClosesActiveDelegateAndBlocksSubsequentCreation() {
        val cacheDir = "/virtual/coil".toPath()
        val delegates = mutableListOf<FakeDiskCache>()

        val cache =
            LazyToggleableImageDiskCache(
                directory = cacheDir,
                initialConfig = resolveImageDiskCacheConfig(512),
                trimOnFirstInit = false,
                createDelegate = { limit ->
                    FakeDiskCache(directory = cacheDir, maxSize = limit).also(delegates::add)
                },
            )

        cache.openEditor("cover-1")?.commit()
        assertEquals(1, delegates.size)

        cache.shutdown()
        assertEquals(1, delegates.single().shutdownCalls)

        assertNull(cache.openSnapshot("cover-1"))
        assertNull(cache.openEditor("cover-2"))
        assertEquals(0L, cache.size)
        assertFalse(cache.remove("cover-1"))
        assertEquals(1, delegates.size)
    }

    private class FakeDiskCache(
        override val directory: Path,
        override val maxSize: Long,
        initialSize: Long = 0L,
    ) : DiskCache {
        override val fileSystem: FileSystem = FileSystem.SYSTEM
        private val entries = linkedSetOf<String>()
        private var currentSize: Long = initialSize
        var clearCalls: Int = 0
            private set
        var shutdownCalls: Int = 0
            private set

        override val size: Long
            get() = currentSize

        override fun openSnapshot(key: String): DiskCache.Snapshot? =
            if (key in entries) FakeSnapshot(key) else null

        override fun openEditor(key: String): DiskCache.Editor? = FakeEditor(key)

        override fun remove(key: String): Boolean {
            val removed = entries.remove(key)
            if (removed && entries.isEmpty()) {
                currentSize = 0L
            }
            return removed
        }

        override fun clear() {
            clearCalls++
            entries.clear()
            currentSize = 0L
        }

        override fun shutdown() {
            shutdownCalls++
        }

        private inner class FakeSnapshot(
            private val key: String,
        ) : DiskCache.Snapshot {
            override val metadata: Path = "$directory/$key.meta".toPath()
            override val data: Path = "$directory/$key.data".toPath()

            override fun close() = Unit

            override fun closeAndOpenEditor(): DiskCache.Editor = FakeEditor(key)
        }

        private inner class FakeEditor(
            private val key: String,
        ) : DiskCache.Editor {
            override val metadata: Path = "$directory/$key.meta".toPath()
            override val data: Path = "$directory/$key.data".toPath()

            override fun commit() {
                entries += key
                if (currentSize == 0L) {
                    currentSize = 1_024L
                }
            }

            override fun commitAndOpenSnapshot(): DiskCache.Snapshot {
                commit()
                return FakeSnapshot(key)
            }

            override fun abort() = Unit
        }
    }
}
