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
 * On-device Chinese OCR + Chinese-to-Vietnamese translation.
 * Frames are sampled sparsely to keep memory and battery use bounded.
 * This stage detects/translates overlays; it does not erase pixels or render a replacement video.
 */
class ChineseOverlayTranslator(private val context: Context) {
    suspend fun scanAndTranslate(
        videoUri: Uri,
        sampleIntervalMs: Long = 1_500L,
        maxFrames: Int = 600,
        onProgress: (Int) -> Unit = {}
    ): List<OnScreenText> {
        require(sampleIntervalMs >= 500L) { "Khoảng lấy mẫu phải từ 0,5 giây trở lên." }
        val retriever = MediaMetadataRetriever()
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build()
        )
        val unique = linkedMapOf<String, OnScreenText>()
        try {
            retriever.setDataSource(context, videoUri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            require(duration > 0L) { "Không đọc được thời lượng video." }
            val frameCount = minOf(((duration + sampleIntervalMs - 1) / sampleIntervalMs).toInt(), maxFrames)
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).awaitTask()
            for (index in 0 until frameCount) {
                val timeMs = index * sampleIntervalMs
                val bitmap: Bitmap = retriever.getFrameAtTime(
                    timeMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                try {
                    val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
                    result.textBlocks.forEach { block ->
                        val text = block.text.trim()
                        if (text.any { it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF }) {
                            val key = text.replace(Regex("\\s+"), "")
                            val box = block.boundingBox
                            if (box != null) {
                                val normalizedLeft = (box.left.toFloat() / bitmap.width).coerceIn(0f, 1f)
                                val normalizedTop = (box.top.toFloat() / bitmap.height).coerceIn(0f, 1f)
                                val normalizedRight = (box.right.toFloat() / bitmap.width).coerceIn(0f, 1f)
                                val normalizedBottom = (box.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f)
                                val previous = unique[key]
                                if (previous != null) {
                                    unique[key] = previous.copy(
                                        lastTimeMs = timeMs,
                                        left = minOf(previous.left, normalizedLeft),
                                        top = minOf(previous.top, normalizedTop),
                                        right = maxOf(previous.right, normalizedRight),
                                        bottom = maxOf(previous.bottom, normalizedBottom)
                                    )
                                } else {
                                    val translated = translator.translate(text).awaitTask()
                                    unique[key] = OnScreenText(
                                        sourceText = text,
                                        vietnameseText = translated,
                                        timeMs = timeMs,
                                        lastTimeMs = timeMs,
                                        left = normalizedLeft,
                                        top = normalizedTop,
                                        right = normalizedRight,
                                        bottom = normalizedBottom
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                onProgress(((index + 1) * 100 / frameCount).coerceIn(0, 100))
            }
            return unique.values.toList()
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
