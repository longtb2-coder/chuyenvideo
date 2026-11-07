package ai.longkaka.seller.speech.vieneu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VieNeuTextChunkerTest {
    @Test
    fun keepsEveryCaptionCueShortAndPreservesOriginalTextOrder() {
        val source = "Món này rất dễ làm. Cả nhà có thể dùng vào bữa sáng. Hương vị thơm ngon."
        val chunks = VieNeuTextChunker.split(source)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.text.length <= VieNeuTextChunker.MAX_CAPTION_CHARS })
        assertEquals(normalize(source), normalize(chunks.joinToString(" ") { it.text }))
    }

    @Test
    fun breaksLongSentencesAtClauseAndWordBoundaries() {
        val source = "Hãy thử món bánh thơm ngon này, cách làm rất đơn giản và nguyên liệu dễ tìm ngay trong căn bếp của gia đình bạn."
        val chunks = VieNeuTextChunker.split(source, maxChars = 36)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 36 })
        assertEquals(normalize(source), normalize(chunks.joinToString(" ") { it.text }))
    }

    @Test
    fun retainsSentenceAndClausePunctuationForNaturalPauses() {
        val chunks = VieNeuTextChunker.split(
            "Đây là phần mở đầu, và đây là phần tiếp theo quan trọng.",
            maxChars = 32
        )
        assertTrue(chunks.size > 1)
        assertTrue(chunks.first().text.endsWith(","))
        assertTrue(chunks.first().pauseAfterSeconds < chunks.last().pauseAfterSeconds)
        assertTrue(chunks.last().text.endsWith("."))
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), "")
}
