package ai.longkaka.seller.translation

/**
 * Returns a motion-compensated OCR box between observations, with a short edge tolerance
 * to keep the cover stable between sampled frames. It never extends beyond that tolerance.
 */
fun OnScreenText.boundsAt(
    timeMs: Long,
    maxGapMs: Long = 2_000L,
    edgeToleranceMs: Long = 500L
): TextRegionObservation? {
    require(maxGapMs >= 0L && edgeToleranceMs >= 0L) { "Time tolerances cannot be negative." }
    val points = observations
        .asSequence()
        .filter { it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() }
        .sortedBy { it.timeMs }
        .distinctBy { it.timeMs }
        .toList()

    if (points.isEmpty()) return null
    if (timeMs < points.first().timeMs) {
        return points.first().takeIf { points.first().timeMs - timeMs <= edgeToleranceMs }
            ?.let { it.copy(timeMs = timeMs) }
    }
    if (timeMs > points.last().timeMs) {
        return points.last().takeIf { timeMs - points.last().timeMs <= edgeToleranceMs }
            ?.let { it.copy(timeMs = timeMs) }
    }
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
