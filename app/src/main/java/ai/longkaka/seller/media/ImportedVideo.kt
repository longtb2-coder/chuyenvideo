package ai.longkaka.seller.media

data class ImportedVideo(
    val id: String,
    val displayName: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val source: ImportSource
)

enum class ImportSource { DEVICE, DIRECT_LINK }
