/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playlist

import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import moe.rukamori.archivetune.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import moe.rukamori.archivetune.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import java.util.Locale
import kotlin.random.Random

internal data class PlaylistSeedCandidate(
    val id: String,
    val title: String,
    val artistKey: String,
    val artistName: String,
    val totalPlayTime: Long,
    val position: Int,
    val isLocal: Boolean,
    val isPodcast: Boolean,
    val isYouTubeId: Boolean,
)

internal enum class RecommendationBatchKind(
    val baseWeight: Double,
    val isTitleSourced: Boolean = false,
) {
    PLAYLIST_RADIO(baseWeight = 4.0),
    SONG_RELATED(baseWeight = 3.2),
    SONG_NEXT(baseWeight = 2.6),
    CACHED_RELATED(baseWeight = 2.0),
    LOCAL_FALLBACK(baseWeight = 2.0),
    TITLE_PLAYLIST(baseWeight = 2.2, isTitleSourced = true),
    TITLE_SEARCH(baseWeight = 1.4, isTitleSourced = true),
}

internal data class RecommendationBatch(
    val kind: RecommendationBatchKind,
    val sourceKey: String,
    val items: List<SongItem>,
)

internal object PlaylistRecommendationPolicy {
    const val YOUTUBE_VIDEO_ID_LENGTH = 11
    const val DEFAULT_PAGE_SIZE = 10
    const val MAX_POOL_SIZE = 50
    const val MAX_PER_ARTIST_PER_PAGE = 2
    const val TITLE_SHARE_CAP_IN_BOTH = 2

    private const val RANK_DECAY_STEP = 0.08
    private const val CONSENSUS_BONUS = 3.0
    private const val ARTIST_AFFINITY_BONUS = 0.4
    private const val PREVIOUSLY_SHOWN_MULTIPLIER = 0.25

    private val GENERIC_PLAYLIST_TITLES =
        setOf(
            "all",
            "best",
            "daftar putar",
            "download",
            "downloaded",
            "downloads",
            "fav",
            "favorit",
            "favorite",
            "favorites",
            "favourite",
            "favourites",
            "favs",
            "lagu",
            "liked",
            "liked music",
            "liked songs",
            "list",
            "misc",
            "mix",
            "music",
            "musik",
            "my music",
            "my playlist",
            "my songs",
            "new",
            "new playlist",
            "other",
            "playlist",
            "queue",
            "random",
            "saved",
            "songs",
            "temp",
            "test",
            "top",
            "tracks",
            "untitled",
            "untitled playlist",
        )

    private val NON_ALNUM_REGEX = Regex("[^\\p{L}\\p{M}\\p{Nd}]+")
    private val TRAILING_NUMBER_TOKEN_REGEX = Regex("^(?:\\d+|v\\d+|no\\d+|part\\d+|pt\\d+|vol\\d+)$")
    private val TRAILING_NUMBER_PREFIX_TOKENS = setOf("v", "no", "num", "part", "pt", "vol", "volume", "ep")
    private val DECADE_TOKEN_REGEX = Regex("^\\d{2}s$")
    private val SHORT_VALID_TITLES = setOf("r b", "rb")

    fun selectSeeds(
        candidates: List<PlaylistSeedCandidate>,
        maxSeeds: Int,
        excludedSeedIds: Set<String> = emptySet(),
        random: Random = Random.Default,
    ): List<PlaylistSeedCandidate> {
        if (maxSeeds <= 0) return emptyList()
        val eligible =
            candidates
                .filter { !it.isLocal && !it.isPodcast && it.isYouTubeId }
                .distinctBy { it.id }
        if (eligible.isEmpty()) return emptyList()

        val (fresh, stale) = eligible.partition { it.id !in excludedSeedIds }

        val selected = mutableListOf<PlaylistSeedCandidate>()
        val selectedIds = HashSet<String>()
        val selectedArtists = HashSet<String>()

        fun tryAdd(
            candidate: PlaylistSeedCandidate,
            enforceUniqueArtist: Boolean,
        ): Boolean {
            if (selected.size >= maxSeeds) return false
            if (candidate.id in selectedIds) return false
            if (enforceUniqueArtist && candidate.artistKey.isNotEmpty() && candidate.artistKey in selectedArtists) {
                return false
            }
            selected.add(candidate)
            selectedIds.add(candidate.id)
            if (candidate.artistKey.isNotEmpty()) selectedArtists.add(candidate.artistKey)
            return true
        }

        fun pickFromPool(pool: List<PlaylistSeedCandidate>) {
            if (pool.isEmpty() || selected.size >= maxSeeds) return

            val topPlayedLimit = minOf(2, maxSeeds - selected.size)
            var topPlayedAdded = 0
            for (candidate in pool.filter { it.totalPlayTime > 0L }.sortedByDescending { it.totalPlayTime }) {
                if (topPlayedAdded >= topPlayedLimit) break
                if (tryAdd(candidate, enforceUniqueArtist = true)) topPlayedAdded++
            }

            val recentLimit = minOf(2, maxSeeds - selected.size)
            var recentAdded = 0
            for (candidate in pool.sortedByDescending { it.position }) {
                if (recentAdded >= recentLimit) break
                if (tryAdd(candidate, enforceUniqueArtist = true)) recentAdded++
            }

            val shuffledRemaining = pool.filterNot { it.id in selectedIds }.shuffled(random)
            for (candidate in shuffledRemaining) {
                if (selected.size >= maxSeeds) break
                tryAdd(candidate, enforceUniqueArtist = true)
            }

            if (selected.size < maxSeeds) {
                for (candidate in shuffledRemaining) {
                    if (selected.size >= maxSeeds) break
                    tryAdd(candidate, enforceUniqueArtist = false)
                }
            }
        }

        // Prioritize unused seeds first so Refresh rotates cleanly across the entire playlist
        pickFromPool(fresh)
        if (selected.size < maxSeeds) {
            pickFromPool(stale)
        }

        return selected
    }

    fun selectLocalSearchQueries(
        candidates: List<PlaylistSeedCandidate>,
        maxQueries: Int = 3,
        excludedSeedIds: Set<String> = emptySet(),
        random: Random = Random.Default,
    ): List<Pair<String, String>> {
        if (maxQueries <= 0) return emptyList()
        val eligible =
            candidates
                .filter { !it.isPodcast && it.title.isNotBlank() }
                .distinctBy { it.id }
        if (eligible.isEmpty()) return emptyList()

        val (fresh, stale) = eligible.partition { it.id !in excludedSeedIds }
        val picked = mutableListOf<PlaylistSeedCandidate>()
        val pickedIds = HashSet<String>()
        val usedArtists = HashSet<String>()

        fun pickFromPool(pool: List<PlaylistSeedCandidate>) {
            if (pool.isEmpty() || picked.size >= maxQueries) return
            val shuffled = pool.shuffled(random)
            for (candidate in shuffled) {
                if (picked.size >= maxQueries) break
                if (candidate.id in pickedIds) continue
                if (candidate.artistKey.isNotEmpty() && candidate.artistKey in usedArtists) continue
                picked.add(candidate)
                pickedIds.add(candidate.id)
                if (candidate.artistKey.isNotEmpty()) usedArtists.add(candidate.artistKey)
            }
            if (picked.size < maxQueries) {
                for (candidate in shuffled) {
                    if (picked.size >= maxQueries) break
                    if (pickedIds.add(candidate.id)) picked.add(candidate)
                }
            }
        }

        pickFromPool(fresh)
        if (picked.size < maxQueries) {
            pickFromPool(stale)
        }

        return picked.map { candidate ->
            val query =
                if (candidate.artistName.isNotBlank()) {
                    "${candidate.title.trim()} ${candidate.artistName.trim()}"
                } else {
                    candidate.title.trim()
                }
            candidate.id to query
        }
    }

    fun isMeaningfulPlaylistTitle(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return false

        val tokens =
            NON_ALNUM_REGEX
                .split(trimmed.lowercase(Locale.ROOT))
                .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false

        val meaningfulTokens = tokens.toMutableList()
        while (meaningfulTokens.isNotEmpty() && TRAILING_NUMBER_TOKEN_REGEX.matches(meaningfulTokens.last())) {
            meaningfulTokens.removeAt(meaningfulTokens.lastIndex)
            while (meaningfulTokens.isNotEmpty() && meaningfulTokens.last() in TRAILING_NUMBER_PREFIX_TOKENS) {
                meaningfulTokens.removeAt(meaningfulTokens.lastIndex)
            }
        }
        if (meaningfulTokens.isEmpty()) return false

        val normalized = meaningfulTokens.joinToString(" ")
        if (normalized in GENERIC_PLAYLIST_TITLES) return false
        if (normalized in SHORT_VALID_TITLES) return true
        if (meaningfulTokens.any { DECADE_TOKEN_REGEX.matches(it) }) return true

        val hasNonLatinScript = normalized.any { ch -> ch.code > 0x024F && ch.isLetter() }
        val letterCount = normalized.count { it.isLetter() }
        if (hasNonLatinScript) {
            return letterCount >= 2
        }
        return letterCount >= 3
    }

    fun dedupSignature(
        title: String,
        primaryArtistName: String,
        fallbackId: String,
    ): String {
        val normalizedTitle =
            NON_ALNUM_REGEX
                .replace(title.lowercase(Locale.ROOT), " ")
                .trim()
        val normalizedArtist =
            NON_ALNUM_REGEX
                .replace(primaryArtistName.lowercase(Locale.ROOT), " ")
                .trim()
        return if (normalizedTitle.isNotEmpty() && normalizedArtist.isNotEmpty()) {
            "$normalizedTitle|$normalizedArtist"
        } else {
            fallbackId
        }
    }

    fun rankCandidates(
        batches: List<RecommendationBatch>,
        playlistSongIds: Set<String>,
        playlistArtistKeys: Set<String>,
        playlistSongSignatures: Set<String> = emptySet(),
        previouslyShownIds: Set<String> = emptySet(),
        maxPerArtistPerPage: Int = MAX_PER_ARTIST_PER_PAGE,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        maxPoolSize: Int = MAX_POOL_SIZE,
        titleShareCapInBoth: Int? = null,
    ): List<SongItem> {
        if (batches.isEmpty() || pageSize <= 0 || maxPoolSize <= 0) return emptyList()

        // Accumulate by normalized song signature so alt-ID versions (e.g. OMV vs ATV) share consensus
        // and prefer the highest audio quality representation.
        val bySignature = LinkedHashMap<String, CandidateAccumulator>()
        for (batch in batches) {
            batch.items.forEachIndexed { index, item ->
                if (!item.isValidSuggestion(playlistSongIds)) return@forEachIndexed
                val signature = item.dedupSignature()
                if (signature in playlistSongSignatures) return@forEachIndexed

                val score = batch.kind.baseWeight / (1.0 + index * RANK_DECAY_STEP)
                val acc = bySignature.getOrPut(signature) { CandidateAccumulator(item) }
                acc.seenIds.add(item.id)
                if (item.audioQualityRank() > acc.item.audioQualityRank()) {
                    acc.item = item
                }
                val existingSourceScore = acc.bestScoreBySource[batch.sourceKey]
                if (existingSourceScore == null || score > existingSourceScore) {
                    acc.bestScoreBySource[batch.sourceKey] = score
                }
                if (!batch.kind.isTitleSourced) {
                    acc.hasContentSource = true
                }
            }
        }
        if (bySignature.isEmpty()) return emptyList()

        val remaining =
            bySignature.values
                .map { acc ->
                    val baseTotal = acc.bestScoreBySource.values.sum()
                    val consensus = (acc.bestScoreBySource.size - 1).coerceAtLeast(0) * CONSENSUS_BONUS
                    val artistAffinity =
                        if (acc.item.artists.any { it.artistKey() in playlistArtistKeys }) {
                            ARTIST_AFFINITY_BONUS
                        } else {
                            0.0
                        }
                    var finalScore = baseTotal + consensus + artistAffinity
                    if (acc.seenIds.any { it in previouslyShownIds }) {
                        finalScore *= PREVIOUSLY_SHOWN_MULTIPLIER
                    }
                    ScoredCandidate(
                        item = acc.item,
                        score = finalScore,
                        primaryArtistKey = acc.item.primaryArtistKey(),
                        isPurelyTitleSourced = !acc.hasContentSource,
                    )
                }.sortedWith(
                    compareByDescending<ScoredCandidate> { it.score }
                        .thenByDescending { it.item.audioQualityRank() },
                ).toMutableList()

        val hasAnyContentCandidates = remaining.any { !it.isPurelyTitleSourced }
        val effectiveTitleCap = if (hasAnyContentCandidates) titleShareCapInBoth else null

        val result = ArrayList<SongItem>(minOf(maxPoolSize, remaining.size))
        while (result.size < maxPoolSize && remaining.isNotEmpty()) {
            val slotsInPage = minOf(pageSize, maxPoolSize - result.size)
            val artistCounts = HashMap<String, Int>()
            var titleCountInPage = 0
            var pickedInPage = 0

            fun fillPage(
                enforceArtistCap: Boolean,
                enforceTitleCap: Boolean,
            ) {
                val iterator = remaining.iterator()
                while (iterator.hasNext() && pickedInPage < slotsInPage) {
                    val candidate = iterator.next()
                    if (
                        enforceTitleCap &&
                        effectiveTitleCap != null &&
                        candidate.isPurelyTitleSourced &&
                        titleCountInPage >= effectiveTitleCap
                    ) {
                        continue
                    }
                    if (
                        enforceArtistCap &&
                        maxPerArtistPerPage > 0 &&
                        candidate.primaryArtistKey.isNotEmpty() &&
                        (artistCounts[candidate.primaryArtistKey] ?: 0) >= maxPerArtistPerPage
                    ) {
                        continue
                    }
                    iterator.remove()
                    result.add(candidate.item)
                    pickedInPage++
                    if (candidate.primaryArtistKey.isNotEmpty()) {
                        artistCounts[candidate.primaryArtistKey] = (artistCounts[candidate.primaryArtistKey] ?: 0) + 1
                    }
                    if (candidate.isPurelyTitleSourced) {
                        titleCountInPage++
                    }
                }
            }

            fillPage(enforceArtistCap = true, enforceTitleCap = true)
            if (pickedInPage < slotsInPage) {
                fillPage(enforceArtistCap = false, enforceTitleCap = true)
            }
            if (pickedInPage < slotsInPage) {
                fillPage(enforceArtistCap = true, enforceTitleCap = false)
            }
            if (pickedInPage < slotsInPage) {
                fillPage(enforceArtistCap = false, enforceTitleCap = false)
            }
        }

        return result
    }

    private class CandidateAccumulator(
        var item: SongItem,
        val seenIds: MutableSet<String> = mutableSetOf(item.id),
        val bestScoreBySource: MutableMap<String, Double> = LinkedHashMap(),
        var hasContentSource: Boolean = false,
    )

    private data class ScoredCandidate(
        val item: SongItem,
        val score: Double,
        val primaryArtistKey: String,
        val isPurelyTitleSourced: Boolean,
    )

    private fun SongItem.isValidSuggestion(playlistSongIds: Set<String>): Boolean =
        id.length == YOUTUBE_VIDEO_ID_LENGTH &&
            id !in playlistSongIds &&
            !isPodcast &&
            title.isNotBlank() &&
            artists.isNotEmpty()

    private fun SongItem.audioQualityRank(): Int =
        when (
            endpoint
                ?.watchEndpointMusicSupportedConfigs
                ?.watchEndpointMusicConfig
                ?.musicVideoType
        ) {
            MUSIC_VIDEO_TYPE_ATV -> 2
            MUSIC_VIDEO_TYPE_OMV, MUSIC_VIDEO_TYPE_UGC -> 0
            else -> 1
        }

    private fun SongItem.primaryArtistKey(): String = artists.firstOrNull()?.artistKey().orEmpty()

    private fun Artist.artistKey(): String =
        id?.takeIf(String::isNotBlank) ?: name.trim().lowercase(Locale.ROOT)

    private fun SongItem.dedupSignature(): String =
        dedupSignature(
            title = title,
            primaryArtistName = artists.firstOrNull()?.name.orEmpty(),
            fallbackId = id,
        )
}
