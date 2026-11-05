package ai.longkaka.seller.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import ai.longkaka.seller.translation.OnScreenText
import ai.longkaka.seller.translation.boundsAt
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

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
            "Không khởi tạo được OpenCV để làm mờ chữ."
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
                    // Match the editor/export motion transform exactly so the blurred
                    // patch and its replacement subtitle share the same moving region.
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
                                    patches += Patch(time, actualBox, gaussianBlur(crop, strength))
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
     * strength controls the mask tolerance/inpaint radius rather than blurring a box.
     */
    private fun gaussianBlur(source: Bitmap, strength: Float): Bitmap {
        val rgba = Mat()
        val rgb = Mat()
        val gray = Mat()
        val localBackground = Mat()
        val contrast = Mat()
        val mask = Mat()
        val expandedMask = Mat()
        val restored = Mat()
        val output = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        try {
            Utils.bitmapToMat(source, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)

            // Compare each pixel with its local background. This follows the strokes
            // themselves (dark or bright text), instead of masking the whole OCR box.
            Imgproc.GaussianBlur(gray, localBackground, Size(0.0, 0.0), 6.0)
            Core.absdiff(gray, localBackground, contrast)
            val threshold = (strength.coerceIn(3f, 28f) * 0.9f + 5f).toDouble()
            Imgproc.threshold(contrast, mask, threshold, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.dilate(mask, expandedMask, kernel)

            // Telea reconstructs from nearby pixels. Only the character strokes and
            // their thin anti-aliased fringe are replaced; all other pixels are retained.
            Imgproc.inpaint(
                rgb,
                expandedMask,
                restored,
                strength.coerceIn(2f, 9f).toDouble(),
                Imgproc.INPAINT_TELEA
            )
            Imgproc.cvtColor(restored, output, Imgproc.COLOR_RGB2RGBA)
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
            restored.release()
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
