package ai.longkaka.seller.processing

import ai.longkaka.seller.translation.OnScreenText
import ai.longkaka.seller.translation.TextRegionObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRemovalPlannerTest {
    @Test
    fun onlyReturnsSelectedTracksVisibleAtRequestedTime() {
        val tracks = listOf(
            OnScreenText("字A", "A", 0L, 2_000L, 0f, 0f, 0f, 0f, listOf(
                TextRegionObservation(0L, 0.1f, 0.1f, 0.2f, 0.2f),
                TextRegionObservation(2_000L, 0.3f, 0.1f, 0.4f, 0.2f)
            )),
            OnScreenText("字B", "B", 3_000L, 4_000L, 0f, 0f, 0f, 0f, listOf(
                TextRegionObservation(3_000L, 0.5f, 0.5f, 0.6f, 0.6f),
                TextRegionObservation(4_000L, 0.6f, 0.5f, 0.7f, 0.6f)
            ))
        )

        val regions = TextRemovalPlanner.regionsAt(tracks, setOf(0, 1), 1_000L)
        assertEquals(1, regions.size)
        assertEquals(0.2f, regions.single().left, 0.0001f)
        assertEquals(0.3f, regions.single().right, 0.0001f)
    }

    @Test
    fun emptySelectionProducesNoRemovalRegions() {
        assertTrue(TextRemovalPlanner.regionsAt(emptyList(), emptySet(), 1_000L).isEmpty())
    }
}
