package ai.longkaka.seller.subtitle

import ai.longkaka.seller.translation.OnScreenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VietnameseSubtitleTrackTest {
    @Test fun buildsCuesFromTranslatedOverlayTimesAndEditedText() {
        val overlay = OnScreenText("优惠", "Ưu đãi", 1_000L, 3_000L, 0.1f, 0.2f, 0.6f, 0.4f)
        val cues = VietnameseSubtitleTrack.fromOverlays(listOf(overlay), mapOf(0 to "Giảm giá hôm nay"), 10_000L)
        assertEquals(1, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_500L, cues[0].endMs)
        assertEquals("Giảm giá hôm nay", cues[0].text)
        assertEquals(0.1f, cues[0].left, 0.0001f)
    }

    @Test fun clipsCueAtVideoEndAndSkipsEmptyTranslation() {
        val overlays = listOf(
            OnScreenText("A", " ", 9_800L, 9_900L, 0f, 0f, 1f, 1f),
            OnScreenText("B", "Mua ngay", 9_800L, 9_900L, 0f, 0f, 1f, 1f))
        val cues = VietnameseSubtitleTrack.fromOverlays(overlays, videoDurationMs = 10_000L)
        assertEquals(1, cues.size)
        assertEquals(10_000L, cues.single().endMs)
    }

    @Test fun exportsSubRipTimestampsAndSanitizesLineBreaks() {
        val cue = VietnameseSubtitleCue(3_661_234L, 3_665_000L, "Mua ngay\nưu đãi", 0f, 0f, 1f, 1f)
        val srt = VietnameseSubtitleTrack.toSrt(listOf(cue))
        assertTrue(srt.contains("01:01:01,234 --> 01:01:05,000"))
        assertTrue(srt.endsWith("Mua ngay ưu đãi\n"))
    }
}
