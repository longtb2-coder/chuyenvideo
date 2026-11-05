package ai.longkaka.seller.processing

import ai.longkaka.seller.translation.OnScreenText
import ai.longkaka.seller.translation.boundsAt

/** Builds the exact removal mask regions for a video timestamp from selected OCR tracks. */
object TextRemovalPlanner {
    fun regionsAt(
        tracks: List<OnScreenText>,
        selectedIndices: Set<Int>,
        timeMs: Long
    ): List<TextRegionInpainting.Region> = selectedIndices
        .sorted()
        .mapNotNull { tracks.getOrNull(it)?.boundsAt(timeMs) }
        .map { box -> TextRegionInpainting.Region(box.left, box.top, box.right, box.bottom) }
}
