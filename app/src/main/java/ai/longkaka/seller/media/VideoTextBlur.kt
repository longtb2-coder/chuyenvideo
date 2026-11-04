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
import kotlin.math.max
import kotlin.math.min

/** Blur only OCR text pixels that intersect the user-selected region. */
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
        // Allocate samples across every OCR region. Do not let early regions consume a
        // global cap and leave the end of a long video using stale blur frames.
        val perRegionBudget = (1200 / active.size).coerceAtLeast(2)
        try {
            retriever.setDataSource(context, source)
            active.forEach { index ->
                val item = overlays[index]
                val selectedBox = boxes[index]
                val duration = (item.lastTimeMs - item.timeMs).coerceAtLeast(1L)
                val rawInterval = ceil(duration.toDouble() / perRegionBudget).toLong()
                val interval = (ceil(rawInterval / 100.0).toLong() * 100L).coerceAtLeast(100L)
                val patches = mutableListOf<Patch>()
                var t = item.timeMs.coerceAtLeast(0L)
                val end = item.lastTimeMs.coerceAtLeast(t)
                while (t <= end) {
                    val observed = item.boundsAt(t)
                    if (observed != null) {
                        val baseLeft = (item.left - .025f).coerceAtLeast(0f)
                        val baseTop = (item.top - .025f).coerceAtLeast(0f)
                        val baseWidth = (item.right - item.left + .05f).coerceAtLeast(.01f)
                        val baseHeight = (item.bottom - item.top + .05f).coerceAtLeast(.01f)
                        val sx = (selectedBox.width / baseWidth).coerceIn(.5f, 2.5f)
                        val sy = (selectedBox.height / baseHeight).coerceIn(.5f, 2.5f)
                        val regionLeft = (observed.left - .025f + selectedBox.left - baseLeft).coerceIn(0f, 1f)
                        val regionTop = (observed.top - .025f + selectedBox.top - baseTop).coerceIn(0f, 1f)
                        val region = RenderBox(
                            regionLeft,
                            regionTop,
                            ((observed.right - observed.left + .05f) * sx).coerceAtMost(1f - regionLeft),
                            ((observed.bottom - observed.top + .05f) * sy).coerceAtMost(1f - regionTop)
                        )
                        // The selected box is only a boundary. The actual blur patch is the
                        // detected text bounds (with a tiny safety margin), clipped to it.
                        val padX = .006f
                        val padY = .006f
                        val mappedTextLeft = region.left + (observed.left - baseLeft) * sx
                        val mappedTextTop = region.top + (observed.top - baseTop) * sy
                        val mappedTextRight = mappedTextLeft + (observed.right - observed.left) * sx
                        val mappedTextBottom = mappedTextTop + (observed.bottom - observed.top) * sy
                        val textLeft = max(region.left, mappedTextLeft - padX)
                        val textTop = max(region.top, mappedTextTop - padY)
                        val textRight = min(region.left + region.width, mappedTextRight + padX)
                        val textBottom = min(region.top + region.height, mappedTextBottom + padY)
                        if (textRight > textLeft && textBottom > textTop) {
                            val target = RenderBox(textLeft, textTop, textRight - textLeft, textBottom - textTop)
                            val frame = retriever.getFrameAtTime(t * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                            if (frame != null) {
                                try {
                                    val x = (target.left * frame.width).toInt().coerceIn(0, frame.width - 1)
                                    val y = (target.top * frame.height).toInt().coerceIn(0, frame.height - 1)
                                    val w = (target.width * frame.width).toInt().coerceAtLeast(1).coerceAtMost(frame.width - x)
                                    val h = (target.height * frame.height).toInt().coerceAtLeast(1).coerceAtMost(frame.height - y)
                                    val crop = Bitmap.createBitmap(frame, x, y, w, h)
                                    try {
                                        val actualBox = RenderBox(
                                            x.toFloat() / frame.width,
                                            y.toFloat() / frame.height,
                                            w.toFloat() / frame.width,
                                            h.toFloat() / frame.height
                                        )
                                        patches += Patch(t, actualBox, gaussianBlur(crop, strength))
                                    } finally {
                                        crop.recycle()
                                    }
                                } finally {
                                    frame.recycle()
                                }
                            }
                        }
                    }
                    if (end - t < interval) break
                    t += interval
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
