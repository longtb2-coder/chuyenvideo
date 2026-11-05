package ai.longkaka.seller.processing

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

object VideoFrameRepairPreview {
    fun create(context: Context, uri: Uri, timeMs: Long, regions: List<TextRegionInpainting.Region>): Bitmap {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val source = retriever.getFrameAtTime(timeMs.coerceAtLeast(0L) * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: error("Không trích xuất được khung hình tại vị trí chữ.")
            return try {
                TextRegionInpainting.repairFrame(source, regions)
            } finally {
                source.recycle()
            }
        } finally {
            retriever.release()
        }
    }
}
