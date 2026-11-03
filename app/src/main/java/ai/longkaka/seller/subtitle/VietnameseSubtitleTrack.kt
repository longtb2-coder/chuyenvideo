package ai.longkaka.seller.subtitle

import ai.longkaka.seller.translation.OnScreenText

data class VietnameseSubtitleCue(
    val startMs: Long, val endMs: Long, val text: String,
    val left: Float, val top: Float, val right: Float, val bottom: Float
)

/** Timeline-ready captions. Normalized bounds let the renderer keep translated text beside its source overlay. */
object VietnameseSubtitleTrack {
    fun fromOverlays(overlays: List<OnScreenText>, editedText: Map<Int, String> = emptyMap(),
        videoDurationMs: Long, minimumDurationMs: Long = 900L, tailPaddingMs: Long = 500L
    ): List<VietnameseSubtitleCue> {
        require(videoDurationMs > 0L) { "Thời lượng video không hợp lệ." }
        require(minimumDurationMs > 0L) { "Thời lượng phụ đề tối thiểu phải lớn hơn 0." }
        return overlays.mapIndexedNotNull { index, overlay ->
            val text = (editedText[index]?.takeIf { it.isNotBlank() } ?: overlay.vietnameseText)
                .trim().takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val start = overlay.timeMs.coerceIn(0L, videoDurationMs)
            val end = (overlay.lastTimeMs + tailPaddingMs)
                .coerceAtLeast(start + minimumDurationMs).coerceAtMost(videoDurationMs)
            if (end <= start) return@mapIndexedNotNull null
            VietnameseSubtitleCue(start, end, text, overlay.left.coerceIn(0f, 1f),
                overlay.top.coerceIn(0f, 1f), overlay.right.coerceIn(0f, 1f), overlay.bottom.coerceIn(0f, 1f))
        }.sortedWith(compareBy<VietnameseSubtitleCue> { it.startMs }.thenBy { it.left })
    }

    fun toSrt(cues: List<VietnameseSubtitleCue>): String =
        cues.mapIndexed { index, cue ->
            "${index + 1}\n${timestamp(cue.startMs)} --> ${timestamp(cue.endMs)}\n${cue.text.replace("\r", " ").replace("\n", " ")}"
        }.joinToString("\n\n", postfix = if (cues.isEmpty()) "" else "\n")

    private fun timestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        return "%02d:%02d:%02d,%03d".format(java.util.Locale.ROOT,
            safe / 3_600_000L, (safe / 60_000L) % 60L, (safe / 1_000L) % 60L, safe % 1_000L)
    }
}
