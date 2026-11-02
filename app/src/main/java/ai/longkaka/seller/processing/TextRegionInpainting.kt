package ai.longkaka.seller.processing

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.photo.Photo
import org.opencv.imgproc.Imgproc

object TextRegionInpainting {
    data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun isAvailable(): Boolean = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)

    fun repairFrame(source: Bitmap, regions: List<Region>): Bitmap {
        require(regions.isNotEmpty()) { "Chưa chọn vùng chữ cần phục hồi." }
        check(isAvailable()) { "Không khởi tạo được OpenCV." }
        val input = Mat()
        val mask = Mat.zeros(source.height, source.width, CvType.CV_8UC1)
        val output = Mat()
        try {
            Utils.bitmapToMat(source, input)
            regions.forEach { region ->
                val padX = (source.width * 0.012f).toInt().coerceAtLeast(2)
                val padY = (source.height * 0.012f).toInt().coerceAtLeast(2)
                val x1 = ((region.left * source.width).toInt() - padX).coerceIn(0, source.width - 1)
                val y1 = ((region.top * source.height).toInt() - padY).coerceIn(0, source.height - 1)
                val x2 = ((region.right * source.width).toInt() + padX).coerceIn(x1 + 1, source.width)
                val y2 = ((region.bottom * source.height).toInt() + padY).coerceIn(y1 + 1, source.height)
                Imgproc.rectangle(mask, Point(x1.toDouble(), y1.toDouble()), Point((x2 - 1).toDouble(), (y2 - 1).toDouble()), Scalar(255.0), -1)
            }
            Photo.inpaint(input, mask, output, 3.0, Photo.INPAINT_TELEA)
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(output, it) }
        } finally {
            input.release()
            mask.release()
            output.release()
        }
    }
}
