package ai.longkaka.seller.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import ai.longkaka.seller.translation.OnScreenText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RenderBox(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Real MP4 renderer. It burns the selected cover and translated text into every matching
 * frame and preserves the source audio track. The output is published only after validation.
 */
@OptIn(UnstableApi::class)
class VideoExporter(private val context: Context) {
    suspend fun export(
        source: Uri,
        overlays: List<OnScreenText>,
        translations: List<RenderBox>,
        covers: List<RenderBox>,
        coverEnabled: List<Boolean>,
        coverColor: Int,
        coverAlpha: Float,
        textSizeSp: Float,
        editedTexts: Map<Int, String>,
        narrationFile: File? = null,
        onProgress: (Int) -> Unit = {}
    ): Uri = withContext(Dispatchers.Main.immediate) {
        require(source != Uri.EMPTY) { "Video đầu vào không hợp lệ." }
        require(translations.size == overlays.size && covers.size == overlays.size && coverEnabled.size == overlays.size) {
            "Dữ liệu chỉnh sửa không khớp số đoạn chữ. Hãy quét lại video."
        }
        val temp = File(context.cacheDir, "seller_export_${System.currentTimeMillis()}.mp4")
        val overlay = object : CanvasOverlay(true) {
            private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = coverColor }
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }
            private val textBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(105, 0, 0, 0) }

            override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
                val t = presentationTimeUs / 1000L
                overlays.forEachIndexed { index, item ->
                    if (t < item.timeMs || t > item.lastTimeMs) return@forEachIndexed
                    val c = covers[index]
                    if (coverEnabled.getOrElse(index) { false }) {
                        coverPaint.color = coverColor
                        coverPaint.alpha = (coverAlpha.coerceIn(0f, 1f) * 255).toInt()
                        canvas.drawRoundRect(
                            RectF(c.left * canvas.width, c.top * canvas.height,
                                (c.left + c.width) * canvas.width, (c.top + c.height) * canvas.height),
                            5f, 5f, coverPaint
                        )
                    }
                    val b = translations[index]
                    val left = b.left * canvas.width
                    val top = b.top * canvas.height
                    val right = (b.left + b.width) * canvas.width
                    val bottom = (b.top + b.height) * canvas.height
                    val text = editedTexts[index]?.takeIf { it.isNotBlank() } ?: item.vietnameseText
                    textPaint.textSize = (textSizeSp * canvas.width / 360f).coerceIn(18f, 90f)
                    val maxWidth = (right - left).coerceAtLeast(40f) - 12f
                    val lines = mutableListOf<String>()
                    var current = StringBuilder()
                    text.split(Regex("\\s+")).forEach { word ->
                        val candidate = if (current.isEmpty()) word else "$current $word"
                        if (textPaint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                            lines += current.toString()
                            current = StringBuilder(word)
                        } else current = StringBuilder(candidate)
                    }
                    if (current.isNotEmpty()) lines += current.toString()
                    val lineHeight = textPaint.textSize * 1.18f
                    val visibleLines = lines.take(((bottom - top) / lineHeight).toInt().coerceAtLeast(1))
                    val blockHeight = visibleLines.size * lineHeight
                    val centerX = (left + right) / 2f
                    val firstBaseline = (top + (bottom - top - blockHeight) / 2f + textPaint.textSize)
                        .coerceIn(top + textPaint.textSize, bottom)
                    visibleLines.forEachIndexed { line, value ->
                        val y = firstBaseline + line * lineHeight
                        canvas.drawText(value, centerX, y, textBg)
                        canvas.drawText(value, centerX, y, textPaint)
                    }
                }
            }
        }
        val effect = OverlayEffect(listOf(overlay))
        val sourceItem = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(Effects(emptyList(), listOf(effect)))
            .build()
        val videoSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
            .addItem(sourceItem)
            .build()
        val composition = if (narrationFile != null && narrationFile.exists() && narrationFile.length() > 44L) {
            val durationRetriever = MediaMetadataRetriever()
            val durationMs = try {
                durationRetriever.setDataSource(context, source)
                durationRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { durationRetriever.release() }
            require(durationMs > 0L) { "Không đọc được thời lượng video để ghép giọng đọc." }
            val narrationItem = MediaItem.Builder()
                .setUri(Uri.fromFile(narrationFile))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(durationMs).build())
                .build()
            val narrationEdited = EditedMediaItem.Builder(narrationItem).setRemoveVideo(true).build()
            val narrationSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                .addItem(narrationEdited)
                .build()
            Composition.Builder(listOf(videoSequence, narrationSequence)).build()
        } else {
            Composition.Builder(listOf(videoSequence)).build()
        }
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: androidx.media3.transformer.Composition, result: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            result: ExportResult,
                            exception: ExportException
                        ) {
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        }
                    }).build()
                continuation.invokeOnCancellation { runCatching { transformer.cancel() } }
                transformer.start(composition, temp.absolutePath)
                // Transformer reports completion asynchronously; progress is polled separately
                // by the app's foreground export controller in the next layer.
            }
            check(temp.exists() && temp.length() > 1024L) { "Bộ dựng không tạo được tệp MP4 hợp lệ." }
            onProgress(95)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "LONGKAKA_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LONGKAKA SELLER AI")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val destination = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Không tạo được tệp trong thư mục Movies.")
            try {
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Không thể ghi video đầu ra.")
                if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.update(destination, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
                onProgress(100)
                destination
            } catch (error: Throwable) {
                context.contentResolver.delete(destination, null, null)
                throw error
            }
        } finally {
            temp.delete()
        }
    }
}
