package ai.longkaka.seller.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLayerLayoutTest {
    @Test
    fun prefersBottomLaneWhenThereIsNoOnScreenText() {
        assertEquals(
            RenderBox(0.04f, 0.79f, 0.92f, 0.17f),
            SubtitleLayerLayout.dialogueCaptionBox(emptyList())
        )
    }

    @Test
    fun movesDialogueCaptionToTopWhenBottomLaneIsOccupied() {
        val overlayAtBottom = RenderBox(0.12f, 0.80f, 0.76f, 0.12f)
        assertEquals(
            RenderBox(0.04f, 0.04f, 0.92f, 0.17f),
            SubtitleLayerLayout.dialogueCaptionBox(listOf(overlayAtBottom))
        )
    }

    @Test
    fun choosesLaneWithLessOverlapWhenBothArePartiallyOccupied() {
        val overlayAtBottom = RenderBox(0.10f, 0.80f, 0.80f, 0.12f)
        val overlayAtTop = RenderBox(0.10f, 0.05f, 0.80f, 0.15f)
        val chosen = SubtitleLayerLayout.dialogueCaptionBox(listOf(overlayAtBottom, overlayAtTop))
        assertEquals(RenderBox(0.04f, 0.79f, 0.92f, 0.17f), chosen)
    }
}
