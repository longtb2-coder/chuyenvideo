package ai.longkaka.seller.sales

/**
 * Voice-first sales copy. Every factual claim must come from the seller or recognized video text.
 * The builder adapts the opening, argument order and pacing without fabricating product claims.
 */
data class SalesProductBrief(
    val productName: String = "",
    val audience: String = "",
    val problem: String = "",
    val benefits: String = "",
    val verifiedFacts: String = "",
    val priceAndOffer: String = "",
    val callToAction: String = ""
)

enum class SalesCopyStyle(val label: String) {
    FRIENDLY("Gần gũi, tự nhiên"),
    URGENT("Nhanh, tạo chú ý"),
    PREMIUM("Chuyên nghiệp, cao cấp"),
    REVIEW("Chân thực như trải nghiệm")
}

enum class SalesCopyLength(val seconds: Int, val label: String) {
    SHORT(15, "15 giây"),
    STANDARD(30, "30 giây"),
    DETAILED(45, "45 giây"),
    LONG(60, "60 giây")
}

data class SalesScript(val hook: String, val body: String, val close: String) {
    fun asText(): String = listOf(hook, body, close).filter { it.isNotBlank() }.joinToString("\n\n")
}

object SalesScriptBuilder {
    fun build(
        brief: SalesProductBrief,
        recognizedVideoText: List<String> = emptyList(),
        style: SalesCopyStyle = SalesCopyStyle.FRIENDLY,
        length: SalesCopyLength = SalesCopyLength.STANDARD
    ): SalesScript {
        val name = brief.productName.clean().ifBlank { "sản phẩm này" }
        val audience = brief.audience.clean()
        val problem = brief.problem.clean()
        val benefits = brief.benefits.clean()
        val facts = (listOf(brief.verifiedFacts) + recognizedVideoText)
            .map { it.clean() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
        val offer = brief.priceAndOffer.clean()
        val cta = brief.callToAction.clean().ifBlank {
            "Nhắn tin để được tư vấn thêm và kiểm tra giá, tình trạng hàng trước khi đặt mua."
        }

        val hook = when (style) {
            SalesCopyStyle.FRIENDLY -> when {
                problem.isNotBlank() -> "Bạn cũng đang gặp tình trạng $problem? Mình giới thiệu nhanh $name nhé."
                audience.isNotBlank() -> "Nếu bạn thuộc nhóm $audience, hãy dành ít phút xem thử $name."
                else -> "Có một sản phẩm đáng để bạn tham khảo hôm nay: $name."
            }
            SalesCopyStyle.URGENT -> when {
                problem.isNotBlank() -> "Đừng bỏ qua nếu bạn đang cần giải quyết chuyện $problem. Đây là $name."
                else -> "Đang tìm một lựa chọn phù hợp? Xem nhanh $name trước khi quyết định nhé."
            }
            SalesCopyStyle.PREMIUM -> "Cùng tìm hiểu $name qua những thông tin đáng chú ý và giá trị sử dụng thực tế."
            SalesCopyStyle.REVIEW -> "Mình cùng xem kỹ $name: điểm nào đáng chú ý, phù hợp với ai và cần lưu ý điều gì."
        }

        val points = buildList {
            if (audience.isNotBlank()) add("Sản phẩm hướng tới $audience.")
            if (problem.isNotBlank()) add("Nhu cầu cần quan tâm là $problem.")
            if (benefits.isNotBlank()) add(when (style) {
                SalesCopyStyle.REVIEW -> "Điểm mình muốn bạn chú ý: $benefits."
                SalesCopyStyle.PREMIUM -> "Giá trị nổi bật được cung cấp: $benefits."
                else -> "Điểm tiện lợi đáng cân nhắc: $benefits."
            })
            if (facts.isNotEmpty()) {
                add("Thông tin đã nhận diện hoặc được cung cấp: " + facts.joinToString("; ") + ".")
            } else {
                add("Mình chưa có thông số được xác minh, vì vậy bạn nên kiểm tra chất liệu, kích thước và xuất xứ trước khi đặt mua.")
            }
            if (offer.isNotBlank()) add("Thông tin giá và ưu đãi bạn cung cấp: $offer. Bạn nên xác nhận lại tình trạng áp dụng trước khi thanh toán.")
            else add("Giá bán và ưu đãi có thể thay đổi; hãy kiểm tra giá hiện tại tại nơi bán.")
        }

        // Short formats prioritize the strongest verified points; longer formats retain all context.
        val selectedPoints = when (length) {
            SalesCopyLength.SHORT -> points.take(3)
            SalesCopyLength.STANDARD -> points.take(4)
            SalesCopyLength.DETAILED, SalesCopyLength.LONG -> points
        }
        val objection = when (style) {
            SalesCopyStyle.REVIEW -> "Trước khi chọn, bạn hãy đối chiếu nhu cầu thực tế, thông số và chính sách đổi trả."
            SalesCopyStyle.PREMIUM -> "Hãy cân nhắc kỹ thông tin, chất lượng hoàn thiện và chính sách hậu mãi trước khi quyết định."
            else -> "Nếu còn phân vân về kích thước, cách dùng hoặc độ phù hợp, cứ hỏi để được tư vấn rõ hơn."
        }
        val body = (selectedPoints + if (length == SalesCopyLength.SHORT) emptyList() else listOf(objection))
            .joinToString("\n\n")
        val close = when (style) {
            SalesCopyStyle.URGENT -> "$cta Nếu thấy phù hợp, hãy nhắn tin ngay để kiểm tra thông tin mới nhất."
            SalesCopyStyle.PREMIUM -> "$cta Cảm ơn bạn đã dành thời gian tìm hiểu sản phẩm."
            else -> cta
        }
        return SalesScript(hook, body, close)
    }

    private fun String.clean(): String = trim().replace(Regex("\\s+"), " ")
}
