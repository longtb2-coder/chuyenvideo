package ai.longkaka.seller.sales

object SalesAnalysisSummaryBuilder {
    fun build(frameCount: Int, ocr: List<String>, translated: List<String>, transcript: String): String {
        val out = StringBuilder()
        out.appendLine("BẢN TỔNG HỢP NỘI DUNG VIDEO")
        out.appendLine("Ảnh cảnh quay: $frameCount")
        out.appendLine("CHỮ TRÊN HÌNH")
        if (ocr.isEmpty()) out.appendLine("Chưa nhận diện được chữ.")
        else ocr.map(String::trim).filter(String::isNotBlank).distinct().take(80).forEachIndexed { i, value ->
            val vi = translated.getOrNull(i).orEmpty()
            out.appendLine("${i + 1}. $value" + if (vi.isNotBlank() && vi != value) " → $vi" else "")
        }
        out.appendLine("LỜI THOẠI")
        out.appendLine(transcript.ifBlank { "Chưa nhận diện được lời thoại." }.take(12000))
        out.appendLine("AI CẦN ĐỐI CHIẾU ẢNH")
        out.appendLine("Xác định sản phẩm, chi tiết nhìn thấy, thao tác và diễn biến theo thứ tự ảnh.")
        out.appendLine("Tách dữ kiện chắc chắn, suy luận và thông tin còn thiếu; không tự bịa công dụng, giá hay thông số.")
        out.appendLine("Bản tổng hợp văn bản chưa tự kết luận nội dung hình ảnh; AI phải quan sát ảnh đính kèm.")
        return out.toString().trim()
    }
}
