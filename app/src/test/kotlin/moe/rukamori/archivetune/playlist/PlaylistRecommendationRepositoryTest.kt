/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playlist

import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaylistRecommendationRepositoryTest {
    @Test
    fun `selectRecommendationSeeds prefers distinct artists and fills remaining slots`() {
        val candidates =
            listOf(
                RecommendationSeed(id = "vid00000001", artistKey = "a1"),
                RecommendationSeed(id = "vid00000002", artistKey = "a1"),
                RecommendationSeed(id = "vid00000003", artistKey = "a2"),
                RecommendationSeed(id = "vid00000004", artistKey = "a3"),
            )

        val picked = selectRecommendationSeeds(candidates, maxSeeds = 3, random = Random(42))

        assertEquals(3, picked.size)
        assertEquals(3, picked.map { it.artistKey }.toSet().size)
    }

    @Test
    fun `selectRecommendationSeeds prioritizes unused seeds before recycling used ones`() {
        val candidates =
            (1..5).map { i ->
                RecommendationSeed(id = "vid0000000$i", artistKey = "artist$i")
            }

        val first = selectRecommendationSeeds(candidates, maxSeeds = 3, random = Random(1))
        val firstIds = first.map { it.id }.toSet()
        val remainingFreshIds = candidates.map { it.id }.toSet() - firstIds

        val second =
            selectRecommendationSeeds(
                candidates = candidates,
                maxSeeds = 3,
                usedSeedIds = firstIds,
                random = Random(2),
            )

        assertEquals(3, second.size)
        assertTrue(second.map { it.id }.containsAll(remainingFreshIds))
    }

    @Test
    fun `mergeSuggestionLists interleaves across seeds, deduplicates, and places unseen songs first`() {
        val listA = listOf(song("a1000000001"), song("dup00000001"), song("a2000000002"))
        val listB = listOf(song("b1000000001"), song("b2000000002"), song("dup00000001"))

        val merged =
            mergeSuggestionLists(
                lists = listOf(listA, listB),
                previouslyShownIds = setOf("a1000000001"),
                maxPoolSize = 50,
            )

        assertEquals(
            listOf("b1000000001", "dup00000001", "b2000000002", "a2000000002", "a1000000001"),
            merged.map { it.id },
        )
    }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = "Song $id",
            artists = listOf(Artist(name = "Artist", id = "UC_artist")),
            thumbnail = "https://example.com/$id.jpg",
        )
}
