package ai.longkaka.seller.translation

/**
 * Returns a motion-compensated OCR box only while the track is observed.
 *
 * We deliberately do not extrapolate before the first or after the last observation:
 * doing so can erase unrelated content after an overlay disappears.
 */
fun OnScreenText.boundsAt(timeMs: Long, maxGapMs: Long = 3_000L): TextRegionObservation? {
    require(maxGapMs >= 0L) { "maxGapMs cannot be negative." }
    val points = observations
        .asSequence()
        .filter { it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() }
        .sortedBy { it.timeMs }
        .distinctBy { it.timeMs }
        .toList()

    if (points.isEmpty() || timeMs < points.first().timeMs || timeMs > points.last().timeMs) return null
    points.firstOrNull { it.timeMs == timeMs }?.let { return it }

    val afterIndex = points.indexOfFirst { it.timeMs > timeMs }
    if (afterIndex <= 0) return null
    val before = points[afterIndex - 1]
    val after = points[afterIndex]
    val span = after.timeMs - before.timeMs
    if (span <= 0L || span > maxGapMs) return null

    val ratio = (timeMs - before.timeMs).toFloat() / span.toFloat()
    fun mix(a: Float, b: Float): Float = (a + (b - a) * ratio).coerceIn(0f, 1f)
    val left = mix(before.left, after.left)
    val top = mix(before.top, after.top)
    val right = mix(before.right, after.right).coerceAtLeast(left)
    val bottom = mix(before.bottom, after.bottom).coerceAtLeast(top)

    return TextRegionObservation(timeMs, left, top, right, bottom)
}
