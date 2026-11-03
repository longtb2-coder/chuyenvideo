package ai.longkaka.seller.translation

/** Interpolates OCR box motion between neighboring sampled observations. */
fun OnScreenText.boundsAt(timeMs: Long): TextRegionObservation? {
    val points = observations.sortedBy { it.timeMs }
    if (points.isEmpty() || timeMs < points.first().timeMs || timeMs > points.last().timeMs) return null
    val exact = points.firstOrNull { it.timeMs == timeMs }
    if (exact != null) return exact
    val before = points.lastOrNull { it.timeMs < timeMs } ?: return null
    val after = points.firstOrNull { it.timeMs > timeMs } ?: return null
    val span = after.timeMs - before.timeMs
    if (span <= 0L) return before
    val ratio = (timeMs - before.timeMs).toFloat() / span.toFloat()
    fun mix(a: Float, b: Float) = a + (b - a) * ratio
    return TextRegionObservation(
        timeMs = timeMs,
        left = mix(before.left, after.left),
        top = mix(before.top, after.top),
        right = mix(before.right, after.right),
        bottom = mix(before.bottom, after.bottom)
    )
}
