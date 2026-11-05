package ai.longkaka.seller.translation

/**
 * Resolves a motion-compensated OCR box using time-ordered observations.
 * Interpolates between samples and briefly extrapolates at the edges so moving
 * text does not snap back to its last sampled position at the end of a track.
 */
fun OnScreenText.boundsAt(
    timeMs: Long,
    maxGapMs: Long = 2_000L,
    edgeToleranceMs: Long = 500L
): TextRegionObservation? {
    require(maxGapMs >= 0L && edgeToleranceMs >= 0L) { "Time tolerances cannot be negative." }
    val points = observations
    if (points.isEmpty()) return null

    fun interpolate(a: TextRegionObservation, b: TextRegionObservation, target: Long): TextRegionObservation? {
        val span = b.timeMs - a.timeMs
        if (span <= 0L || span > maxGapMs) return null
        val ratio = (target - a.timeMs).toFloat() / span.toFloat()
        fun mix(x: Float, y: Float): Float = (x + (y - x) * ratio).coerceIn(0f, 1f)
        val left = mix(a.left, b.left)
        val top = mix(a.top, b.top)
        val right = mix(a.right, b.right).coerceAtLeast(left)
        val bottom = mix(a.bottom, b.bottom).coerceAtLeast(top)
        return TextRegionObservation(target, left, top, right, bottom)
    }

    val first = points.first()
    val last = points.last()
    if (timeMs < first.timeMs) {
        if (first.timeMs - timeMs > edgeToleranceMs) return null
        if (points.size < 2) return first.copy(timeMs = timeMs)
        return interpolate(points[0], points[1], timeMs)
            ?: first.copy(timeMs = timeMs)
    }
    if (timeMs > last.timeMs) {
        if (timeMs - last.timeMs > edgeToleranceMs) return null
        if (points.size < 2) return last.copy(timeMs = timeMs)
        return interpolate(points[points.lastIndex - 1], last, timeMs)
            ?: last.copy(timeMs = timeMs)
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
    return interpolate(points[afterIndex - 1], points[afterIndex], timeMs)
}
