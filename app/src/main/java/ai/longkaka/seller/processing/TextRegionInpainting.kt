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
 * The OCR rectangle is a search window only: detect high-contrast glyph strokes
 * inside it and inpaint those pixels instead of wiping the entire rectangle.
 * This is spatial repair, not temporal video restoration.
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

                val rect = org.opencv.core.Rect(x1, y1, width, height)
                val roi = gray.submat(rect)
                val maskRoi = mask.submat(rect)
                val glyphMask = Mat()
                val dilated = Mat()
                try {
                    var blockSize = minOf(31, minOf(width, height) / 2 * 2 - 1)
                    if (blockSize < 3) blockSize = 3
                    // Use one polarity only. OR-ing bright and dark thresholds would
                    // mark every pixel and erase the complete OCR rectangle.
                    val localMean = Core.mean(roi).\`val\`[0]
                    val polarity = if (localMean >= 127.5) Imgproc.THRESH_BINARY_INV
                        else Imgproc.THRESH_BINARY
                    Imgproc.adaptiveThreshold(roi, glyphMask, 255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, polarity, blockSize, 9.0)
                    Imgproc.dilate(glyphMask, dilated, kernel, Point(-1.0, -1.0), 1)
                    dilated.copyTo(maskRoi)
                } finally {
                    roi.release()
                    maskRoi.release()
                    glyphMask.release()
                    dilated.release()
                }
            }

            // Small radius limits smearing around fine product details.
            Photo.inpaint(input, mask, output, 2.0, Photo.INPAINT_TELEA)
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                .also { Utils.matToBitmap(output, it) }
        } finally {
            input.release()
            gray.release()
            mask.release()
            output.release()
            kernel.release()
        }
    }
}
