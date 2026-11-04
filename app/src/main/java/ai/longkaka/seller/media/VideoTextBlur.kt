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

/** Samples and blurs source-frame patches densely enough for smooth subtitle replacement during export. */
internal object VideoTextBlur {
    data class Patch(val timeMs: Long, val bitmap: Bitmap)

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
        val retriever = MediaMetadataRetriever()
        val result = linkedMapOf<Int, MutableList<Patch>>()
        var total = 0
        val aggregateDurationMs = overlays.indices.sumOf { index ->
            val item = overlays[index]
            if (boxes.getOrNull(index) == null) 0L else (item.lastTimeMs - item.timeMs).coerceAtLeast(0L)
        }
        // Keep memory bounded while increasing sampling from the old 1-second cadence to 5 fps
        // for typical short clips. Longer projects automatically use a wider interval.
        val sampleIntervalMs = (((aggregateDurationMs + 599L) / 600L).coerceAtLeast(200L) + 99L) / 100L * 100L
        try {
            retriever.setDataSource(context, source)
            overlays.forEachIndexed { index, item ->
                val box = boxes.getOrNull(index) ?: return@forEachIndexed
                val patches = mutableListOf<Patch>()
                var t = item.timeMs.coerceAtLeast(0L)
                val end = item.lastTimeMs.coerceAtLeast(t)
                while (t <= end && total < 600) {
                    val observed = item.boundsAt(t)
                    val target = if (observed != null) {
                        val baseLeft = (item.left - .025f).coerceAtLeast(0f)
                        val baseTop = (item.top - .025f).coerceAtLeast(0f)
                        val baseWidth = (item.right - item.left + .05f).coerceAtLeast(.01f)
                        val baseHeight = (item.bottom - item.top + .05f).coerceAtLeast(.01f)
                        val left = (observed.left - .025f + box.left - baseLeft).coerceIn(0f, 1f)
                        val top = (observed.top - .025f + box.top - baseTop).coerceIn(0f, 1f)
                        val sx = (box.width / baseWidth).coerceIn(.5f, 2.5f)
                        val sy = (box.height / baseHeight).coerceIn(.5f, 2.5f)
                        RenderBox(left, top,
                            ((observed.right - observed.left + .05f) * sx).coerceAtMost(1f - left),
                            ((observed.bottom - observed.top + .05f) * sy).coerceAtMost(1f - top))
                    } else if (item.observations.isEmpty()) box else null
                    if (target != null && target.width > 0f && target.height > 0f) {
                        val frame = retriever.getFrameAtTime(t * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (frame != null) {
                            try {
                                val x = (target.left * frame.width).toInt().coerceIn(0, frame.width - 1)
                                val y = (target.top * frame.height).toInt().coerceIn(0, frame.height - 1)
                                val w = (target.width * frame.width).toInt().coerceAtLeast(1).coerceAtMost(frame.width - x)
                                val h = (target.height * frame.height).toInt().coerceAtLeast(1).coerceAtMost(frame.height - y)
                                val crop = Bitmap.createBitmap(frame, x, y, w, h)
                                patches += Patch(t, gaussianBlur(crop, strength))
                                crop.recycle()
                                total++
                            } finally {
                                frame.recycle()
                            }
                        }
                    }
                    t += sampleIntervalMs
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
