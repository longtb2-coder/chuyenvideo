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
        val candidates = regions.filter(::isReadableRegion)
        if (preferredRegion == null) {
            return candidates.maxByOrNull { it.width * it.height }
        }

        // If the selected patch is too small, stay near that same text position
        // instead of jumping to a much larger but unrelated caption region.
        val preferredX = preferredRegion.left + preferredRegion.width / 2f
        val preferredY = preferredRegion.top + preferredRegion.height / 2f
        return candidates.minByOrNull { candidate ->
            val dx = candidate.left + candidate.width / 2f - preferredX
            val dy = candidate.top + candidate.height / 2f - preferredY
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            val sizeBonus = kotlin.math.sqrt(candidate.width * candidate.height).coerceAtMost(0.5f) * 0.04f
            distance - sizeBonus
        }
    }
}
