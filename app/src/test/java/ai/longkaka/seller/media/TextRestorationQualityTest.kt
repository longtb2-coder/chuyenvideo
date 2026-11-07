package ai.longkaka.seller.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRestorationQualityTest {
    @Test
    fun allocatesMoreSamplesToLongLivedTextTracks() {
        val counts = TextRestorationQuality.allocateSampleCounts(
            observationCounts = listOf(500, 40, 40),
            durationsMs = listOf(120_000L, 8_000L, 8_000L),
            maxTotalPatches = 40
        )

        assertEquals(40, counts.sum())
        assertTrue(counts[0] > counts[1])
        assertTrue(counts[0] > counts[2])
        assertTrue(counts.all { it >= 1 })
    }

    @Test
    fun leavesShortTracksAtTheirRequiredSampleCountWhenBudgetAllows() {
        val counts = TextRestorationQuality.allocateSampleCounts(
            observationCounts = listOf(12, 8, 1),
            durationsMs = listOf(3_000L, 2_000L, 0L),
            maxTotalPatches = 32
        )

        assertEquals(listOf(5, 4, 1), counts)
    }

    @Test
    fun limitsTotalPatchCountWithoutStarvingAnyTrack() {
        val counts = TextRestorationQuality.allocateSampleCounts(
            observationCounts = listOf(200, 200, 200, 200),
            durationsMs = listOf(60_000L, 60_000L, 60_000L, 60_000L),
            maxTotalPatches = 64
        )

        assertEquals(64, counts.sum())
        assertTrue(counts.all { it >= 2 })
    }

    @Test
    fun rejectsAnImpossibleBudgetRatherThanSilentlySkippingTextTracks() {
        val error = runCatching {
            TextRestorationQuality.allocateSampleCounts(
                observationCounts = listOf(1, 1, 1),
                durationsMs = listOf(0L, 0L, 0L),
                maxTotalPatches = 2
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun detectsExactAndPartialRemnantsOfTheOriginalChineseText() {
        assertTrue(TextRestorationQuality.matchesOriginal("奶香松饼", "奶香松饼"))
        assertTrue(TextRestorationQuality.matchesOriginal("奶香松饼", "香松饼"))
        assertTrue(TextRestorationQuality.matchesOriginal("限时抢购", "限时抢购"))
    }

    @Test
    fun doesNotFlagUnrelatedChineseOrAnAmbiguousSingleGlyph() {
        assertFalse(TextRestorationQuality.matchesOriginal("奶香松饼", "今天下单"))
        assertFalse(TextRestorationQuality.matchesOriginal("奶香松饼", "奶"))
        assertFalse(TextRestorationQuality.matchesOriginal("", "中文"))
    }
}
