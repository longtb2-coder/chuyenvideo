package ai.longkaka.seller.media

/**
 * Places spoken Vietnamese captions inside a cleaned OCR region whenever that
 * region has enough usable space. The caller supplies boxes already resolved
 * for the current video frame, so captions follow the same tracked motion.
 */
internal object SpeechCaptionPlacement {
    fun chooseRestoredRegion(regions: List<RenderBox>): RenderBox? =
        regions.asSequence()
            .filter { box ->
                box.left >= 0f && box.top >= 0f &&
                    box.width >= 0.22f && box.height >= 0.075f &&
                    box.left + box.width <= 1.001f &&
                    box.top + box.height <= 1.001f
            }
            .maxByOrNull { it.width * it.height }
}
