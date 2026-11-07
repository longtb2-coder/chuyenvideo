package ai.longkaka.seller.media

/**
 * Places spoken Vietnamese captions inside a cleaned OCR region whenever that
 * region has enough usable space. The caller supplies boxes already resolved
 * for the current video frame, so captions follow the same tracked motion.
 */
internal object SpeechCaptionPlacement {
    private fun isReadableRegion(box: RenderBox): Boolean =
        box.left >= 0f && box.top >= 0f &&
            box.width >= 0.22f && box.height >= 0.075f &&
            box.left + box.width <= 1.001f &&
            box.top + box.height <= 1.001f

    /**
     * Respect the region explicitly selected in the editor when it is currently active.
     * Otherwise choose the largest usable restored region as a safe fallback.
     */
    fun chooseRestoredRegion(
        regions: List<RenderBox>,
        preferredRegion: RenderBox? = null
    ): RenderBox? {
        if (preferredRegion != null && isReadableRegion(preferredRegion)) return preferredRegion
        return regions.asSequence()
            .filter(::isReadableRegion)
            .maxByOrNull { it.width * it.height }
    }
}
