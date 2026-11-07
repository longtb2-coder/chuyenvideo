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
    fun ignoresRegionsTooSmallForReadableCaption() {
        val tiny = RenderBox(0.20f, 0.40f, 0.15f, 0.04f)
        assertNull(SpeechCaptionPlacement.chooseRestoredRegion(listOf(tiny)))
    }

    @Test
    fun returnsNullWhenNoRestoredRegionIsActive() {
        assertNull(SpeechCaptionPlacement.chooseRestoredRegion(emptyList()))
    }
}
