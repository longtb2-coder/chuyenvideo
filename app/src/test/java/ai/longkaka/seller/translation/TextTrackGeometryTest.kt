package ai.longkaka.seller.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextTrackGeometryTest {
    @Test
    fun interpolatesMovingBoxBetweenSamples() {
        val track = OnScreenText(
            sourceText = "促销", vietnameseText = "Khuyến mãi",
            timeMs = 0L, lastTimeMs = 2_000L,
            left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f,
            observations = listOf(
                TextRegionObservation(0L, 0.1f, 0.2f, 0.3f, 0.4f),
                TextRegionObservation(2_000L, 0.3f, 0.4f, 0.5f, 0.6f)
            )
        )

        val middle = track.boundsAt(1_000L)
        assertEquals(0.2f, middle!!.left, 0.0001f)
        assertEquals(0.3f, middle.top, 0.0001f)
        assertEquals(0.4f, middle.right, 0.0001f)
        assertEquals(0.5f, middle.bottom, 0.0001f)
    }

    @Test
    fun doesNotInterpolateAcrossLongOcrGap() {
        val track = OnScreenText(
            sourceText = "促销", vietnameseText = "Khuyến mãi",
            timeMs = 0L, lastTimeMs = 5_000L,
            left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f,
            observations = listOf(
                TextRegionObservation(0L, 0.1f, 0.2f, 0.3f, 0.4f),
                TextRegionObservation(5_000L, 0.3f, 0.4f, 0.5f, 0.6f)
            )
        )
        assertNull(track.boundsAt(2_500L))
    }

    @Test
    fun returnsNullOutsideObservedTimeRange() {
        val track = OnScreenText(
            sourceText = "促销", vietnameseText = "Khuyến mãi",
            timeMs = 500L, lastTimeMs = 1_500L,
            left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f,
            observations = listOf(TextRegionObservation(500L, 0.1f, 0.2f, 0.3f, 0.4f),
                TextRegionObservation(1_500L, 0.2f, 0.3f, 0.4f, 0.5f))
        )
        assertNull(track.boundsAt(0L))
        assertNull(track.boundsAt(2_001L))
    }

    @Test
    fun doesNotExtrapolateAfterOverlayDisappears() {
        val track = OnScreenText(
            sourceText = "促销", vietnameseText = "Khuyến mãi",
            timeMs = 0L, lastTimeMs = 1_000L,
            left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f,
            observations = listOf(TextRegionObservation(0L, 0.1f, 0.2f, 0.3f, 0.4f),
                TextRegionObservation(1_000L, 0.2f, 0.3f, 0.4f, 0.5f))
        )
        assertEquals(0.2f, track.boundsAt(1_250L)!!.left, 0.0001f)
        assertNull(track.boundsAt(1_501L))
    }

    @Test
    fun clampsInterpolatedCoordinatesToFrameBounds() {
        val track = OnScreenText(
            sourceText = "促销", vietnameseText = "Khuyến mãi",
            timeMs = 0L, lastTimeMs = 1_000L,
            left = 0f, top = 0f, right = 1f, bottom = 1f,
            observations = listOf(TextRegionObservation(0L, -0.1f, 0.1f, 0.8f, 0.9f),
                TextRegionObservation(1_000L, 0.2f, 0.2f, 1.1f, 1.2f))
        )
        val middle = track.boundsAt(500L)!!
        assertEquals(0.05f, middle.left, 0.0001f)
        assertEquals(0.15f, middle.top, 0.0001f)
        assertEquals(0.95f, middle.right, 0.0001f)
        assertEquals(1.0f, middle.bottom, 0.0001f)
    }

    @Test
    fun exactObservationWinsEvenWhenNeighborGapIsLarge() {
        val track = OnScreenText(
            sourceText = "促销", vietnameseText = "Khuyến mãi",
            timeMs = 0L, lastTimeMs = 5_000L,
            left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f,
            observations = listOf(TextRegionObservation(0L, 0.1f, 0.2f, 0.3f, 0.4f),
                TextRegionObservation(5_000L, 0.3f, 0.4f, 0.5f, 0.6f))
        )
        assertEquals(0.3f, track.boundsAt(5_000L)!!.left, 0.0001f)
    }

}
