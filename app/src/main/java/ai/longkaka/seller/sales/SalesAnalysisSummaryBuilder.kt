package ai.longkaka.seller.sales

/**
 * Organizes evidence from the video scan. Visual interpretation is performed by the
 * selected multimodal AI using the attached storyboard, never fabricated locally.
 */
object SalesAnalysisSummaryBuilder {
    fun build(frameCount: Int, ocr: List<String>, translated: List<String>, transcript: String): String {
        val entries = ocr.indices.map { index ->
            ocr[index].trim() to translated.getOrNull(index).orEmpty().trim()
        }.filter { it.first.isNotBlank() }.distinct()
        val out = StringBuilder()
        out.appendLine("BẢN TỔNG HỢP NỘI DUNG VIDEO")
        out.appendLine("Ảnh cảnh quay: $frameCount")
        out.appendLine()
        out.appendLine("1. CHỮ TRÊN HÌNH (theo thời điểm nhận diện)")
        if (entries.isEmpty()) out.appendLine("- Chưa nhận diện được chữ đủ tin cậy.")
        else entries.take(80).forEachIndexed { index, entry ->
            out.appendLine("- ${index + 1}. ${entry.first}" +
                if (entry.second.isNotBlank() && entry.second != entry.first) " → ${entry.second}" else "")
        }
        out.appendLine()
        out.appendLine("2. LỜI THOẠI / ÂM THANH")
        out.appendLine(transcript.trim().ifBlank { "Chưa nhận diện được lời thoại. Không được suy diễn nội dung âm thanh." }.take(12000))
        out.appendLine()
        out.appendLine("3. YÊU CẦU TỔNG HỢP TỪ ẢNH")
        out.appendLine("- Nhận diện tên/nhóm sản phẩm, hình dáng, bộ phận và phụ kiện nhìn thấy.")
        out.appendLine("- Sắp xếp thao tác và diễn biến theo thứ tự ảnh từ đầu đến cuối.")
        out.appendLine("- Nêu đối tượng và nhu cầu phù hợp; chỉ gắn lợi ích với thao tác có bằng chứng.")
        out.appendLine("- Tách dữ kiện chắc chắn, suy luận và thông tin còn thiếu.")
        out.appendLine()
        out.appendLine("GIỚI HẠN ĐỘ TIN CẬY")
        out.appendLine("- Văn bản này là dữ liệu OCR và lời thoại, chưa tự kết luận nội dung hình ảnh.")
        out.appendLine("- AI phải quan sát ảnh đính kèm trước khi xác định sản phẩm hoặc công dụng.")
        out.appendLine("- Không tự tạo giá, thông số, chất liệu, xuất xứ, chứng nhận hay công dụng chưa xác nhận.")
        return out.toString().trim()
    }
}
