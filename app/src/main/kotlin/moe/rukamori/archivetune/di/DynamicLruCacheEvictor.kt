/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.di

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.TreeSet

const val UNLIMITED_PLAYER_CACHE_BYTES = -1L
private const val CACHE_BYTES_PER_MEGABYTE = 1024L * 1024L

fun cacheLimitMegabytesToMaxBytes(sizeMegabytes: Int): Long =
    when {
        sizeMegabytes < 0 -> UNLIMITED_PLAYER_CACHE_BYTES
        sizeMegabytes == 0 -> 0L
        else ->
            sizeMegabytes.toLong().coerceAtMost(Long.MAX_VALUE / CACHE_BYTES_PER_MEGABYTE) *
                CACHE_BYTES_PER_MEGABYTE
    }

@OptIn(UnstableApi::class)
class DynamicLruCacheEvictor(
    initialMaxBytes: Long,
) : CacheEvictor {
    private val leastRecentlyUsed = TreeSet<CacheSpan>(::compareSpans)
    private var currentSize: Long = 0L

    @Volatile
    private var maxBytes: Long = initialMaxBytes

    @Volatile
    var protectedKey: String? = null
        private set

    private val _protectedKeyChanges =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val protectedKeyChanges: SharedFlow<Unit> = _protectedKeyChanges.asSharedFlow()

    fun setProtectedKey(key: String?) {
        if (protectedKey == key) return
        protectedKey = key
        _protectedKeyChanges.tryEmit(Unit)
    }

    fun updateMaxBytes(newMaxBytes: Long) {
        maxBytes = newMaxBytes
    }

    fun updateMaxSizeMegabytes(sizeMegabytes: Int) {
        maxBytes = cacheLimitMegabytesToMaxBytes(sizeMegabytes)
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(
        cache: Cache,
        key: String,
        position: Long,
        length: Long,
    ) {
        if (length != C.LENGTH_UNSET.toLong()) {
            evictCache(cache, requiredSpace = length, writingKey = key)
        }
    }

    override fun onSpanAdded(
        cache: Cache,
        span: CacheSpan,
    ) {
        if (leastRecentlyUsed.add(span)) {
            currentSize += span.length
        }
        evictCache(cache, requiredSpace = 0L, writingKey = span.key)
    }

    override fun onSpanRemoved(
        cache: Cache,
        span: CacheSpan,
    ) {
        if (leastRecentlyUsed.remove(span)) {
            currentSize = (currentSize - span.length).coerceAtLeast(0L)
        }
    }

    override fun onSpanTouched(
        cache: Cache,
        oldSpan: CacheSpan,
        newSpan: CacheSpan,
    ) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evictCache(
        cache: Cache,
        requiredSpace: Long,
        writingKey: String,
    ) {
        val limit = maxBytes
        if (limit < 0L) return
        val activeKey = protectedKey
        val safeRequired = requiredSpace.coerceAtLeast(0L)
        while (exceedsLimit(currentSize, safeRequired, limit) && leastRecentlyUsed.isNotEmpty()) {
            val candidate =
                leastRecentlyUsed.firstOrNull { span ->
                    span.key != activeKey && span.key != writingKey
                } ?: break
            val sizeBefore = leastRecentlyUsed.size
            cache.removeSpan(candidate)
            if (leastRecentlyUsed.size >= sizeBefore) {
                break
            }
        }
    }

    private fun exceedsLimit(
        currentSize: Long,
        requiredSpace: Long,
        limit: Long,
    ): Boolean = requiredSpace > limit || currentSize > limit - requiredSpace

    private fun compareSpans(
        lhs: CacheSpan,
        rhs: CacheSpan,
    ): Int =
        when {
            lhs.lastTouchTimestamp < rhs.lastTouchTimestamp -> -1
            lhs.lastTouchTimestamp > rhs.lastTouchTimestamp -> 1
            else -> lhs.compareTo(rhs)
        }
}

private data class TrimCandidate(
    val key: String,
    val lastTouchTimestamp: Long,
    val sizeBytes: Long,
)

@OptIn(UnstableApi::class)
internal fun trimCacheToBytes(
    cache: Cache,
    limitBytes: Long,
    protectedKey: String?,
    fallbackTotalBytes: () -> Long = { 0L },
) {
    if (limitBytes <= 0L) return

    val currentSpace = runCatching { cache.cacheSpace }.getOrNull() ?: 0L
    var totalBytes = if (currentSpace > 0L) currentSpace else fallbackTotalBytes()
    if (totalBytes <= limitBytes) return

    val candidates =
        runCatching {
            cache.keys
                .asSequence()
                .filter { key -> key != protectedKey }
                .mapNotNull { key ->
                    runCatching {
                        val spans = cache.getCachedSpans(key)
                        if (spans.isEmpty()) return@runCatching null
                        val oldestTouch = spans.minOf { it.lastTouchTimestamp }
                        val sizeBytes = spans.sumOf { it.length.coerceAtLeast(0L) }
                        TrimCandidate(key = key, lastTouchTimestamp = oldestTouch, sizeBytes = sizeBytes)
                    }.getOrNull()
                }.sortedWith(compareBy<TrimCandidate> { it.lastTouchTimestamp }.thenBy { it.key })
                .toList()
        }.getOrNull().orEmpty()

    for (candidate in candidates) {
        if (totalBytes <= limitBytes) break
        runCatching { cache.removeResource(candidate.key) }
            .onSuccess { totalBytes -= candidate.sizeBytes.coerceAtLeast(0L) }
    }
}
