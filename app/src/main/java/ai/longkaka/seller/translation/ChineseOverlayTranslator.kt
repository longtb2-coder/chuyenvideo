package ai.longkaka.seller.translation

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot

data class OnScreenText(
    val sourceText: String,
    val vietnameseText: String,
    val timeMs: Long,
    val lastTimeMs: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Samples video frames, recognizes Chinese text and groups observations into separate
 * time/position tracks. Identical text in two different screen locations is not merged.
 * This stage does not itself erase pixels or render a replacement video.
 */
class ChineseOverlayTranslator(private val context: Context) {
    private data class Track(
        val normalizedText: String,
        var displayText: String,
        var firstTimeMs: Long,
        var lastTimeMs: Long,
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    suspend fun scanAndTranslate(
        videoUri: Uri,
        sampleIntervalMs: Long = 1_500L,
        maxFrames: Int = 600,
        onProgress: (Int) -> Unit = {}
    ): List<OnScreenText> {
        require(sampleIntervalMs >= 500L) { "Khoảng lấy mẫu phải từ 0,5 giây trở lên." }
        require(maxFrames > 0) { "Số khung hình quét phải lớn hơn 0." }

        val retriever = MediaMetadataRetriever()
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build()
        )
        val tracks = mutableListOf<Track>()
        try {
            retriever.setDataSource(context, videoUri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            require(duration > 0L) { "Không đọc được thời lượng video." }
            val frameCount = minOf(
                ((duration + sampleIntervalMs - 1) / sampleIntervalMs).toInt().coerceAtLeast(1),
                maxFrames
            )
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()

            for (index in 0 until frameCount) {
                val timeMs = index * sampleIntervalMs
                val bitmap: Bitmap = retriever.getFrameAtTime(
                    timeMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: run {
                    onProgress(((index + 1) * 100 / frameCount).coerceIn(0, 100))
                    continue
                }
                try {
                    val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
                    result.textBlocks.forEach { block ->
                        val text = block.text.trim()
                        val containsChinese = text.any {
                            it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF
                        }
                        val box = block.boundingBox
                        if (containsChinese && box != null) {
                            val left = (box.left.toFloat() / bitmap.width).coerceIn(0f, 1f)
                            val top = (box.top.toFloat() / bitmap.height).coerceIn(0f, 1f)
                            val right = (box.right.toFloat() / bitmap.width).coerceIn(left, 1f)
                            val bottom = (box.bottom.toFloat() / bitmap.height).coerceIn(top, 1f)
                            val normalized = text.replace(Regex("\\s+"), "")
                            val cx = (left + right) / 2f
                            val cy = (top + bottom) / 2f

                            // Match only a recent observation at approximately the same location.
                            // A new scene or a second copy elsewhere becomes a separate track.
                            val candidate = tracks
                                .filter { it.normalizedText == normalized }
                                .filter { timeMs - it.lastTimeMs <= sampleIntervalMs * 3 }
                                .filter { hypot((it.centerX - cx).toDouble(), (it.centerY - cy).toDouble()) <= 0.13 }
                                .minByOrNull {
                                    hypot((it.centerX - cx).toDouble(), (it.centerY - cy).toDouble())
                                }

                            if (candidate == null) {
                                tracks += Track(
                                    normalizedText = normalized,
                                    displayText = text,
                                    firstTimeMs = timeMs,
                                    lastTimeMs = timeMs,
                                    left = left,
                                    top = top,
                                    right = right,
                                    bottom = bottom
                                )
                            } else {
                                candidate.lastTimeMs = timeMs
                                candidate.left = minOf(candidate.left, left)
                                candidate.top = minOf(candidate.top, top)
                                candidate.right = maxOf(candidate.right, right)
                                candidate.bottom = maxOf(candidate.bottom, bottom)
                                if (text.length > candidate.displayText.length) candidate.displayText = text
                            }
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                onProgress(((index + 1) * 100 / frameCount).coerceIn(0, 100))
            }

            return tracks.map { track ->
                val translated = translator.translate(track.displayText).awaitTask()
                OnScreenText(
                    sourceText = track.displayText,
                    vietnameseText = translated,
                    timeMs = track.firstTimeMs,
                    lastTimeMs = track.lastTimeMs,
                    left = track.left,
                    top = track.top,
                    right = track.right,
                    bottom = track.bottom
                )
            }
        } finally {
            recognizer.close()
            translator.close()
            retriever.release()
        }
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        continuation.cancel()
    }
}
