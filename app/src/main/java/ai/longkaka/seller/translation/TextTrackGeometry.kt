package ai.longkaka.seller.translation

/**
 * Resolves a motion-compensated OCR box using time-ordered observations.
 * A short edge tolerance keeps the cover stable between sampled frames without
 * extending it far beyond the last sighting of an overlay.
 *
 * Observations are appended in video-time order by ChineseOverlayTranslator.
 * Binary search keeps per-frame export lookup inexpensive on long videos.
 */
fun OnScreenText.boundsAt(
    timeMs: Long,
    maxGapMs: Long = 2_000L,
    edgeToleranceMs: Long = 500L
): TextRegionObservation? {
    require(maxGapMs >= 0L && edgeToleranceMs >= 0L) { "Time tolerances cannot be negative." }
    val points = observations
    if (points.isEmpty()) return null

    val first = points.first()
    val last = points.last()
    if (timeMs < first.timeMs) {
        return first.takeIf { first.timeMs - timeMs <= edgeToleranceMs }?.copy(timeMs = timeMs)
    }
    if (timeMs > last.timeMs) {
        return last.takeIf { timeMs - last.timeMs <= edgeToleranceMs }?.copy(timeMs = timeMs)
    }

    var low = 0
    var high = points.lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        val point = points[middle]
        if (point.timeMs == timeMs) return point
        if (point.timeMs < timeMs) low = middle + 1 else high = middle - 1
    }

    val afterIndex = low
    if (afterIndex <= 0 || afterIndex >= points.size) return null
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
