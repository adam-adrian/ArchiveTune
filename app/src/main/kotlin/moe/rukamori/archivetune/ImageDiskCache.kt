/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune

import coil3.disk.DiskCache
import coil3.request.CachePolicy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okio.FileSystem
import okio.Path
import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_MAX_IMAGE_CACHE_SIZE_MB = 512
private const val BYTES_PER_MEGABYTE = 1024L * 1024L

@Singleton
class ImageCacheInvalidationSignal
    @Inject
    constructor() {
        private val _invalidations =
            MutableSharedFlow<Unit>(
                extraBufferCapacity = 1,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val invalidations: SharedFlow<Unit> = _invalidations.asSharedFlow()

        fun notifyChanged() {
            _invalidations.tryEmit(Unit)
        }
    }

internal data class ImageDiskCacheConfig(
    val policy: CachePolicy,
    val maxSizeBytes: Long,
)

internal fun resolveImageDiskCacheConfig(maxImageCacheSizeMb: Int?): ImageDiskCacheConfig {
    val sizeMb = maxImageCacheSizeMb ?: DEFAULT_MAX_IMAGE_CACHE_SIZE_MB
    if (sizeMb == 0) return ImageDiskCacheConfig(policy = CachePolicy.DISABLED, maxSizeBytes = 0L)
    if (sizeMb < 0) return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = Long.MAX_VALUE)
    val safeSizeMb = sizeMb.toLong().coerceAtMost(Long.MAX_VALUE / BYTES_PER_MEGABYTE)
    return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = safeSizeMb * BYTES_PER_MEGABYTE)
}

internal class LazyToggleableImageDiskCache(
    override val directory: Path,
    override val fileSystem: FileSystem = FileSystem.SYSTEM,
    initialConfig: ImageDiskCacheConfig,
    private val trimOnFirstInit: Boolean,
    private val createDelegate: (Long) -> DiskCache,
    private val clearUnopenedDirectory: () -> Unit = {},
    private val onCacheChanged: () -> Unit = {},
) : DiskCache {
    private val lock = Any()

    @Volatile private var enabled: Boolean =
        initialConfig.policy == CachePolicy.ENABLED && initialConfig.maxSizeBytes > 0L

    @Volatile private var pendingMaxSizeBytes: Long = initialConfig.maxSizeBytes

    @Volatile private var delegate: DiskCache? = null

    @Volatile private var isShutDown: Boolean = false

    private var didStartupTrim: Boolean = false

    fun updateLimitMegabytes(maxImageCacheSizeMb: Int?) {
        val config = resolveImageDiskCacheConfig(maxImageCacheSizeMb)
        synchronized(lock) {
            enabled = config.policy == CachePolicy.ENABLED && config.maxSizeBytes > 0L
            if (delegate == null && config.maxSizeBytes > 0L) {
                pendingMaxSizeBytes = config.maxSizeBytes
            }
        }
    }

    private fun ensureDelegate(): DiskCache? {
        if (!enabled || isShutDown) return null
        delegate?.let { return it }
        var didTrim = false
        val createdDelegate =
            synchronized(lock) {
                if (!enabled || isShutDown) return null
                delegate?.let { return it }
                val limitBytes = pendingMaxSizeBytes
                if (limitBytes <= 0L) return null
                var created = createDelegate(limitBytes)
                if (trimOnFirstInit && !didStartupTrim && limitBytes < Long.MAX_VALUE) {
                    didStartupTrim = true
                    val initialSize = runCatching { created.size }.getOrDefault(0L)
                    if (initialSize > limitBytes) {
                        runCatching { created.shutdown() }
                        created = createDelegate(limitBytes)
                        didTrim = true
                    }
                }
                delegate = created
                created
            }
        if (didTrim) {
            onCacheChanged()
        }
        return createdDelegate
    }

    override val size: Long
        get() = if (enabled) ensureDelegate()?.size ?: 0L else delegate?.size ?: 0L

    override val maxSize: Long
        get() = if (!enabled) 0L else (delegate?.maxSize ?: pendingMaxSizeBytes)

    override fun openSnapshot(key: String): DiskCache.Snapshot? =
        if (!enabled) null else ensureDelegate()?.openSnapshot(key)?.let(::NotifyingSnapshot)

    override fun openEditor(key: String): DiskCache.Editor? =
        if (!enabled) null else ensureDelegate()?.openEditor(key)?.let(::NotifyingEditor)

    override fun remove(key: String): Boolean {
        val removed =
            if (!enabled) {
                delegate?.remove(key) ?: false
            } else {
                ensureDelegate()?.remove(key) ?: false
            }
        if (removed) {
            onCacheChanged()
        }
        return removed
    }

    override fun clear() {
        val existing = synchronized(lock) { delegate }
        if (existing != null) {
            existing.clear()
        } else {
            clearUnopenedDirectory()
        }
        onCacheChanged()
    }

    override fun shutdown() {
        val existing =
            synchronized(lock) {
                isShutDown = true
                delegate.also { delegate = null }
            }
        existing?.shutdown()
    }

    private inner class NotifyingEditor(
        private val delegate: DiskCache.Editor,
    ) : DiskCache.Editor {
        override val metadata: Path
            get() = delegate.metadata

        override val data: Path
            get() = delegate.data

        override fun commit() {
            delegate.commit()
            onCacheChanged()
        }

        override fun commitAndOpenSnapshot(): DiskCache.Snapshot? {
            val snapshot = delegate.commitAndOpenSnapshot()
            onCacheChanged()
            return snapshot?.let(::NotifyingSnapshot)
        }

        override fun abort() {
            delegate.abort()
        }
    }

    private inner class NotifyingSnapshot(
        private val delegate: DiskCache.Snapshot,
    ) : DiskCache.Snapshot {
        override val metadata: Path
            get() = delegate.metadata

        override val data: Path
            get() = delegate.data

        override fun close() {
            delegate.close()
        }

        override fun closeAndOpenEditor(): DiskCache.Editor? =
            delegate.closeAndOpenEditor()?.let(::NotifyingEditor)
    }
}
