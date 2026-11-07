package ai.longkaka.seller.sales

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Extracts a small, evenly distributed visual storyboard for multimodal sales-script review.
 * Frames are downscaled and stored in cache; the imported source video is never modified.
 */
class SalesVideoFrameSampler(private val context: Context) {
    suspend fun createStoryboard(videoUri: Uri, frameLimit: Int = 8): List<Uri> =
        withContext(Dispatchers.IO) {
            require(frameLimit in 4..12) { "Số ảnh phân tích phải từ 4 đến 12." }
            val retriever = MediaMetadataRetriever()
            val outputDir = File(context.cacheDir, "sales_analysis").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                retriever.setDataSource(context, videoUri)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                require(durationMs > 0L) { "Không đọc được thời lượng video." }

                val fractions = (0 until frameLimit).map { index ->
                    if (frameLimit == 1) 0.5 else 0.04 + 0.92 * index / (frameLimit - 1)
                }
                fractions.mapIndexedNotNull { index, fraction ->
                    val timeUs = (durationMs * fraction * 1_000.0).toLong()
                    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: return@mapIndexedNotNull null
                    try {
                        val scale = minOf(1.0, 1280.0 / maxOf(frame.width, frame.height))
                        val width = (frame.width * scale).toInt().coerceAtLeast(1)
                        val height = (frame.height * scale).toInt().coerceAtLeast(1)
                        val resized = if (width == frame.width && height == frame.height) frame
                            else Bitmap.createScaledBitmap(frame, width, height, true)
                        try {
                            val file = File(outputDir, String.format(Locale.US, "scene_%02d.jpg", index + 1))
                            file.outputStream().use { stream ->
                                check(resized.compress(Bitmap.CompressFormat.JPEG, 84, stream)) {
                                    "Không thể lưu ảnh phân tích video."
                                }
                            }
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                        } finally {
                            if (resized !== frame) resized.recycle()
                        }
                    } finally {
                        frame.recycle()
                    }
                }
            } finally {
                retriever.release()
            }
        }
}
