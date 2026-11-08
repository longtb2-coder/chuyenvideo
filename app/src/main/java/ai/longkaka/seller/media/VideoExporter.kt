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
import androidx.media3.transformer.ProgressHolder
import ai.longkaka.seller.translation.OnScreenText
import ai.longkaka.seller.translation.boundsAt
import ai.longkaka.seller.speech.vieneu.VieNeuChunkTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        val needsSeparateRestoration = blurEnabled.any { it } || coverEnabled.any { it }
        if (!needsSeparateRestoration) {
            return@withContext exportSinglePass(
                source, overlays, translations, covers, coverEnabled, coverColor, coverAlpha,
                textSizeSp, editedTexts, narrationFile, onProgress, blurEnabled, blurStrength,
                salesSubtitleTimings, salesSubtitleRegionIndex, originalAudioVolume
            )
        }

        // Pass 1: remove/restore the original text only. No translated caption is drawn here.
        val cleanedIntermediate = exportSinglePass(
            source, overlays, translations, covers, coverEnabled, coverColor, coverAlpha,
            textSizeSp, editedTexts, narrationFile,
            onProgress = { onProgress((it * 45) / 100) },
            blurEnabled = blurEnabled,
            blurStrength = blurStrength,
            salesSubtitleTimings = salesSubtitleTimings,
            salesSubtitleRegionIndex = salesSubtitleRegionIndex,
            originalAudioVolume = originalAudioVolume,
            drawSubtitles = false,
            outputPrefix = "LONGKAKA_CLEAN_STAGE"
        )
        try {
            verifyCleanIntermediate(source, cleanedIntermediate)
            // Re-run Chinese OCR on the cleaned video, limited to the original target
            // regions. If any source glyph remains, stop before subtitle burn-in.
            val selectedRemovalTracks = overlays.filterIndexed { index, _ ->
                blurEnabled.getOrElse(index) { false } || coverEnabled.getOrElse(index) { false }
            }
            VideoTextRemovalVerifier.requireNoResidualChinese(context, cleanedIntermediate, selectedRemovalTracks)

            // Pass 2: use the cleaned video as the new source and burn captions only.
            // Audio (including VieNeu narration) is already present in the intermediate,
            // so it is copied at unity gain and never mixed a second time.
            exportSinglePass(
                source = cleanedIntermediate,
                overlays = overlays,
                translations = translations,
                covers = covers,
                coverEnabled = overlays.indices.map { false },
                coverColor = coverColor,
                coverAlpha = coverAlpha,
                textSizeSp = textSizeSp,
                editedTexts = editedTexts,
                narrationFile = null,
                onProgress = { onProgress(50 + (it / 2)) },
                blurEnabled = emptyList(),
                blurStrength = blurStrength,
                salesSubtitleTimings = salesSubtitleTimings,
                salesSubtitleRegionIndex = salesSubtitleRegionIndex,
                originalAudioVolume = 1f,
                allowSubtitleWithoutNarration = salesSubtitleTimings.isNotEmpty(),
                subtitleFollowsMotionIndices = overlays.indices.filter { index ->
                    blurEnabled.getOrElse(index) { false } || coverEnabled.getOrElse(index) { false }
                }.toSet(),
                outputPrefix = "LONGKAKA"
            )
        } finally {
            // Never delete the original input. Only remove this app-created intermediate.
            runCatching { context.contentResolver.delete(cleanedIntermediate, null, null) }
        }
    }

    private suspend fun exportSinglePass(
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
        originalAudioVolume: Float = 1f,
        drawSubtitles: Boolean = true,
        allowSubtitleWithoutNarration: Boolean = false,
        subtitleFollowsMotionIndices: Set<Int> = emptySet(),
        outputPrefix: String = "LONGKAKA"
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
        require(salesSubtitleTimings.isEmpty() || hasNarration || allowSubtitleWithoutNarration) { "Có phụ đề đồng bộ giọng đọc nhưng chưa có tệp giọng đọc hợp lệ." }
        if (hasNarration) {
            val narrationProbe = MediaMetadataRetriever()
            val narrationDurationMs = try {
                narrationProbe.setDataSource(narrationFile!!.absolutePath)
                narrationProbe.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                narrationProbe.release()
            }
            require(narrationDurationMs > 0L) { "Không đọc được thời lượng tệp giọng đọc. Hãy tạo lại giọng đọc trước khi xuất." }
            require(narrationDurationMs <= sourceDurationMs + 250L) {
                val excessSeconds = ((narrationDurationMs - sourceDurationMs).coerceAtLeast(0L) / 1000.0)
                "Giọng đọc dài hơn video khoảng %.1f giây. App đã dừng để không tự cắt mất lời đọc hoặc làm phụ đề lệch tiếng. Cần kéo dài video hoặc rút gọn lời đọc rồi xuất lại.".format(java.util.Locale.ROOT, excessSeconds)
            }
        }
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
            // Centered dark outline behind white text. A separate fill paint with
            // default left alignment caused a second, offset copy of every subtitle.
            private val textBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
            }

            override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
                val t = presentationTimeUs / 1000L
                val activeSalesCaption = salesSubtitleTimings
                    .filter { t >= it.startMs && t < it.endMs }
                    .maxByOrNull { it.startMs }

                // OCR can produce several subtitle tracks for changing words in the same
                // screen position. Never burn all overlapping tracks at once: select only
                // the track whose observation is closest to this output frame.
                fun regionAt(index: Int): RenderBox {
                    val item = overlays[index]
                    val observed = item.boundsAt(t)
                    return if (observed != null) {
                        RenderBox(observed.left, observed.top,
                            (observed.right - observed.left).coerceAtLeast(0f),
                            (observed.bottom - observed.top).coerceAtLeast(0f))
                    } else RenderBox(item.left, item.top,
                        (item.right - item.left).coerceAtLeast(0f),
                        (item.bottom - item.top).coerceAtLeast(0f))
                }

                // Use the same expanded, motion-tracked area that VideoTextBlur restored,
                // not just the tight OCR glyph bounds. This gives the replacement caption
                // the full user-selected clean patch while preserving its movement.
                fun restoredCaptionRegionAt(index: Int): RenderBox {
                    val item = overlays[index]
                    val selected = covers[index]
                    val observed = item.boundsAt(t) ?: return selected
                    val defaultLeft = (item.left - 0.025f).coerceAtLeast(0f)
                    val defaultTop = (item.top - 0.025f).coerceAtLeast(0f)
                    val defaultRight = (item.right + 0.025f).coerceAtMost(1f)
                    val defaultBottom = (item.bottom + 0.025f).coerceAtMost(1f)
                    val defaultWidth = (defaultRight - defaultLeft).coerceAtLeast(0.01f)
                    val defaultHeight = (defaultBottom - defaultTop).coerceAtLeast(0.01f)
                    val scaleX = (selected.width / defaultWidth).coerceIn(0.5f, 2.5f)
                    val scaleY = (selected.height / defaultHeight).coerceIn(0.5f, 2.5f)
                    val left = (observed.left - 0.025f + selected.left - defaultLeft).coerceIn(0f, 1f)
                    val top = (observed.top - 0.025f + selected.top - defaultTop).coerceIn(0f, 1f)
                    return RenderBox(
                        left,
                        top,
                        ((observed.right - observed.left + 0.05f) * scaleX).coerceAtMost(1f - left),
                        ((observed.bottom - observed.top + 0.05f) * scaleY).coerceAtMost(1f - top)
                    )
                }
                fun overlapRatio(a: RenderBox, b: RenderBox): Float {
                    val width = (minOf(a.left + a.width, b.left + b.width) - maxOf(a.left, b.left)).coerceAtLeast(0f)
                    val height = (minOf(a.top + a.height, b.top + b.height) - maxOf(a.top, b.top)).coerceAtLeast(0f)
                    val intersection = width * height
                    val smaller = minOf(a.width * a.height, b.width * b.height)
                    return if (smaller <= 0f) 0f else intersection / smaller
                }
                val activeOverlayIndices = overlays.indices.filter { index ->
                    val item = overlays[index]
                    val visible = t >= (item.timeMs - 500L).coerceAtLeast(0L) && t <= item.lastTimeMs + 500L
                    visible && (item.observations.isEmpty() || item.boundsAt(t) != null)
                }
                fun observationDistance(index: Int): Long {
                    val item = overlays[index]
                    return item.observations.minOfOrNull { kotlin.math.abs(it.timeMs - t) }
                        ?: kotlin.math.abs(item.timeMs - t)
                }
                overlays.forEachIndexed { index, item ->
                    val trackVisible = t >= (item.timeMs - 500L).coerceAtLeast(0L) && t <= item.lastTimeMs + 500L
                    if (!trackVisible) return@forEachIndexed
                    if (trackVisible) {
                        val currentBox = regionAt(index)
                        val currentDistance = observationDistance(index)
                        val superseded = activeOverlayIndices.any { otherIndex ->
                            if (otherIndex == index) false else {
                                val other = overlays[otherIndex]
                                val otherDistance = observationDistance(otherIndex)
                                overlapRatio(currentBox, regionAt(otherIndex)) >= 0.35f &&
                                    (otherDistance < currentDistance ||
                                        (otherDistance == currentDistance && other.timeMs > item.timeMs))
                            }
                        }
                        if (superseded) return@forEachIndexed
                    }
                    val c = covers[index]
                    val observed = item.boundsAt(t)
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
                    } else if (item.observations.isEmpty()) c else return@forEachIndexed
                    val blurThisOverlay = blurEnabled.getOrElse(index) { false }
                    // Layer 1: blur the original pixels. Keep drawing below so the Vietnamese
                    // subtitle is composited on top of this blurred region in the same frame.
                    if (blurThisOverlay && trackVisible) {
                        // Never stretch a sampled repair across an unrelated scene or a long OCR gap.
                        // If no nearby repair exists, leave the frame untouched; the clean-video
                        // verifier will stop export rather than accept residual text or stale pixels.
                        val patch = blurSamples[index]
                            ?.minByOrNull { kotlin.math.abs(it.timeMs - t) }
                            ?.takeIf { kotlin.math.abs(it.timeMs - t) <= TextRestorationQuality.MAX_PATCH_DISTANCE_MS }
                        if (patch != null) {
                            // The bitmap is a crop around the sampled OCR glyphs, not the
                            // whole subtitle panel. Reproject that crop by the OCR track's
                            // actual translation/scale since the sample time. This avoids
                            // stretching a small glyph mask across the user-selected panel.
                            val destination = if (observed != null) {
                                val sampleWidth = patch.anchor.width.coerceAtLeast(0.0001f)
                                val sampleHeight = patch.anchor.height.coerceAtLeast(0.0001f)
                                val currentWidth = (observed.right - observed.left).coerceAtLeast(0.0001f)
                                val currentHeight = (observed.bottom - observed.top).coerceAtLeast(0.0001f)
                                val scaleX = (currentWidth / sampleWidth).coerceIn(0.5f, 2.5f)
                                val scaleY = (currentHeight / sampleHeight).coerceIn(0.5f, 2.5f)
                                // Scale the crop offset from the OCR anchor as well as
                                // the crop dimensions. Without this, a resized text track shifts
                                // the restored pixels away from the glyphs and leaves halos.
                                val left = observed.left + (patch.box.left - patch.anchor.left) * scaleX
                                val top = observed.top + (patch.box.top - patch.anchor.top) * scaleY
                                RectF(
                                    left * canvas.width,
                                    top * canvas.height,
                                    (left + patch.box.width * scaleX) * canvas.width,
                                    (top + patch.box.height * scaleY) * canvas.height
                                )
                            } else {
                                RectF(
                                    patch.box.left * canvas.width,
                                    patch.box.top * canvas.height,
                                    (patch.box.left + patch.box.width) * canvas.width,
                                    (patch.box.top + patch.box.height) * canvas.height
                                )
                            }
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
                    val b = if (index in subtitleFollowsMotionIndices || blurThisOverlay || coverEnabled.getOrElse(index) { false }) motionBox else translations[index]
                    val left = b.left * canvas.width
                    val top = b.top * canvas.height
                    val right = (b.left + b.width) * canvas.width
                    val bottom = (b.top + b.height) * canvas.height
                    // OCR translation is its own visual layer: it always follows the
                    // original on-screen text geometry, independent of spoken dialogue.
                    // During narration export, the restored OCR regions selected for
                    // replacement are reserved for the spoken Vietnamese caption. Do not
                    // also paint the static OCR translation there: that created two captions
                    // occupying the same cleaned patch.
                    val isNarrationCaptionRegion = salesSubtitleTimings.isNotEmpty() &&
                        index in subtitleFollowsMotionIndices
                    val text = if (drawSubtitles && !isNarrationCaptionRegion) {
                        editedTexts[index]?.takeIf { it.isNotBlank() } ?: item.vietnameseText
                    } else ""
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
                    // Never silently export unreadably tiny captions. If the selected
                    // subtitle region cannot hold the text at a readable size, stop and ask
                    // for a larger region instead of shrinking down to 4-7 px.
                    val minimumReadableSize = 24f
                    while (fittedTextSize > minimumReadableSize &&
                        lines.size * fittedTextSize * 1.18f > availableHeight) {
                        fittedTextSize = (fittedTextSize - 1f).coerceAtLeast(minimumReadableSize)
                        lines = wrapAtCurrentSize()
                    }
                    check(lines.isEmpty() || lines.size * fittedTextSize * 1.18f <= availableHeight) {
                        "Vùng phụ đề quá nhỏ để hiển thị rõ. Hãy mở rộng vùng phụ đề hoặc rút gọn câu dịch rồi xuất lại."
                    }
                    textPaint.textSize = fittedTextSize
                    textBg.textSize = fittedTextSize
                    textBg.strokeWidth = (fittedTextSize * 0.12f).coerceIn(2f, 7f)
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

                // Spoken-dialogue captions are rendered as a separate, time-synchronised
                // track. They no longer replace OCR translations or borrow one OCR box.
                if (drawSubtitles && activeSalesCaption != null && activeSalesCaption.text.isNotBlank()) {
                    val caption = activeSalesCaption.text.trim()
                    val occupied = overlays.indices.filter { index ->
                        val item = overlays[index]
                        t >= (item.timeMs - 500L).coerceAtLeast(0L) &&
                            t <= item.lastTimeMs + 500L && item.boundsAt(t) != null
                    }.map { regionAt(it) }
                    // Prefer the actual regions whose Chinese glyphs were restored.
                    // The voice chunk timing is the master clock; the selected restored
                    // region supplies only the on-screen placement and motion.
                    val activeRestoredIndices = subtitleFollowsMotionIndices
                        .filter { index ->
                            val item = overlays[index]
                            t >= (item.timeMs - 500L).coerceAtLeast(0L) &&
                                t <= item.lastTimeMs + 500L && item.boundsAt(t) != null
                        }
                    val restoredCaptionRegions = activeRestoredIndices.map { restoredCaptionRegionAt(it) }
                    // The editor's selected region wins over larger unrelated OCR regions.
                    val preferredRestoredRegion = activeRestoredIndices
                        .firstOrNull { it == salesSubtitleRegionIndex }
                        ?.let { restoredCaptionRegionAt(it) }
                    val captionBox = SpeechCaptionPlacement.chooseRestoredRegion(
                        restoredCaptionRegions,
                        preferredRestoredRegion
                    ) ?: SubtitleLayerLayout.dialogueCaptionBox(occupied)
                    val left = captionBox.left * canvas.width
                    val top = captionBox.top * canvas.height
                    val right = (captionBox.left + captionBox.width) * canvas.width
                    val bottom = (captionBox.top + captionBox.height) * canvas.height
                    val maxWidth = (right - left).coerceAtLeast(80f) - 20f
                    var size = (textSizeSp * canvas.width / 360f).coerceIn(20f, 56f)
                    fun wrapDialogue(): List<String> {
                        textPaint.textSize = size
                        val lines = mutableListOf<String>()
                        var current = StringBuilder()
                        caption.split(Regex("\\s+")).forEach { word ->
                            val candidate = if (current.isEmpty()) word else "$current $word"
                            if (textPaint.measureText(candidate) > maxWidth && current.isNotEmpty()) {
                                lines += current.toString()
                                current = StringBuilder(word)
                            } else current = StringBuilder(candidate)
                        }
                        if (current.isNotEmpty()) lines += current.toString()
                        return lines
                    }
                    var dialogueLines = wrapDialogue()
                    while (dialogueLines.size > 3 && size > 24f) {
                        size = (size - 2f).coerceAtLeast(24f)
                        dialogueLines = wrapDialogue()
                    }
                    check(dialogueLines.size <= 3) {
                        "Cụm phụ đề vẫn quá dài so với vùng chữ đã phục hồi. Hãy mở rộng vùng phục hồi hoặc rút gọn câu; app không cắt mất chữ."
                    }
                    textBg.textSize = size
                    textBg.strokeWidth = (size * 0.12f).coerceIn(2f, 7f)
                    val lineHeight = size * 1.16f
                    val blockHeight = dialogueLines.size * lineHeight
                    check(blockHeight <= bottom - top) {
                        "Vùng chữ đã phục hồi quá thấp để hiển thị phụ đề rõ. Hãy tăng chiều cao vùng phục hồi."
                    }
                    val centerX = (left + right) / 2f
                    val firstBaseline = top + ((bottom - top - blockHeight) / 2f).coerceAtLeast(0f) + size
                    canvas.save()
                    canvas.clipRect(RectF(left, top, right, bottom))
                    dialogueLines.forEachIndexed { line, value ->
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
            // The preflight check above guarantees the narration fits. Keep a small
            // muxer tolerance, but never silently clip a longer voice track.
            val narrationItem = MediaItem.Builder()
                .setUri(Uri.fromFile(narrationFile))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(sourceDurationMs + 250L)
                        .build()
                )
                .build()
            val narrationEdited = EditedMediaItem.Builder(narrationItem).setRemoveVideo(true).build()
            compositionSequences += EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                .addItem(narrationEdited)
                .build()
        }
        val composition = Composition.Builder(compositionSequences).build()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                var progressJob: Job? = null
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: androidx.media3.transformer.Composition, result: ExportResult) {
                            progressJob?.cancel()
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            result: ExportResult,
                            exception: ExportException
                        ) {
                            progressJob?.cancel()
                            if (continuation.isActive) continuation.resumeWithException(exception)
                        }
                    }).build()
                continuation.invokeOnCancellation {
                    progressJob?.cancel()
                    runCatching { transformer.cancel() }
                }
                transformer.start(composition, temp.absolutePath)
                val progressHolder = ProgressHolder()
                progressJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                    while (isActive && continuation.isActive) {
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressHolder.progress.coerceIn(0, 94))
                        }
                        delay(400)
                    }
                }
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
                put(MediaStore.Video.Media.DISPLAY_NAME, "${outputPrefix}_${System.currentTimeMillis()}.mp4")
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
    private fun verifyCleanIntermediate(original: Uri, cleaned: Uri) {
        fun metadata(uri: Uri): Triple<Long, Int, Int> {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                Triple(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                )
            } finally {
                retriever.release()
            }
        }
        val before = metadata(original)
        val after = metadata(cleaned)
        check(after.first > 0L && after.second > 0 && after.third > 0) {
            "Video sau khi xóa chữ không có khung hình hợp lệ. Chưa thể chèn phụ đề."
        }
        check(kotlin.math.abs(before.first - after.first) <= 1500L) {
            "Video trung gian bị thay đổi thời lượng bất thường. Đã dừng trước bước chèn phụ đề."
        }
        check(containsTrack(context, cleaned, "video/")) {
            "Không tìm thấy luồng hình ảnh trong video sau khi xóa chữ."
        }
        // Decode three checkpoints to catch a corrupt or unreadable intermediate before
        // any subtitle is burned into it. This is a technical verification, not OCR proof.
        val probe = MediaMetadataRetriever()
        try {
            probe.setDataSource(context, cleaned)
            val durationUs = after.first * 1000L
            listOf(0.10, 0.50, 0.90).forEach { fraction ->
                val frame = probe.getFrameAtTime((durationUs * fraction).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                check(frame != null) {
                    "Không đọc được khung hình kiểm tra sau khi xóa chữ. Đã dừng trước bước chèn phụ đề."
                }
                frame?.recycle()
            }
        } finally {
            probe.release()
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


/** Converts processing exceptions into a safe, actionable recovery plan. */
data class VideoRecoveryAdvice(
    val title: String,
    val explanation: String,
    val nextStep: String
)

object VideoRecoveryAdvisor {
    fun diagnose(error: Throwable): VideoRecoveryAdvice {
        val chain = generateSequence(error) { it.cause }
            .take(8)
            .joinToString(" ") { (it.message ?: it.javaClass.simpleName).lowercase() }
        return when {
            "giọng đọc dài hơn video" in chain ->
                VideoRecoveryAdvice(
                    "Giọng đọc dài hơn video",
                    "App đã phát hiện phần lời đọc sẽ bị cắt nếu tiếp tục xuất theo thời lượng video gốc.",
                    "Kéo dài video nguồn hoặc rút gọn nội dung đọc để khớp thời lượng. App không tự cắt mất câu cuối."
                )
            "vùng phụ đề quá nhỏ" in chain || "unreadably tiny" in chain ->
                VideoRecoveryAdvice(
                    "Vùng phụ đề chưa đủ rộng",
                    "App đã chặn xuất để không tạo phụ đề quá nhỏ, khó đọc trên điện thoại.",
                    "Rút gọn câu dịch trong ô chỉnh sửa rồi xuất lại. Không nên giảm cỡ chữ để ép nội dung vào vùng quá hẹp."
                )
            "residual" in chain || "còn sót" in chain || "chữ trung" in chain ->
                VideoRecoveryAdvice(
                    "Phục hồi chữ chưa đạt",
                    "Bộ kiểm định vẫn phát hiện chữ gốc trong vùng cần xóa. App dừng trước khi chèn phụ đề để không che giấu lỗi.",
                    "Khoanh sát lại vùng chữ. Nếu chữ nằm trên nền chuyển động/phức tạp, cần phục hồi theo khung hình lân cận hoặc chỉnh vùng thủ công."
                )
            "opencv" in chain || "inpaint" in chain || "native" in chain ->
                VideoRecoveryAdvice(
                    "Bộ phục hồi hình ảnh gặp lỗi",
                    "Không hoàn tất được bước tạo mặt nạ hoặc phục hồi nền. App không tự chuyển sang làm nhòe vì sẽ giảm chất lượng.",
                    "Kiểm tra video nguồn và thử lại. Nếu lặp lại, cần kiểm tra thư viện OpenCV hoặc vùng chữ OCR."
                )
            "outofmemory" in chain || "out of memory" in chain ->
                VideoRecoveryAdvice(
                    "Thiếu bộ nhớ xử lý",
                    "Khung hình hoặc số vùng xử lý vượt quá bộ nhớ còn trống của điện thoại.",
                    "Đóng ứng dụng nền, giải phóng bộ nhớ rồi thử video ngắn hơn hoặc video có độ phân giải thấp hơn."
                )
            "encoder" in chain || "codec" in chain || "transformer" in chain || "muxer" in chain ->
                VideoRecoveryAdvice(
                    "Bộ mã hóa MP4 gặp lỗi",
                    "Thiết bị không hoàn tất được cấu hình mã hóa hoặc ghép luồng hình/tiếng.",
                    "Thử lại sau khi khởi động lại ứng dụng. Nếu vẫn lỗi, cần điều chỉnh codec hoặc cấu hình xuất."
                )
            "narration" in chain || "giọng đọc" in chain || "audio" in chain ->
                VideoRecoveryAdvice(
                    "Lỗi tệp âm thanh",
                    "Không đọc được hoặc không ghép được giọng đọc/âm thanh nguồn.",
                    "Tạo lại tệp giọng đọc, nghe thử và kiểm tra thời lượng trước khi xuất."
                )
            "duration" in chain || "thời lượng" in chain || "ngắn hơn" in chain || "mp4" in chain ->
                VideoRecoveryAdvice(
                    "Video đầu ra chưa hợp lệ",
                    "MP4 không vượt qua kiểm tra thời lượng, khung hình hoặc luồng âm thanh/hình ảnh.",
                    "Giữ video gốc, kiểm tra dung lượng trống và thử xuất lại. Không sử dụng tệp MP4 lỗi."
                )
            else -> VideoRecoveryAdvice(
                "Chưa xác định được nguyên nhân",
                error.message?.take(240)?.ifBlank { "Quá trình xử lý gặp lỗi không xác định." }
                    ?: "Quá trình xử lý gặp lỗi không xác định.",
                "Ghi lại thông tin lỗi và video nguồn để xác định đúng công đoạn cần sửa; không bỏ qua lỗi để xuất tệp."
            )
        }
    }
}
