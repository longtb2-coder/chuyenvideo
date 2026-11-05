package ai.longkaka.seller.media

import android.content.Context
import android.graphics.Bitmap
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
    data class Patch(val timeMs: Long, val box: RenderBox, val bitmap: Bitmap)

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
        // Keep memory bounded on phones: distribute at most 160 source-frame patches
        // over all OCR tracks, while ensuring every track receives at least two samples.
        val perRegionBudget = (160 / active.size).coerceAtLeast(2)
        try {
            retriever.setDataSource(context, source)
            active.forEach { index ->
                val item = overlays[index]
                val selected = boxes[index]
                val observations = item.observations
                val sampleCount = minOf(observations.size, perRegionBudget).coerceAtLeast(1)
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
                                    patches += Patch(time, actualBox, restoreGlyphs(crop, strength, mappedGlyphs))
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
    private fun restoreGlyphs(source: Bitmap, strength: Float, glyphs: List<TextGlyphBox>): Bitmap {
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
            val dilationPasses = if (strength >= 20f) 3 else 2
            Imgproc.dilate(mask, expandedMask, kernel, Point(-1.0, -1.0), dilationPasses)
            // Feather only the edge of the glyph mask. Transparent pixels outside it
            // let the live video frame show through instead of freezing a whole crop.
            Imgproc.GaussianBlur(expandedMask, softMask, Size(3.0, 3.0), 0.8)

            // Actually reconstruct pixels from the neighborhood. The previous build
            // blurred the glyphs and therefore left visible soft smears in the video.
            // Keep the mask glyph-shaped and tightly expanded so product details outside
            // the strokes are not modified.
            val radius = strength.coerceIn(2f, 5f).toDouble()
            try {
                Photo.inpaint(rgb, expandedMask, restoredRgb, radius, Photo.INPAINT_TELEA)
            } catch (_: Throwable) {
                // NS is a different reconstruction method, not a blur fallback.
                Photo.inpaint(rgb, expandedMask, restoredRgb, radius, Photo.INPAINT_NS)
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
