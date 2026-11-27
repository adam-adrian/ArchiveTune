/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.cache

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import java.io.File
import java.util.NavigableSet
import java.util.TreeSet

@OptIn(UnstableApi::class)
internal open class FakeCache : Cache {
    private val stateLock = Any()
    private val spansByKey = mutableMapOf<String, TreeSet<CacheSpan>>()
    private val metadataByKey = mutableMapOf<String, DefaultContentMetadata>()
    val removedResources = mutableListOf<String>()

    fun putSpan(
        key: String,
        position: Long,
        length: Long,
        lastTouchTimestamp: Long = 0L,
    ): CacheSpan {
        val span = CacheSpan(key, position, length, lastTouchTimestamp, File("fake-$key-$position"))
        synchronized(stateLock) {
            spansByKey.getOrPut(key) { TreeSet() }.add(span)
        }
        return span
    }

    override fun addListener(
        key: String,
        listener: Cache.Listener,
    ): NavigableSet<CacheSpan> = getCachedSpans(key)

    override fun removeListener(
        key: String,
        listener: Cache.Listener,
    ) = Unit

    override fun getCachedSpans(key: String): NavigableSet<CacheSpan> =
        synchronized(stateLock) {
            TreeSet(spansByKey[key].orEmpty())
        }

    override fun getKeys(): NavigableSet<String> =
        synchronized(stateLock) {
            TreeSet(spansByKey.keys)
        }

    override fun getCacheSpace(): Long =
        synchronized(stateLock) {
            spansByKey.values.flatten().sumOf { span -> span.length.coerceAtLeast(0L) }
        }

    override fun getUid(): Long = 1L

    override fun getCachedLength(
        key: String,
        position: Long,
        length: Long,
    ): Long {
        val maxLength = if (length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else length
        if (maxLength <= 0L) return 0L
        val spans = getCachedSpans(key)
        for (span in spans) {
            if (!span.isCached) continue
            val spanEnd = span.position + span.length
            if (span.position <= position && position < spanEnd) {
                return minOf(maxLength, spanEnd - position)
            }
            if (span.position > position) {
                return -minOf(maxLength, span.position - position)
            }
        }
        return -maxLength
    }

    override fun getCachedBytes(
        key: String,
        position: Long,
        length: Long,
    ): Long {
        val maxLength = if (length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else length
        if (maxLength <= 0L) return 0L
        val end = if (position > Long.MAX_VALUE - maxLength) Long.MAX_VALUE else position + maxLength
        var total = 0L
        for (span in getCachedSpans(key)) {
            if (!span.isCached) continue
            val spanEnd = span.position + span.length
            val overlapStart = maxOf(position, span.position)
            val overlapEnd = minOf(end, spanEnd)
            if (overlapEnd > overlapStart) {
                total += overlapEnd - overlapStart
            }
        }
        return total
    }

    override fun applyContentMetadataMutations(
        key: String,
        mutations: ContentMetadataMutations,
    ) {
        synchronized(stateLock) {
            val current = metadataByKey[key] ?: DefaultContentMetadata.EMPTY
            metadataByKey[key] = current.copyWithMutationsApplied(mutations)
        }
    }

    override fun getContentMetadata(key: String): ContentMetadata =
        synchronized(stateLock) {
            metadataByKey[key] ?: DefaultContentMetadata.EMPTY
        }

    override fun startReadWrite(
        key: String,
        position: Long,
        length: Long,
    ): CacheSpan = CacheSpan(key, position, length)

    override fun startReadWriteNonBlocking(
        key: String,
        position: Long,
        length: Long,
    ): CacheSpan? = CacheSpan(key, position, length)

    override fun startFile(
        key: String,
        position: Long,
        maxLength: Long,
    ): File = File("fake-$key-$position")

    override fun commitFile(
        file: File,
        length: Long,
    ) = Unit

    override fun releaseHoleSpan(holeSpan: CacheSpan) = Unit

    override fun removeSpan(span: CacheSpan) {
        synchronized(stateLock) {
            val spans = spansByKey[span.key] ?: return
            spans.remove(span)
            if (spans.isEmpty()) {
                spansByKey.remove(span.key)
            }
        }
    }

    override fun removeResource(key: String) {
        synchronized(stateLock) {
            spansByKey.remove(key)
            metadataByKey.remove(key)
            removedResources += key
        }
    }

    override fun isCached(
        key: String,
        position: Long,
        length: Long,
    ): Boolean {
        if (length <= 0L) return false
        var currentPosition = position
        var remaining = length
        for (span in getCachedSpans(key)) {
            if (!span.isCached) continue
            val spanEnd = span.position + span.length
            if (span.position <= currentPosition && currentPosition < spanEnd) {
                val covered = minOf(remaining, spanEnd - currentPosition)
                currentPosition += covered
                remaining -= covered
                if (remaining == 0L) return true
            } else if (span.position > currentPosition) {
                return false
            }
        }
        return false
    }

    override fun release() = Unit
}
