package ai.longkaka.seller.media

/**
 * A video entry that references the original media URI; the app does not own or duplicate device-selected files.
 * contentUri is a MediaStore content:// URI, never a filesystem path.
 */
data class ImportedVideo(
    val id: String,
    val displayName: String,
    val contentUri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val source: ImportSource,
    val importedAt: Long = System.currentTimeMillis()
)

enum class ImportSource { DEVICE, DIRECT_LINK }
