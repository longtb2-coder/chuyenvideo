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
import android.media.MediaExtractor
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.audio.GainProcessor
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
import ai.longkaka.seller.translation.boundsAt
import ai.longkaka.seller.speech.vieneu.VieNeuChunkTiming
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
        onProgress: (Int) -> Unit = {},
        blurEnabled: List<Boolean> = emptyList(),
        blurStrength: Float = 12f,
        salesSubtitleTimings: List<VieNeuChunkTiming> = emptyList(),
        salesSubtitleRegionIndex: Int = 0,
        originalAudioVolume: Float = 1f
    ): Uri = withContext(Dispatchers.Main.immediate) {
        require(source != Uri.EMPTY) { "Video đầu vào không hợp lệ." }
        require(originalAudioVolume in 0f..1f) { "Âm lượng âm thanh gốc phải từ 0 đến 100%." }
        require(translations.size == overlays.size && covers.size == overlays.size && coverEnabled.size == overlays.size) {
            "Dữ liệu chỉnh sửa không khớp số đoạn chữ. Hãy quét lại video."
        }
        val sourceProbe = MediaMetadataRetriever()
        val sourceDurationMs = try {
            sourceProbe.setDataSource(context, source)
            sourceProbe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            sourceProbe.release()
        }
        require(sourceDurationMs > 0L) { "Không đọc được thời lượng video nguồn. Hãy chọn lại video." }
        val hasNarration = narrationFile != null && narrationFile.exists() && narrationFile.length() > 44L
        require(narrationFile == null || hasNarration) { "Tệp giọng đọc không tồn tại hoặc bị lỗi. Hãy tạo lại giọng đọc trước khi xuất." }
        require(salesSubtitleTimings.isEmpty() || hasNarration) { "Có phụ đề đồng bộ giọng đọc nhưng chưa có tệp giọng đọc hợp lệ." }
        val temp = File(context.cacheDir, "seller_export_" + System.currentTimeMillis() + ".mp4")
        val blurSamples = if (blurEnabled.any { it }) withContext(Dispatchers.IO) { VideoTextBlur.prepare(context, source, overlays, covers, blurStrength) } else emptyMap()
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
                val activeSalesCaption = salesSubtitleTimings.firstOrNull { t >= it.startMs && t <= it.endMs }
                overlays.forEachIndexed { index, item ->
                    val trackVisible = t >= (item.timeMs - 500L).coerceAtLeast(0L) && t <= item.lastTimeMs + 500L
                    val captionNeedsStaticRegion = salesSubtitleTimings.isNotEmpty() && index == salesSubtitleRegionIndex && activeSalesCaption != null
                    if (!trackVisible && !captionNeedsStaticRegion) return@forEachIndexed
                    val c = covers[index]
                    val observed = if (trackVisible) item.boundsAt(t) else null
                    val defaultLeft = (item.left - 0.025f).coerceAtLeast(0f)
                    val defaultTop = (item.top - 0.025f).coerceAtLeast(0f)
                    val defaultRight = (item.right + 0.025f).coerceAtMost(1f)
                    val defaultBottom = (item.bottom + 0.025f).coerceAtMost(1f)
                    val defaultWidth = (defaultRight - defaultLeft).coerceAtLeast(0.01f)
                    val defaultHeight = (defaultBottom - defaultTop).coerceAtLeast(0.01f)
                    val motionBox = if (observed != null) {
                        val left = (observed.left - 0.025f + (c.left - defaultLeft)).coerceIn(0f, 1f)
                        val top = (observed.top - 0.025f + (c.top - defaultTop)).coerceIn(0f, 1f)
                        val scaleX = (c.width / defaultWidth).coerceIn(0.5f, 2.5f)
                        val scaleY = (c.height / defaultHeight).coerceIn(0.5f, 2.5f)
                        RenderBox(left, top,
                            ((observed.right - observed.left + 0.05f) * scaleX).coerceAtMost(1f - left),
                            ((observed.bottom - observed.top + 0.05f) * scaleY).coerceAtMost(1f - top))
                    } else if (item.observations.isEmpty() || captionNeedsStaticRegion) c else return@forEachIndexed
                    val blurThisOverlay = blurEnabled.getOrElse(index) { false }
                    // Layer 1: blur the original pixels. Keep drawing below so the Vietnamese
                    // subtitle is composited on top of this blurred region in the same frame.
                    if (blurThisOverlay && (trackVisible || captionNeedsStaticRegion)) {
                        val patch = blurSamples[index]?.minByOrNull { kotlin.math.abs(it.timeMs - t) }
                        if (patch != null) {
                            // Blur the entire selected replacement area, then render the
                            // Vietnamese subtitle into that exact moving rectangle below.
                            val destination = RectF(
                                motionBox.left * canvas.width,
                                motionBox.top * canvas.height,
                                (motionBox.left + motionBox.width) * canvas.width,
                                (motionBox.top + motionBox.height) * canvas.height
                            )
                            canvas.drawBitmap(patch.bitmap, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
                        }
                    }
                    // A blur-mode subtitle sits directly on the blurred area; do not add
                    // the opaque cover panel on top of it.
                    if (!blurThisOverlay && coverEnabled.getOrElse(index) { false }) {
                        coverPaint.color = coverColor
                        coverPaint.alpha = (coverAlpha.coerceIn(0f, 1f) * 255).toInt()
                        canvas.drawRoundRect(
                            RectF(motionBox.left * canvas.width, motionBox.top * canvas.height,
                                (motionBox.left + motionBox.width) * canvas.width, (motionBox.top + motionBox.height) * canvas.height),
                            5f, 5f, coverPaint
                        )
                    }
                    // The cover is the actual subtitle panel: burn the Vietnamese caption
                    // inside that same rectangle so it replaces (not sits beside) Chinese text.
                    val b = if (blurThisOverlay || coverEnabled.getOrElse(index) { false }) motionBox else translations[index]
                    val left = b.left * canvas.width
                    val top = b.top * canvas.height
                    val right = (b.left + b.width) * canvas.width
                    val bottom = (b.top + b.height) * canvas.height
                    val text = if (salesSubtitleTimings.isNotEmpty()) {
                        if (index == salesSubtitleRegionIndex) activeSalesCaption?.text.orEmpty() else ""
                    } else editedTexts[index]?.takeIf { it.isNotBlank() } ?: item.vietnameseText
                    if (text.isBlank()) return@forEachIndexed
                    val availableHeight = (bottom - top).coerceAtLeast(1f)
                    val maxWidth = (right - left).coerceAtLeast(40f) - 12f
                    var fittedTextSize = (textSizeSp * canvas.width / 360f).coerceIn(18f, 90f)
                    fun wrapAtCurrentSize(): List<String> {
                        textPaint.textSize = fittedTextSize
                        val wrapped = mutableListOf<String>()
                        var current = StringBuilder()
                        text.split(Regex("\\s+")).forEach { word ->
                            val candidate = if (current.isEmpty()) word else "$current $word"
                            if (textPaint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                                wrapped += current.toString()
                                current = StringBuilder(word)
                            } else current = StringBuilder(candidate)
                        }
                        if (current.isNotEmpty()) wrapped += current.toString()
                        return wrapped
                    }
                    var lines = wrapAtCurrentSize()
                    // Fit every subtitle line inside its panel instead of clipping the last
                    // lines when the caption is longer than the selected region.
                    while (fittedTextSize > 7f && lines.size * fittedTextSize * 1.18f > availableHeight) {
                        fittedTextSize = (fittedTextSize - 1f).coerceAtLeast(7f)
                        lines = wrapAtCurrentSize()
                    }
                    if (lines.isNotEmpty() && lines.size * fittedTextSize * 1.18f > availableHeight) {
                        fittedTextSize = (availableHeight / (lines.size * 1.18f)).coerceAtLeast(4f)
                    }
                    textPaint.textSize = fittedTextSize
                    val lineHeight = fittedTextSize * 1.18f
                    val blockHeight = lines.size * lineHeight
                    val centerX = (left + right) / 2f
                    val firstBaseline = top + (availableHeight - blockHeight) / 2f + fittedTextSize
                    canvas.save()
                    canvas.clipRect(RectF(left, top, right, bottom))
                    lines.forEachIndexed { line, value ->
                        val y = firstBaseline + line * lineHeight
                        canvas.drawText(value, centerX, y, textBg)
                        canvas.drawText(value, centerX, y, textPaint)
                    }
                    canvas.restore()
                }
            }
        }
        val effect = OverlayEffect(listOf(overlay))
        // Separate video, source audio and narration into parallel tracks. This lets
        // the source soundtrack be attenuated independently without muting VieNeu.
        val sourceVideoItem = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(Effects(emptyList(), listOf(effect)))
            .setRemoveAudio(true)
            .build()
        val videoSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            .addItem(sourceVideoItem)
            .build()
        val compositionSequences = mutableListOf(videoSequence)
        val sourceHasAudio = containsTrack(context, source, "audio/")
        if (originalAudioVolume > 0f && sourceHasAudio) {
            val sourceAudioGain = object : GainProcessor.GainProvider {
                override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float =
                    originalAudioVolume

                override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long =
                    if (originalAudioVolume >= 1f) C.TIME_END_OF_SOURCE else C.TIME_UNSET
            }
            val sourceAudioEffects = Effects(
                listOf(GainProcessor(sourceAudioGain)),
                emptyList()
            )
            val sourceAudioItem = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(sourceAudioEffects)
                .setRemoveVideo(true)
                .build()
            compositionSequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                .addItem(sourceAudioItem)
                .build()
        }
        if (hasNarration) {
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
            compositionSequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                .addItem(narrationEdited)
                .build()
        }
        val composition = Composition.Builder(compositionSequences).build()
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
            val outputProbe = MediaMetadataRetriever()
            try {
                outputProbe.setDataSource(temp.absolutePath)
                val outputDuration = outputProbe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val outputWidth = outputProbe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val outputHeight = outputProbe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                check(outputDuration > 0L && outputWidth > 0 && outputHeight > 0) {
                    "Tệp MP4 vừa dựng thiếu thời lượng hoặc khung hình. Không lưu tệp lỗi vào thư viện."
                }
                check(outputDuration + 1500L >= sourceDurationMs) {
                    "Video xuất ra bị ngắn hơn video gốc bất thường. Đã hủy lưu để tránh nhận MP4 bị cắt cụt."
                }
                check(containsTrack(temp, "video/")) {
                    "MP4 đầu ra không có luồng hình ảnh hợp lệ."
                }
                if (hasNarration) {
                    check(containsTrack(temp, "audio/")) {
                        "MP4 đã dựng nhưng không có luồng âm thanh giọng đọc. Hãy thử xuất lại."
                    }
                }
            } finally {
                outputProbe.release()
            }
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
                val copiedBytes = context.contentResolver.openOutputStream(destination)?.use { output ->
                    temp.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Không thể ghi video đầu ra.")
                check(copiedBytes == temp.length()) { "Video được sao chép chưa đầy đủ. Hãy thử xuất lại." }
                val savedProbe = MediaMetadataRetriever()
                try {
                    savedProbe.setDataSource(context, destination)
                    val savedDuration = savedProbe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    check(savedDuration > 0L) { "Video trong thư mục lưu không đọc được. Tệp chưa được công bố." }
                } finally {
                    savedProbe.release()
                }
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
            VideoTextBlur.release(blurSamples)
        }
    }
}
 
private fun containsTrack(file: File, mimePrefix: String): Boolean {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.absolutePath)
        (0 until extractor.trackCount).any { index ->
            extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                ?.startsWith(mimePrefix) == true
        }
    } finally {
        extractor.release()
    }
}


private fun containsTrack(context: Context, uri: Uri, mimePrefix: String): Boolean {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(context, uri, null)
        (0 until extractor.trackCount).any { index ->
            extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                ?.startsWith(mimePrefix) == true
        }
    } finally {
        extractor.release()
    }
}
