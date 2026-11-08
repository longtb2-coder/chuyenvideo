package ai.longkaka.seller.media

/**
 * Pure helpers for memory-bounded temporal text restoration and residual verification.
 * Kept independent of Android/OpenCV so the allocation and matching rules can be
 * unit-tested on the JVM before building the APK.
 */
internal object TextRestorationQuality {
    const val MAX_TOTAL_PATCHES = 256
    const val TARGET_PATCH_GAP_MS = 350L
    const val MAX_PATCH_DISTANCE_MS = 300L

    /**
     * Allocates a bounded number of restoration samples across OCR tracks.
     * Tracks are weighted by the number of samples needed to cover their visible
     * duration, rather than dividing the budget equally between short and long tracks.
     */
    fun allocateSampleCounts(
        observationCounts: List<Int>,
        durationsMs: List<Long>,
        maxTotalPatches: Int = MAX_TOTAL_PATCHES,
        targetGapMs: Long = TARGET_PATCH_GAP_MS
    ): List<Int> {
        require(observationCounts.size == durationsMs.size) {
            "Số lượng track và thời lượng không khớp."
        }
        require(maxTotalPatches >= 0) { "Ngân sách patch không hợp lệ." }
        require(targetGapMs > 0L) { "Khoảng lấy mẫu phải lớn hơn 0." }
        if (observationCounts.isEmpty()) return emptyList()

        val desired = observationCounts.mapIndexed { index, rawCount ->
            val count = rawCount.coerceAtLeast(0)
            if (count == 0) {
                0
            } else {
                val duration = durationsMs[index].coerceAtLeast(0L)
                val byDuration = (duration + targetGapMs - 1L) / targetGapMs + 1L
                minOf(count.toLong(), byDuration).toInt().coerceAtLeast(1)
            }
        }
        val desiredTotal = desired.sum()
        if (desiredTotal <= maxTotalPatches) return desired

        val activeIndices = desired.indices.filter { desired[it] > 0 }
        require(activeIndices.size <= maxTotalPatches) {
            "Có quá nhiều vùng chữ riêng biệt để phục hồi an toàn cùng lúc. Hãy giảm số vùng OCR cần xử lý."
        }

        // Preserve at least two samples per track when possible. For very many short
        // tracks, preserve one sample per track and distribute the remainder by duration.
        val minimumPerTrack = if (activeIndices.size * 2 <= maxTotalPatches) 2 else 1
        val allocation = desired.map { count ->
            if (count == 0) 0 else minOf(count, minimumPerTrack)
        }.toMutableList()
        val remaining = maxTotalPatches - allocation.sum()
        val weights = desired.indices.map { (desired[it] - allocation[it]).coerceAtLeast(0) }
        val totalWeight = weights.sum()
        if (remaining <= 0 || totalWeight <= 0) return allocation

        val shares = weights.map { weight -> remaining.toDouble() * weight / totalWeight }
        shares.forEachIndexed { index, share ->
            allocation[index] += share.toInt().coerceAtMost(weights[index])
        }

        var unallocated = maxTotalPatches - allocation.sum()
        val remainderOrder = shares.indices.sortedByDescending { shares[it] - shares[it].toInt() }
        for (index in remainderOrder) {
            if (unallocated <= 0) break
            if (allocation[index] < desired[index]) {
                allocation[index]++
                unallocated--
            }
        }
        check(unallocated == 0) { "Không thể phân bổ đầy đủ ngân sách phục hồi." }
        return allocation
    }

    /**
     * Matches residual OCR against the original text for that track. A one-character
     * detection inside a longer phrase is too ambiguous to fail export by itself;
     * two-or-more-character fragments and close OCR variants are still detected.
     */
    fun matchesOriginal(source: String, detected: String): Boolean {
        val expected = source.filter { it.isLetterOrDigit() }
        val actual = detected.filter { it.isLetterOrDigit() }
        if (expected.isBlank() || actual.isBlank()) return false
        if (expected == actual) return true
        if (actual.length == 1 && expected.length > 2) return false
        if (expected.contains(actual) || actual.contains(expected)) return true

        var previous = IntArray(actual.length + 1) { it }
        for (i in expected.indices) {
            val current = IntArray(actual.length + 1)
            current[0] = i + 1
            for (j in actual.indices) {
                val cost = if (expected[i] == actual[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            previous = current
        }
        val similarity = 1.0 - previous[actual.length].toDouble() / maxOf(expected.length, actual.length)
        return similarity >= 0.60
    }
}
