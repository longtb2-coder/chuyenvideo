package ai.longkaka.seller.sales

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesScriptBuilderTest {
    @Test fun doesNotInventPriceOrSpecsWhenMissing() {
        val text = SalesScriptBuilder.build(SalesProductBrief(productName = "Áo khoác"))
            .asText()
        assertTrue(text.contains("chưa có thông số được xác minh"))
        assertTrue(text.contains("kiểm tra giá hiện tại tại nơi bán"))
        assertFalse(text.contains("299.000"))
    }

    @Test fun includesOnlyProvidedAndRecognizedFacts() {
        val text = SalesScriptBuilder.build(
            SalesProductBrief(productName = "Bình giữ nhiệt", benefits = "Dễ mang theo"),
            listOf("Dung tích 500 ml", "Inox 304")
        ).asText()
        assertTrue(text.contains("Dung tích 500 ml"))
        assertTrue(text.contains("Inox 304"))
        assertTrue(text.contains("Dễ mang theo"))
    }

    @Test fun adaptsToneAndLengthWithoutInventingFacts() {
        val brief = SalesProductBrief(
            productName = "Bình giữ nhiệt",
            benefits = "Dễ mang theo",
            verifiedFacts = "Dung tích 500 ml"
        )
        val friendly = SalesScriptBuilder.build(brief, style = SalesCopyStyle.FRIENDLY, length = SalesCopyLength.SHORT).asText()
        val premium = SalesScriptBuilder.build(brief, style = SalesCopyStyle.PREMIUM, length = SalesCopyLength.LONG).asText()
        assertFalse(friendly == premium)
        assertTrue(premium.contains("Dung tích 500 ml"))
        assertFalse(premium.contains("giảm 50%"))
    }

    @Test fun usesAudienceAndProblemInHook() {
        val text = SalesScriptBuilder.build(
            SalesProductBrief(productName = "Quạt mini", audience = "người thường xuyên di chuyển", problem = "nóng khi đi ngoài trời")
        ).hook
        assertTrue(text.contains("nóng khi đi ngoài trời"))
        assertTrue(text.contains("Quạt mini"))
    }
}
