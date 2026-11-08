package ai.longkaka.seller.media

/**
 * Keeps spoken-dialogue captions independent from translated on-screen text.
 * All rectangles use normalized video coordinates (0..1).
 */
internal object SubtitleLayerLayout {
    private val bottomLane = RenderBox(0.04f, 0.79f, 0.92f, 0.17f)
    private val topLane = RenderBox(0.04f, 0.04f, 0.92f, 0.17f)

    fun dialogueCaptionBox(occupied: List<RenderBox>): RenderBox {
        val bottomCost = collisionArea(bottomLane, occupied)
        val topCost = collisionArea(topLane, occupied)
        return if (bottomCost <= topCost) bottomLane else topLane
    }

    private fun collisionArea(candidate: RenderBox, occupied: List<RenderBox>): Float =
        occupied.sumOf { box ->
            val width = (minOf(candidate.left + candidate.width, box.left + box.width) -
                maxOf(candidate.left, box.left)).coerceAtLeast(0f)
            val height = (minOf(candidate.top + candidate.height, box.top + box.height) -
                maxOf(candidate.top, box.top)).coerceAtLeast(0f)
            (width * height).toDouble()
        }.toFloat()
}
