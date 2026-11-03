package ai.longkaka.seller.processing

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.photo.Photo
import org.opencv.imgproc.Imgproc

/**
 * Spatial inpainting for a single preview frame.
 *
 * This deliberately repairs only the selected text mask. It never blurs or filters the
 * full frame. Full-video temporal reconstruction is a separate renderer stage and must
 * not be represented as complete by this preview helper.
 */
object TextRegionInpainting {
    data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun isAvailable(): Boolean = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)

    fun repairFrame(source: Bitmap, regions: List<Region>): Bitmap {
        require(regions.isNotEmpty()) { "Chưa chọn vùng chữ cần phục hồi." }
        require(source.width > 0 && source.height > 0) { "Khung hình không hợp lệ." }
        check(isAvailable()) { "Không khởi tạo được OpenCV." }

        val rgba = Mat()
        val rgb = Mat()
        val mask = Mat.zeros(source.height, source.width, CvType.CV_8UC1)
        val repairedRgb = Mat()
        val repairedRgba = Mat()
        val alpha = Mat()
        try {
            Utils.bitmapToMat(source, rgba)
            require(rgba.channels() == 4 || rgba.channels() == 3) {
                "Định dạng màu khung hình không được hỗ trợ."
            }
            if (rgba.channels() == 4) {
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                Core.extractChannel(rgba, alpha, 3)
            } else {
                rgba.copyTo(rgb)
            }

            regions.forEach { region ->
                require(
                    region.left.isFinite() && region.top.isFinite() &&
                    region.right.isFinite() && region.bottom.isFinite() &&
                    region.left >= 0f && region.top >= 0f &&
                    region.right <= 1f && region.bottom <= 1f &&
                    region.right > region.left && region.bottom > region.top
                ) { "Vùng chữ được chọn không hợp lệ." }

                // Small local padding removes antialiased character edges without masking
                // a large part of the product image.
                val padX = (source.width * 0.003f).toInt().coerceAtLeast(2)
                val padY = (source.height * 0.003f).toInt().coerceAtLeast(2)
                val x1 = ((region.left * source.width).toInt() - padX).coerceIn(0, source.width - 1)
                val y1 = ((region.top * source.height).toInt() - padY).coerceIn(0, source.height - 1)
                val x2 = ((region.right * source.width).toInt() + padX).coerceIn(x1 + 1, source.width)
                val y2 = ((region.bottom * source.height).toInt() + padY).coerceIn(y1 + 1, source.height)
                Imgproc.rectangle(
                    mask,
                    Point(x1.toDouble(), y1.toDouble()),
                    Point((x2 - 1).toDouble(), (y2 - 1).toDouble()),
                    Scalar(255.0),
                    -1
                )
            }

            // OpenCV inpaint accepts 8-bit single- or three-channel source images.
            // Convert Android RGBA to RGB first, then restore the original alpha channel.
            Photo.inpaint(rgb, mask, repairedRgb, 3.0, Photo.INPAINT_TELEA)
            if (rgba.channels() == 4) {
                Imgproc.cvtColor(repairedRgb, repairedRgba, Imgproc.COLOR_RGB2RGBA)
                Core.insertChannel(alpha, repairedRgba, 3)
            } else {
                repairedRgb.copyTo(repairedRgba)
            }

            return Bitmap.createBitmap(
                source.width,
                source.height,
                Bitmap.Config.ARGB_8888
            ).also { Utils.matToBitmap(repairedRgba, it) }
        } finally {
            rgba.release()
            rgb.release()
            mask.release()
            repairedRgb.release()
            repairedRgba.release()
            alpha.release()
        }
    }
}
