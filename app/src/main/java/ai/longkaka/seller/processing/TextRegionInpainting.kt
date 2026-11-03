package ai.longkaka.seller.processing

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.photo.Photo
import org.opencv.imgproc.Imgproc

/**
 * Conservative single-frame text repair.
 *
 * Important: the OCR rectangle is only a search window. We do NOT erase the whole
 * rectangle (which would wipe product details/background and look blurred). Instead
 * we derive a glyph-like mask from local light/dark contrast, dilate it slightly to
 * include anti-aliased edges, then inpaint only those pixels.
 *
 * This is still spatial inpainting, not temporal video restoration. Complex textures,
 * large captions and moving backgrounds need neighboring-frame reconstruction.
 */
object TextRegionInpainting {
    data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun isAvailable(): Boolean = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)

    fun repairFrame(source: Bitmap, regions: List<Region>): Bitmap {
        require(regions.isNotEmpty()) { "Chưa chọn vùng chữ cần phục hồi." }
        check(isAvailable()) { "Không khởi tạo được OpenCV." }
        require(source.width > 0 && source.height > 0) { "Khung hình không hợp lệ." }

        val input = Mat()
        val gray = Mat()
        val mask = Mat.zeros(source.height, source.width, CvType.CV_8UC1)
        val output = Mat()
        val bright = Mat()
        val dark = Mat()
        val combined = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        try {
            Utils.bitmapToMat(source, input)
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_RGBA2GRAY)

            regions.forEach { region ->
                val left = minOf(region.left, region.right).coerceIn(0f, 1f)
                val right = maxOf(region.left, region.right).coerceIn(0f, 1f)
                val top = minOf(region.top, region.bottom).coerceIn(0f, 1f)
                val bottom = maxOf(region.top, region.bottom).coerceIn(0f, 1f)
                val x1 = (left * source.width).toInt().coerceIn(0, source.width - 1)
                val y1 = (top * source.height).toInt().coerceIn(0, source.height - 1)
                val x2 = (right * source.width).toInt().coerceIn(x1 + 1, source.width)
                val y2 = (bottom * source.height).toInt().coerceIn(y1 + 1, source.height)
                val width = x2 - x1
                val height = y2 - y1
                if (width < 3 || height < 3) return@forEach

                val roi = gray.submat(org.opencv.core.Rect(x1, y1, width, height))
                val brightRoi = bright.submat(org.opencv.core.Rect(x1, y1, width, height))
                val darkRoi = dark.submat(org.opencv.core.Rect(x1, y1, width, height))
                val combinedRoi = combined.submat(org.opencv.core.Rect(x1, y1, width, height))
                val maskRoi = mask.submat(org.opencv.core.Rect(x1, y1, width, height))
                try {
                    // Adaptive threshold in both polarities handles white and dark Chinese glyphs.
                    var blockSize = minOf(31, minOf(width, height) / 2 * 2 - 1)
                    if (blockSize < 3) blockSize = 3
                    Imgproc.adaptiveThreshold(roi, brightRoi, 255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, blockSize, 9.0)
                    Imgproc.adaptiveThreshold(roi, darkRoi, 255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, blockSize, 9.0)
                    Core.bitwise_or(brightRoi, darkRoi, combinedRoi)
                    Imgproc.dilate(combinedRoi, maskRoi, kernel, Point(-1.0, -1.0), 1)
                } finally {
                    roi.release()
                    brightRoi.release()
                    darkRoi.release()
                    combinedRoi.release()
                    maskRoi.release()
                }
            }

            // Keep the repair radius small: large radii tend to smear nearby product details.
            Photo.inpaint(input, mask, output, 2.0, Photo.INPAINT_TELEA)
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                .also { Utils.matToBitmap(output, it) }
        } finally {
            input.release()
            gray.release()
            mask.release()
            output.release()
            bright.release()
            dark.release()
            combined.release()
            kernel.release()
        }
    }
}
