package ai.longkaka.seller.translation

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot

data class OnScreenText(
    val sourceText: String,
    val vietnameseText: String,
    val timeMs: Long,
    val lastTimeMs: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Samples video frames, recognizes Chinese text and groups observations into separate
 * time/position tracks. Identical text in two different screen locations is not merged.
 * This stage does not itself erase pixels or render a replacement video.
 */
class ChineseOverlayTranslator(private val context: Context) {
    private data class Track(
        val normalizedText: String,
        var displayText: String,
        var firstTimeMs: Long,
        var lastTimeMs: Long,
        var left: Float,
        var top: Float,
        var right: Float,
        var bottom: Float
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    suspend fun scanAndTranslate(
        videoUri: Uri,
        sampleIntervalMs: Long = 1_500L,
        maxFrames: Int = 600,
        category: ProductCategory = ProductCategory.AUTO,
        onProgress: (Int) -> Unit = {}
    ): List<OnScreenText> {
        require(sampleIntervalMs >= 500L) { "Khoảng lấy mẫu phải từ 0,5 giây trở lên." }
        require(maxFrames > 0) { "Số khung hình quét phải lớn hơn 0." }

        val retriever = MediaMetadataRetriever()
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.VIETNAMESE)
                .build()
        )
        val tracks = mutableListOf<Track>()
        try {
            retriever.setDataSource(context, videoUri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            require(duration > 0L) { "Không đọc được thời lượng video." }
            val frameCount = minOf(
                ((duration + sampleIntervalMs - 1) / sampleIntervalMs).toInt().coerceAtLeast(1),
                maxFrames
            )
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()

            for (index in 0 until frameCount) {
                val timeMs = index * sampleIntervalMs
                val bitmap = retriever.getFrameAtTime(
                    timeMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (bitmap == null) {
                    onProgress(((index + 1) * 100 / frameCount).coerceIn(0, 100))
                    continue
                }
                try {
                    val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
                    result.textBlocks.forEach { block ->
                        val text = block.text.trim()
                        val containsChinese = text.any {
                            it.code in 0x3400..0x9FFF || it.code in 0xF900..0xFAFF
                        }
                        val box = block.boundingBox
                        if (containsChinese && box != null) {
                            val left = (box.left.toFloat() / bitmap.width).coerceIn(0f, 1f)
                            val top = (box.top.toFloat() / bitmap.height).coerceIn(0f, 1f)
                            val right = (box.right.toFloat() / bitmap.width).coerceIn(left, 1f)
                            val bottom = (box.bottom.toFloat() / bitmap.height).coerceIn(top, 1f)
                            val normalized = text.replace(Regex("\\s+"), "")
                            val cx = (left + right) / 2f
                            val cy = (top + bottom) / 2f

                            // Match only a recent observation at approximately the same location.
                            // A new scene or a second copy elsewhere becomes a separate track.
                            val candidate = tracks
                                .filter { it.normalizedText == normalized }
                                .filter { timeMs - it.lastTimeMs <= sampleIntervalMs * 3 }
                                .filter { hypot((it.centerX - cx).toDouble(), (it.centerY - cy).toDouble()) <= 0.13 }
                                .minByOrNull {
                                    hypot((it.centerX - cx).toDouble(), (it.centerY - cy).toDouble())
                                }

                            if (candidate == null) {
                                tracks += Track(
                                    normalizedText = normalized,
                                    displayText = text,
                                    firstTimeMs = timeMs,
                                    lastTimeMs = timeMs,
                                    left = left,
                                    top = top,
                                    right = right,
                                    bottom = bottom
                                )
                            } else {
                                candidate.lastTimeMs = timeMs
                                candidate.left = minOf(candidate.left, left)
                                candidate.top = minOf(candidate.top, top)
                                candidate.right = maxOf(candidate.right, right)
                                candidate.bottom = maxOf(candidate.bottom, bottom)
                                if (text.length > candidate.displayText.length) candidate.displayText = text
                            }
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                onProgress(((index + 1) * 100 / frameCount).coerceIn(0, 100))
            }

            return tracks.map { track ->
                val translated = translateForProduct(track.displayText, category) { phrase ->
                    translator.translate(phrase).awaitTask()
                }
                OnScreenText(
                    sourceText = track.displayText,
                    vietnameseText = translated,
                    timeMs = track.firstTimeMs,
                    lastTimeMs = track.lastTimeMs,
                    left = track.left,
                    top = track.top,
                    right = track.right,
                    bottom = track.bottom
                )
            }
        } finally {
            recognizer.close()
            translator.close()
            retriever.release()
        }
    }
}


private suspend fun translateForProduct(
    source: String,
    category: ProductCategory,
    fallback: suspend (String) -> String
): String {
    val normalized = source.replace(Regex("\\s+"), "").trim()
    val common = mapOf(
        "限时抢购" to "Ưu đãi trong thời gian có hạn",
        "买一送一" to "Mua 1 tặng 1",
        "包邮" to "Miễn phí vận chuyển",
        "正品保证" to "Cam kết hàng chính hãng",
        "新品上市" to "Sản phẩm mới ra mắt",
        "立即购买" to "Mua ngay",
        "售后无忧" to "Yên tâm với dịch vụ hậu mãi",
        "厂家直销" to "Bán trực tiếp từ nhà sản xuất",
        "库存有限" to "Số lượng có hạn",
        "热卖中" to "Đang bán chạy",
        "爆款" to "Sản phẩm bán chạy",
        "优惠价" to "Giá ưu đãi",
        "全场包邮" to "Miễn phí vận chuyển toàn bộ đơn hàng",
        "质量保证" to "Đảm bảo chất lượng"
    )
    val categoryTerms = when (category) {
        ProductCategory.CLOTHING -> mapOf(
            "显瘦" to "Tôn dáng, tạo cảm giác thon gọn", "修身" to "Ôm dáng",
            "宽松" to "Dáng rộng thoải mái", "亲肤" to "Mềm mại, thân thiện với da",
            "透气" to "Thoáng khí", "高腰" to "Cạp cao", "垂感" to "Độ rủ mềm mại",
            "不起球" to "Hạn chế xù lông", "不褪色" to "Hạn chế phai màu",
            "显腿长" to "Tạo hiệu ứng chân dài"
        )
        ProductCategory.COSMETICS -> mapOf(
            "补水" to "Cấp nước", "保湿" to "Dưỡng ẩm", "提亮" to "Làm sáng da",
            "控油" to "Kiểm soát dầu", "温和" to "Dịu nhẹ", "敏感肌" to "Da nhạy cảm",
            "细腻" to "Mịn màng", "持久" to "Lâu trôi, bền màu"
        )
        ProductCategory.HOME -> mapOf(
            "收纳" to "Lưu trữ, sắp xếp đồ đạc", "便携" to "Thiết kế tiện mang theo",
            "防滑" to "Chống trượt", "耐用" to "Bền chắc", "易清洗" to "Dễ vệ sinh",
            "承重" to "Khả năng chịu tải", "静音" to "Hoạt động êm ái"
        )
        ProductCategory.ELECTRONICS -> mapOf(
            "续航" to "Thời lượng pin", "快充" to "Sạc nhanh", "高清" to "Độ phân giải cao",
            "蓝牙" to "Bluetooth", "兼容" to "Tương thích", "功率" to "Công suất",
            "智能" to "Thông minh"
        )
        ProductCategory.SHOES_BAGS -> mapOf(
            "防水" to "Chống nước", "轻便" to "Nhẹ, tiện di chuyển", "耐磨" to "Chống mài mòn",
            "透气" to "Thoáng khí", "容量" to "Dung tích / sức chứa"
        )
        ProductCategory.FOOD -> mapOf(
            "口感" to "Hương vị và cảm giác khi thưởng thức", "新鲜" to "Tươi ngon",
            "配料" to "Thành phần", "保质期" to "Hạn sử dụng", "无添加" to "Không thêm phụ gia"
        )
        ProductCategory.ACCESSORIES -> mapOf(
            "轻便" to "Nhẹ, tiện mang theo", "防水" to "Chống nước",
            "耐磨" to "Chống mài mòn", "精致" to "Thiết kế tinh tế"
        )
        else -> emptyMap()
    }
    val exact = categoryTerms[normalized] ?: common[normalized]
    if (exact != null) return exact
    return fallback(source)
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        continuation.cancel()
    }
}
