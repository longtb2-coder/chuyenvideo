package ai.longkaka.seller.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import ai.longkaka.seller.translation.OnScreenText
import ai.longkaka.seller.translation.boundsAt
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil

/**
 * Builds blurred patches for the complete user-selected subtitle region, not only
 * the glyph pixels. This guarantees the replacement caption has a clean surface.
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
                val duration = (item.lastTimeMs - item.timeMs).coerceAtLeast(1L)
                val rawInterval = ceil(duration.toDouble() / perRegionBudget).toLong()
                val interval = (ceil(rawInterval / 100.0).toLong() * 100L).coerceAtLeast(100L)
                val patches = mutableListOf<Patch>()
                var time = item.timeMs.coerceAtLeast(0L)
                val end = item.lastTimeMs.coerceAtLeast(time)
                while (time <= end) {
                    val observed = item.boundsAt(time)
                    if (observed != null) {
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
                    if (end - time < interval) break
                    time += interval
                }
                if (patches.isNotEmpty()) result[index] = patches
            }
        } finally {
            retriever.release()
        }
        return result
    }

    private fun gaussianBlur(source: Bitmap, strength: Float): Bitmap {
        val input = Mat()
        val output = Mat()
        try {
            Utils.bitmapToMat(source, input)
            val sigma = strength.coerceIn(2f, 28f).toDouble()
            Imgproc.GaussianBlur(input, output, Size(0.0, 0.0), sigma, sigma)
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                .also { Utils.matToBitmap(output, it) }
        } finally {
            input.release()
            output.release()
        }
    }

    fun release(samples: Map<Int, List<Patch>>) {
        samples.values.flatten().forEach { patch ->
            if (!patch.bitmap.isRecycled) patch.bitmap.recycle()
        }
    }
}
