package ai.longkaka.seller.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import ai.longkaka.seller.translation.OnScreenText
import ai.longkaka.seller.translation.boundsAt
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

/**
 * Re-scans the restored intermediate before subtitle rendering.
 * Only Chinese OCR that overlaps a region originally selected for removal is treated
 * as a failure; unrelated Chinese text elsewhere in the scene is left untouched.
 */
internal object VideoTextRemovalVerifier {
    suspend fun requireNoResidualChinese(
        context: Context,
        cleanedVideo: Uri,
        originalTracks: List<OnScreenText>
    ) = withContext(Dispatchers.IO) {
        if (originalTracks.isEmpty()) return@withContext
        val retriever = MediaMetadataRetriever()
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            retriever.setDataSource(context, cleanedVideo)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("Không đọc được thời lượng video sau khi xóa chữ.")
            check(durationMs > 0L) { "Video sau khi xóa chữ không hợp lệ." }

            // Cover the full duration while bounding the scan to 3,600 frames.
            val intervalMs = maxOf(250L, ceil(durationMs / 3_600.0).toLong())
            val frameCount = ceil(durationMs.toDouble() / intervalMs).toInt().coerceAtLeast(1)
            for (index in 0 until frameCount) {
                val timeMs = index * intervalMs
                val bitmap = retriever.getFrameAtTime(
                    timeMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                try {
                    val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitResult()
                    val residual = result.textBlocks
                        .flatMap { it.lines }
                        .firstOrNull { line ->
                            val text = line.text.trim()
                            val rect = line.boundingBox
                            if (rect == null || !containsChinese(text)) {
                                false
                            } else {
                                val ocrLeft = rect.left.toFloat() / bitmap.width
                                val ocrTop = rect.top.toFloat() / bitmap.height
                                val ocrRight = rect.right.toFloat() / bitmap.width
                                val ocrBottom = rect.bottom.toFloat() / bitmap.height
                                originalTracks.any { track ->
                                    val visible = timeMs >= (track.timeMs - 500L).coerceAtLeast(0L) &&
                                        timeMs <= track.lastTimeMs + 500L
                                    // The OCR may read a partially erased or distorted
                                    // remnant differently from the original phrase. Any Chinese
                                    // glyph still inside the selected removal track is a failure.
                                    if (!visible) false else {
                                        val target = track.boundsAt(timeMs) ?: if (track.observations.isEmpty()) {
                                            ai.longkaka.seller.translation.TextRegionObservation(
                                                timeMs, track.left, track.top, track.right, track.bottom
                                            )
                                        } else null
                                        if (target == null) false else {
                                            val intersectionWidth = (minOf(ocrRight, target.right) -
                                                maxOf(ocrLeft, target.left)).coerceAtLeast(0f)
                                            val intersectionHeight = (minOf(ocrBottom, target.bottom) -
                                                maxOf(ocrTop, target.top)).coerceAtLeast(0f)
                                            val intersection = intersectionWidth * intersectionHeight
                                            val smallerArea = minOf(
                                                (ocrRight - ocrLeft).coerceAtLeast(0f) *
                                                    (ocrBottom - ocrTop).coerceAtLeast(0f),
                                                (target.right - target.left).coerceAtLeast(0f) *
                                                    (target.bottom - target.top).coerceAtLeast(0f)
                                            )
                                            smallerArea > 0f && intersection / smallerArea >= 0.15f
                                        }
                                    }
                                }
                            }
                        }
                    if (residual != null) {
                        error(
                            "Kiểm tra sau khi xóa phát hiện chữ Trung còn sót: ${residual.text.trim()} " +
                                "tại khoảng ${timeMs / 1000.0}s. Đã dừng, chưa chèn phụ đề để tránh che lỗi."
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            recognizer.close()
            retriever.release()
        }
    }

    private fun resemblesOriginal(source: String, detected: String): Boolean {
        val expected = source.filter { it.isLetterOrDigit() }
        val actual = detected.filter { it.isLetterOrDigit() }
        if (expected.isBlank() || actual.isBlank()) return false
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
        return similarity >= 0.30
    }

    private fun containsChinese(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            if (codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF ||
                codePoint in 0xF900..0xFAFF || codePoint in 0x20000..0x2FA1F ||
                codePoint in 0x30000..0x3134F
            ) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    private suspend fun <T> Task<T>.awaitResult(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { value ->
                if (continuation.isActive) continuation.resume(value)
            }
            addOnFailureListener { failure ->
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
            addOnCanceledListener {
                if (continuation.isActive) continuation.cancel(CancellationException("OCR verification cancelled"))
            }
        }
}
