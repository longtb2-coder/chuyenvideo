package ai.longkaka.seller.speech.vieneu

/**
 * Splits narration into short, natural speech cues so the exact VieNeu audio timing
 * can drive readable subtitles instead of showing a long paragraph for up to 15 seconds.
 */
internal data class VieNeuTextChunk(val text: String, val pauseAfterSeconds: Float)

internal object VieNeuTextChunker {
    const val MAX_CAPTION_CHARS = 56

    fun split(source: String, maxChars: Int = MAX_CAPTION_CHARS): List<VieNeuTextChunk> {
        require(maxChars >= 16) { "Giới hạn cụm đọc quá ngắn." }
        val paragraphs = source.replace("\\r", "").split(Regex("\\n+")).filter { it.isNotBlank() }
        val result = mutableListOf<VieNeuTextChunk>()

        paragraphs.forEach { paragraph ->
            val sentences = paragraph.split(Regex("""(?<=[.!?…])\\s+""")).filter { it.isNotBlank() }
            var pending = StringBuilder()

            fun flushPending() {
                val value = pending.toString().trim()
                if (value.isNotEmpty()) {
                    result += VieNeuTextChunk(value, pauseFor(value))
                    pending = StringBuilder()
                }
            }

            sentences.forEach { rawSentence ->
                val sentence = rawSentence.trim()
                if (sentence.length <= maxChars) {
                    val addedLength = if (pending.isEmpty()) sentence.length else pending.length + 1 + sentence.length
                    if (pending.isNotEmpty() && addedLength > maxChars) flushPending()
                    if (pending.isNotEmpty()) pending.append(' ')
                    pending.append(sentence)
                } else {
                    flushPending()
                    splitLongSentence(sentence, maxChars).forEach { phrase ->
                        result += VieNeuTextChunk(phrase, pauseFor(phrase))
                    }
                }
            }

            flushPending()
            // Keep a natural paragraph break in the actual WAV timeline. The final
            // cue's pause is ignored by the writer, so this is safe at end of input too.
            if (result.isNotEmpty()) {
                val last = result.removeAt(result.lastIndex)
                result += last.copy(pauseAfterSeconds = maxOf(last.pauseAfterSeconds, 0.45f))
            }
        }
        return result
    }

    private fun splitLongSentence(sentence: String, maxChars: Int): List<String> {
        // Prefer punctuation/clause boundaries. Only break at word boundaries when a
        // single clause is still too long for a compact on-screen caption.
        val clauses = sentence.split(Regex("""(?<=[,，;；:：])\\s+""")).filter { it.isNotBlank() }
        val units = mutableListOf<String>()
        clauses.forEach { clause ->
            if (clause.length <= maxChars) {
                units += clause.trim()
            } else {
                units += splitAtWords(clause, maxChars)
            }
        }

        val packed = mutableListOf<String>()
        var current = StringBuilder()
        units.forEach { unit ->
            val length = if (current.isEmpty()) unit.length else current.length + 1 + unit.length
            if (current.isNotEmpty() && length > maxChars) {
                packed += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(unit)
        }
        if (current.isNotEmpty()) packed += current.toString()
        return packed
    }

    private fun splitAtWords(text: String, maxChars: Int): List<String> {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        text.trim().split(Regex("\\s+")).forEach { word ->
            val length = if (current.isEmpty()) word.length else current.length + 1 + word.length
            if (current.isNotEmpty() && length > maxChars) {
                parts += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    private fun pauseFor(text: String): Float = when (text.trimEnd().lastOrNull()) {
        '.', '!', '?', '…' -> 0.42f
        ';', '；', ':', '：' -> 0.28f
        ',', '，' -> 0.18f
        else -> 0.12f
    }
}
