package ai.longkaka.seller.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechCaptionPlacementTest {
    @Test
    fun placesSpeechCaptionInsideLargestRestoredTextRegion() {
        val small = RenderBox(0.08f, 0.20f, 0.28f, 0.10f)
        val large = RenderBox(0.12f, 0.72f, 0.76f, 0.16f)
        assertEquals(large, SpeechCaptionPlacement.chooseRestoredRegion(listOf(small, large)))
    }

    @Test
    fun prefersTheRegionSelectedInTheEditor() {
        val selected = RenderBox(0.10f, 0.35f, 0.32f, 0.12f)
        val larger = RenderBox(0.08f, 0.70f, 0.78f, 0.18f)
        assertEquals(selected, SpeechCaptionPlacement.chooseRestoredRegion(listOf(larger, selected), selected))
    }

    @Test
    fun fallsBackToLargestRegionWhenPreferredRegionIsTooSmall() {
        val tiny = RenderBox(0.20f, 0.40f, 0.15f, 0.04f)
        val larger = RenderBox(0.08f, 0.70f, 0.78f, 0.18f)
        assertEquals(larger, SpeechCaptionPlacement.chooseRestoredRegion(listOf(tiny, larger), tiny))
    }

    @Test
    fun ignoresRegionsTooSmallForReadableCaption() {
        val tiny = RenderBox(0.20f, 0.40f, 0.15f, 0.04f)
        assertNull(SpeechCaptionPlacement.chooseRestoredRegion(listOf(tiny)))
    }

    @Test
    fun prefersNearestReadableRegionWhenSelectedRegionIsTooSmall() {
        val selectedButTooSmall = RenderBox(0.48f, 0.43f, 0.12f, 0.04f)
        val nearby = RenderBox(0.43f, 0.40f, 0.30f, 0.10f)
        val distantLarger = RenderBox(0.08f, 0.76f, 0.82f, 0.18f)
        assertEquals(
            nearby,
            SpeechCaptionPlacement.chooseRestoredRegion(
                listOf(distantLarger, nearby),
                selectedButTooSmall
            )
        )
    }

    @Test
    fun returnsNullWhenNoRestoredRegionIsActive() {
        assertNull(SpeechCaptionPlacement.chooseRestoredRegion(emptyList()))
    }
}
