package ai.longkaka.seller.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import ai.longkaka.seller.translation.OnScreenText
import ai.longkaka.seller.translation.TextGlyphBox
import ai.longkaka.seller.translation.boundsAt
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * Restores only the detected glyph strokes inside each OCR observation.
 * The surrounding video pixels are kept untouched; no rectangular blur panel is drawn.
 */
internal object VideoTextBlur {
    data class Patch(val timeMs: Long, val box: RenderBox, val anchor: RenderBox, val bitmap: Bitmap)

    fun prepare(
        context: Context,
        source: Uri,
        overlays: List<OnScreenText>,
        boxes: List<RenderBox>,
        strength: Float
    ): Map<Int, List<Patch>> {
        check(runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)) {
            "Không khởi tạo được OpenCV để phục hồi nền chữ."
        }
        val active = overlays.indices.filter { index ->
            boxes.getOrNull(index) != null &&
                overlays[index].observations.isNotEmpty() &&
                overlays[index].lastTimeMs >= overlays[index].timeMs
        }
        if (active.isEmpty()) return emptyMap()

        val retriever = MediaMetadataRetriever()
        val result = linkedMapOf<Int, MutableList<Patch>>()
        // Keep patch memory bounded, but allocate samples by each text track's
        // visible duration. A long-lived track must not receive the same tiny budget
        // as a short caption that appears for only a second.
        val sampleBudgets = TextRestorationQuality.allocateSampleCounts(
            observationCounts = active.map { overlays[it].observations.size },
            durationsMs = active.map { (overlays[it].lastTimeMs - overlays[it].timeMs).coerceAtLeast(0L) }
        )
        try {
            retriever.setDataSource(context, source)
            active.forEachIndexed { activePosition, index ->
                val item = overlays[index]
                val selected = boxes[index]
                val observations = item.observations
                val sampleCount = sampleBudgets[activePosition].coerceAtLeast(1)
                val selectedObservations = if (observations.size <= sampleCount) observations else
                    (0 until sampleCount).map { sampleIndex ->
                        observations[(sampleIndex.toLong() * (observations.lastIndex) / (sampleCount - 1).coerceAtLeast(1)).toInt()]
                    }.distinctBy { it.timeMs }
                val patches = mutableListOf<Patch>()
                selectedObservations.forEach { observed ->
                    val time = observed.timeMs
                    // Match the editor/export motion transform so the restored glyph patch
                    // and replacement subtitle follow the same selected region.
                    val baseLeft = (item.left - .025f).coerceAtLeast(0f)
                    val baseTop = (item.top - .025f).coerceAtLeast(0f)
                    val baseWidth = (item.right - item.left + .05f).coerceAtLeast(.01f)
                    val baseHeight = (item.bottom - item.top + .05f).coerceAtLeast(.01f)
                    val scaleX = (selected.width / baseWidth).coerceIn(.5f, 2.5f)
                    val scaleY = (selected.height / baseHeight).coerceIn(.5f, 2.5f)
                    val left = (observed.left - .025f + selected.left - baseLeft).coerceIn(0f, 1f)
                    val top = (observed.top - .025f + selected.top - baseTop).coerceIn(0f, 1f)
                    val region = RenderBox(
                        left,
                        top,
                        ((observed.right - observed.left + .05f) * scaleX).coerceAtMost(1f - left),
                        ((observed.bottom - observed.top + .05f) * scaleY).coerceAtMost(1f - top)
                    )
                    if (region.width > 0f && region.height > 0f) {
                        val frame = retriever.getFrameAtTime(time * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (frame != null) {
                            try {
                                val x = (region.left * frame.width).toInt().coerceIn(0, frame.width - 1)
                                val y = (region.top * frame.height).toInt().coerceIn(0, frame.height - 1)
                                val width = (region.width * frame.width).toInt().coerceAtLeast(1).coerceAtMost(frame.width - x)
                                val height = (region.height * frame.height).toInt().coerceAtLeast(1).coerceAtMost(frame.height - y)
                                val crop = Bitmap.createBitmap(frame, x, y, width, height)
                                try {
                                    val actualBox = RenderBox(
                                        x.toFloat() / frame.width,
                                        y.toFloat() / frame.height,
                                        width.toFloat() / frame.width,
                                        height.toFloat() / frame.height
                                    )
                                    val mappedGlyphs = observed.glyphs.mapNotNull { glyph ->
                                        val lineWidth = (observed.right - observed.left).coerceAtLeast(0.0001f)
                                        val lineHeight = (observed.bottom - observed.top).coerceAtLeast(0.0001f)
                                        val mappedLeft = region.left + ((glyph.left - observed.left) / lineWidth) * region.width
                                        val mappedTop = region.top + ((glyph.top - observed.top) / lineHeight) * region.height
                                        val mappedRight = region.left + ((glyph.right - observed.left) / lineWidth) * region.width
                                        val mappedBottom = region.top + ((glyph.bottom - observed.top) / lineHeight) * region.height
                                        TextGlyphBox(
                                            left = (mappedLeft - actualBox.left) / actualBox.width,
                                            top = (mappedTop - actualBox.top) / actualBox.height,
                                            right = (mappedRight - actualBox.left) / actualBox.width,
                                            bottom = (mappedBottom - actualBox.top) / actualBox.height
                                        ).takeIf { it.right > it.left && it.bottom > it.top }
                                    }
                                    val temporalDonor = findTemporalDonor(
                                        retriever = retriever,
                                        sourceFrame = crop,
                                        region = region,
                                        frameWidth = frame.width,
                                        frameHeight = frame.height,
                                        track = item,
                                        aroundTimeMs = time,
                                        glyphs = mappedGlyphs
                                    )
                                    val restored = try {
                                        restoreGlyphs(crop, strength, mappedGlyphs, temporalDonor)
                                    } finally {
                                        temporalDonor?.recycle()
                                    }
                                    patches += Patch(
                                        time,
                                        actualBox,
                                        RenderBox(observed.left, observed.top,
                                            (observed.right - observed.left).coerceAtLeast(0.0001f),
                                            (observed.bottom - observed.top).coerceAtLeast(0.0001f)),
                                        restored
                                    )
                                } finally {
                                    crop.recycle()
                                }
                            } finally {
                                frame.recycle()
                            }
                        }
                    }
                }
                if (patches.isNotEmpty()) result[index] = patches
            }
        } finally {
            retriever.release()
        }
        return result
    }

    /**
     * Builds a text-shaped mask from local contrast, slightly expands it to catch
     * anti-aliased edges, then uses Telea inpainting to reconstruct the background.
     * strength controls the inpaint radius; it never selects Gaussian blur.
     */
    /**
     * Looks for a nearby frame where OCR did not observe this track. A temporal donor is
     * accepted only when the glyph area has low local contrast and the surrounding crop
     * still matches the current frame. This deliberately prefers safe inpainting over
     * copying a misaligned moving background.
     */
    private fun findTemporalDonor(
        retriever: MediaMetadataRetriever,
        sourceFrame: Bitmap,
        region: RenderBox,
        frameWidth: Int,
        frameHeight: Int,
        track: OnScreenText,
        aroundTimeMs: Long,
        glyphs: List<TextGlyphBox>
    ): Bitmap? {
        if (glyphs.isEmpty() || track.observations.size < 2) return null
        val observedTimes = track.observations.map { it.timeMs }
        val candidates = mutableListOf<Long>()
        // Search only a short neighborhood around this patch. Never walk the entire
        // duration of a long track when every sampled frame contains the overlay.
        val searchStart = maxOf(0L, aroundTimeMs - 1_500L, track.timeMs - 750L)
        val searchEnd = minOf(aroundTimeMs + 1_500L, track.lastTimeMs + 750L)
        var candidateTime = searchStart
        while (candidateTime <= searchEnd && candidates.size < 12) {
            if (observedTimes.none { kotlin.math.abs(it - candidateTime) <= 140L } &&
                kotlin.math.abs(candidateTime - aroundTimeMs) >= 180L
            ) candidates += candidateTime
            candidateTime += 250L
        }
        // Try only the closest six candidate frames to keep memory and decode time bounded.
        for (time in candidates.sortedBy { kotlin.math.abs(it - aroundTimeMs) }.take(6)) {
            val frame = runCatching {
                retriever.getFrameAtTime(time * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            }.getOrNull() ?: continue
            try {
                if (frame.width != frameWidth || frame.height != frameHeight) continue
                val x = (region.left * frame.width).toInt().coerceIn(0, frame.width - 1)
                val y = (region.top * frame.height).toInt().coerceIn(0, frame.height - 1)
                val width = (region.width * frame.width).toInt().coerceAtLeast(1).coerceAtMost(frame.width - x)
                val height = (region.height * frame.height).toInt().coerceAtLeast(1).coerceAtMost(frame.height - y)
                val donor = Bitmap.createBitmap(frame, x, y, width, height)
                if (isSafeTemporalDonor(sourceFrame, donor, glyphs)) return donor
                donor.recycle()
            } finally {
                frame.recycle()
            }
        }
        return null
    }

    private fun isSafeTemporalDonor(
        current: Bitmap,
        donor: Bitmap,
        glyphs: List<TextGlyphBox>
    ): Boolean {
        if (current.width != donor.width || current.height != donor.height) return false
        // Compare a sparse grid outside the glyph boxes. A moving scene or a scene cut
        // must not be copied into the current frame.
        val stepX = (current.width / 36).coerceAtLeast(1)
        val stepY = (current.height / 28).coerceAtLeast(1)
        var difference = 0.0
        var samples = 0
        for (y in stepY until current.height step stepY) {
            for (x in stepX until current.width step stepX) {
                val nx = x.toFloat() / current.width
                val ny = y.toFloat() / current.height
                val insideGlyph = glyphs.any { nx >= it.left && nx <= it.right && ny >= it.top && ny <= it.bottom }
                if (insideGlyph) continue
                val a = current.getPixel(x, y)
                val b = donor.getPixel(x, y)
                difference += (kotlin.math.abs(Color.red(a) - Color.red(b)) +
                    kotlin.math.abs(Color.green(a) - Color.green(b)) +
                    kotlin.math.abs(Color.blue(a) - Color.blue(b))) / 3.0
                samples++
            }
        }
        if (samples < 20 || difference / samples > 18.0) return false

        val donorRgba = Mat()
        val donorGray = Mat()
        val donorBackground = Mat()
        val donorContrast = Mat()
        try {
            Utils.bitmapToMat(donor, donorRgba)
            Imgproc.cvtColor(donorRgba, donorGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(donorGray, donorBackground, Size(0.0, 0.0), 3.0)
            Core.absdiff(donorGray, donorBackground, donorContrast)
            var highContrast = 0
            var total = 0
            glyphs.forEach { glyph ->
                val left = (glyph.left * donor.width).toInt().coerceIn(0, donor.width - 1)
                val top = (glyph.top * donor.height).toInt().coerceIn(0, donor.height - 1)
                val right = (glyph.right * donor.width).toInt().coerceIn(left + 1, donor.width)
                val bottom = (glyph.bottom * donor.height).toInt().coerceIn(top + 1, donor.height)
                for (yy in top until bottom step 2) {
                    for (xx in left until right step 2) {
                        if (donorContrast.get(yy, xx)[0] > 18.0) highContrast++
                        total++
                    }
                }
            }
            // Reject donors that still look like text or contain strong edges in the mask.
            return total >= 8 && highContrast.toDouble() / total <= 0.18
        } finally {
            donorRgba.release()
            donorGray.release()
            donorBackground.release()
            donorContrast.release()
        }
    }

    private fun restoreGlyphs(
        source: Bitmap,
        strength: Float,
        glyphs: List<TextGlyphBox>,
        temporalDonor: Bitmap? = null
    ): Bitmap {
        val rgba = Mat()
        val rgb = Mat()
        val gray = Mat()
        val localBackground = Mat()
        val contrast = Mat()
        val mask = Mat.zeros(source.height, source.width, CvType.CV_8UC1)
        val expandedMask = Mat()
        val softMask = Mat()
        val restoredRgb = Mat()
        val output = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)

            // Estimate local contrast once, but only threshold pixels inside ML Kit's
            // tight element boxes. The OCR line rectangle is never treated as a text mask:
            // product edges, hands, fabric and other moving details outside glyph boxes stay clear.
            Imgproc.GaussianBlur(gray, localBackground, Size(0.0, 0.0), 4.0)
            Core.absdiff(gray, localBackground, contrast)
            if (glyphs.isNotEmpty()) {
                glyphs.forEach { glyph ->
                    val padX = 1
                    val padY = 1
                    val x1 = (glyph.left * source.width).toInt().coerceIn(0, source.width - 1)
                    val y1 = (glyph.top * source.height).toInt().coerceIn(0, source.height - 1)
                    val x2 = (glyph.right * source.width).toInt().coerceIn(x1 + 1, source.width)
                    val y2 = (glyph.bottom * source.height).toInt().coerceIn(y1 + 1, source.height)
                    val rect = Rect(
                        (x1 - padX).coerceAtLeast(0),
                        (y1 - padY).coerceAtLeast(0),
                        (x2 - x1 + padX * 2).coerceAtMost(source.width - (x1 - padX).coerceAtLeast(0)),
                        (y2 - y1 + padY * 2).coerceAtMost(source.height - (y1 - padY).coerceAtLeast(0))
                    )
                    if (rect.width < 2 || rect.height < 2) return@forEach
                    val contrastRoi = contrast.submat(rect)
                    val maskRoi = mask.submat(rect)
                    try {
                        Imgproc.threshold(contrastRoi, maskRoi, 7.0, 255.0, Imgproc.THRESH_BINARY)
                    } finally {
                        contrastRoi.release()
                        maskRoi.release()
                    }
                }
            } else {
                // Compatibility path for older saved scans without element geometry.
                Imgproc.threshold(contrast, mask, 11.0, 255.0, Imgproc.THRESH_BINARY)
            }
            // Tight masks need only a one-pixel expansion; broad dilation recreates the
            // very background smearing this pipeline is designed to avoid.
            val dilationPasses = when {
                strength >= 23f -> 3
                strength >= 11f -> 2
                else -> 1
            }
            Imgproc.dilate(mask, expandedMask, kernel, Point(-1.0, -1.0), dilationPasses)
            // Feather only the edge of the glyph mask. Transparent pixels outside it
            // let the live video frame show through instead of freezing a whole crop.
            Imgproc.GaussianBlur(expandedMask, softMask, Size(3.0, 3.0), 0.8)

            // Actually reconstruct pixels from the neighborhood. The previous build
            // blurred the glyphs and therefore left visible soft smears in the video.
            // Keep the mask glyph-shaped and tightly expanded so product details outside
            // the strokes are not modified.
            val radius = (2f + ((strength.coerceIn(3f, 28f) - 3f) / 25f) * 3f).toDouble()
            try {
                Photo.inpaint(rgb, expandedMask, restoredRgb, radius, Photo.INPAINT_TELEA)
            } catch (_: Throwable) {
                // NS is a different reconstruction method, not a blur fallback.
                Photo.inpaint(rgb, expandedMask, restoredRgb, radius, Photo.INPAINT_NS)
            }
            // If a nearby clean frame passed both the text-residual and background-
            // similarity checks, prefer its real pixels inside the glyph mask. Otherwise
            // keep the on-device inpainting result; never force a mismatched donor.
            if (temporalDonor != null && temporalDonor.width == source.width && temporalDonor.height == source.height) {
                val donorRgba = Mat()
                val donorRgb = Mat()
                try {
                    Utils.bitmapToMat(temporalDonor, donorRgba)
                    Imgproc.cvtColor(donorRgba, donorRgb, Imgproc.COLOR_RGBA2RGB)
                    donorRgb.copyTo(restoredRgb, expandedMask)
                } finally {
                    donorRgba.release()
                    donorRgb.release()
                }
            }
            Imgproc.cvtColor(restoredRgb, output, Imgproc.COLOR_RGB2RGBA)
            // Composite only restored glyph pixels. The current frame remains visible
            // everywhere outside the feathered mask.
            Core.insertChannel(softMask, output, 3)
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                .also { Utils.matToBitmap(output, it) }
        } finally {
            rgba.release()
            rgb.release()
            gray.release()
            localBackground.release()
            contrast.release()
            mask.release()
            expandedMask.release()
            softMask.release()
            restoredRgb.release()
            output.release()
            kernel.release()
        }
    }

    fun release(samples: Map<Int, List<Patch>>) {
        samples.values.flatten().forEach { patch ->
            if (!patch.bitmap.isRecycled) patch.bitmap.recycle()
        }
    }
}
