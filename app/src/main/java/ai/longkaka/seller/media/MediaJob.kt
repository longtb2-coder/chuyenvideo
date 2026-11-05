package ai.longkaka.seller.media

enum class MediaStage {
    IMPORTED, PROBING, TRANSCRIBING, TRANSLATING, SUBTITLING, VOICEOVER, COMPOSING, EXPORTING, COMPLETED, FAILED
}

data class MediaJob(
    val id: String,
    val inputUri: String,
    val stage: MediaStage = MediaStage.IMPORTED,
    val progressPercent: Int = 0,
    val lastError: String? = null
) {
    init { require(progressPercent in 0..100) }
}
