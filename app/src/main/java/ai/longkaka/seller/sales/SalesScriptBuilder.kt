package ai.longkaka.seller.sales

/**
 * Offline sales-copy builder. It only turns supplied/recognized facts into copy;
 * it never invents prices, certifications, performance figures or product claims.
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

data class SalesScript(
    val hook: String,
    val body: String,
    val close: String
) {
    fun asText(): String = listOf(hook, body, close).filter { it.isNotBlank() }.joinToString("\n\n")
}

object SalesScriptBuilder {
    fun build(brief: SalesProductBrief, recognizedVideoText: List<String> = emptyList()): SalesScript {
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
        val cta = brief.callToAction.clean().ifBlank { "Nhắn tin để được tư vấn và kiểm tra thông tin trước khi đặt mua." }

        val hook = when {
            problem.isNotBlank() -> "Bạn đang gặp tình trạng $problem? Hãy xem thử $name."
            audience.isNotBlank() -> "Nếu bạn là $audience, $name có thể là một lựa chọn đáng để tìm hiểu."
            else -> "Đang tìm hiểu một sản phẩm phù hợp? Cùng xem $name có những thông tin gì."
        }
        val body = buildList {
            if (benefits.isNotBlank()) add("Điểm đáng chú ý: $benefits.")
            if (facts.isNotEmpty()) {
                add("Thông tin đọc được từ video / do bạn cung cấp: " + facts.joinToString("; ") + ".")
            } else {
                add("Hiện chưa có thông số đã xác minh. Hãy bổ sung chất liệu, kích thước, công dụng hoặc xuất xứ trước khi đăng.")
            }
            if (offer.isNotBlank()) add("Giá và ưu đãi: $offer.")
            else add("Giá và chương trình ưu đãi: vui lòng kiểm tra tại nơi bán trước khi công bố.")
        }.joinToString("\n\n")
        return SalesScript(hook, body, cta)
    }

    private fun String.clean(): String = trim().replace(Regex("\\s+"), " ")
}
