package ai.longkaka.seller.ui

import ai.longkaka.seller.translation.OnScreenText
import ai.longkaka.seller.translation.TextGlyphBox
import ai.longkaka.seller.translation.TextRegionObservation
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayScanCodecTest {
    @Test
    fun preservesTextTimingMotionSamplesAndGlyphMasks() {
        val original = listOf(
            OnScreenText(
                sourceText = "促销",
                vietnameseText = "Khuyến mãi",
                timeMs = 1_000L,
                lastTimeMs = 2_500L,
                left = 0.1f,
                top = 0.2f,
                right = 0.5f,
                bottom = 0.3f,
                observations = listOf(
                    TextRegionObservation(
                        1_000L, 0.1f, 0.2f, 0.5f, 0.3f,
                        listOf(TextGlyphBox(0.12f, 0.21f, 0.18f, 0.28f))
                    ),
                    TextRegionObservation(2_500L, 0.2f, 0.25f, 0.6f, 0.35f)
                )
            )
        )

        val restored = OverlayScanCodec.decode(OverlayScanCodec.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun malformedMetadataDoesNotCrashProjectRestore() {
        assertEquals(emptyList<OnScreenText>(), OverlayScanCodec.decode("not-json"))
    }
}
