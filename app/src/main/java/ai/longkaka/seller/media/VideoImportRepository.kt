package ai.longkaka.seller.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Free, on-device video intake. No account, paid API or remote backend is required.
 * Imported originals are copied into app-private storage; the user's selected source is untouched.
 */
class VideoImportRepository(private val context: Context) {
    companion object {
        private const val MAX_VIDEO_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MIN_FREE_BYTES = 64L * 1024L * 1024L
    }

    suspend fun importFromUri(
        uri: Uri,
        onProgress: (Int) -> Unit = {}
    ): ImportedVideo = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val nameAndSize = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                    name to size
                } else null
            } ?: (null to -1L)

        val mime = resolver.getType(uri).orEmpty().ifBlank { "video/*" }
        require(mime.startsWith("video/") || looksLikeVideo(nameAndSize.first.orEmpty())) {
            "Tệp đã chọn không được nhận diện là video."
        }
        if (nameAndSize.second > MAX_VIDEO_BYTES) throw IllegalArgumentException("Video vượt giới hạn 2 GB của bản nhập hiện tại.")
        ensureSpace(nameAndSize.second)

        val folder = File(context.filesDir, "managed_media").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val safeName = sanitize(nameAndSize.first ?: "video_$id.mp4")
        val target = File(folder, "${id}_$safeName")
        try {
            val total = nameAndSize.second
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_VIDEO_BYTES) throw IllegalArgumentException("Video vượt giới hạn 2 GB.")
                        output.write(buffer, 0, read)
                        if (total > 0) {
                            val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                        }
                    }
                    output.fd.sync()
                }
            } ?: throw IllegalStateException("Không thể đọc video từ vị trí đã chọn.")
            inspect(target, id, safeName, mime, ImportSource.DEVICE)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun importFromDirectLink(
        rawUrl: String,
        onProgress: (Int) -> Unit = {}
    ): ImportedVideo = withContext(Dispatchers.IO) {
        val parsed = URL(rawUrl.trim())
        require(parsed.protocol.equals("https", true)) { "Chỉ chấp nhận link HTTPS an toàn." }
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "LONGKAKA-SELLER-AI/0.1")
        }
        var target: File? = null
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("Máy chủ từ chối tải video (HTTP $code).")
            val mime = connection.contentType.orEmpty().substringBefore(';').trim()
            if (!mime.startsWith("video/", true)) {
                throw IllegalArgumentException("Link này không trả về tệp video trực tiếp. Hãy lưu video về máy rồi chọn tệp.")
            }
            val length = connection.contentLengthLong
            if (length > MAX_VIDEO_BYTES) throw IllegalArgumentException("Video vượt giới hạn 2 GB.")
            ensureSpace(length)
            val id = UUID.randomUUID().toString()
            val extension = when (mime.lowercase()) {
                "video/quicktime" -> "mov"
                "video/webm" -> "webm"
                "video/3gpp" -> "3gp"
                else -> "mp4"
            }
            val folder = File(context.filesDir, "managed_media").apply { mkdirs() }
            target = File(folder, "${id}_link.$extension")
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_VIDEO_BYTES) throw IllegalArgumentException("Video vượt giới hạn 2 GB.")
                        output.write(buffer, 0, read)
                        if (length > 0) {
                            val percent = ((copied * 100) / length).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                        }
                    }
                    output.fd.sync()
                }
            }
            inspect(target, id, target.name, mime, ImportSource.DIRECT_LINK)
        } catch (error: Throwable) {
            target?.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun inspect(file: File, id: String, name: String, mime: String, source: ImportSource): ImportedVideo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            require(duration > 0L && width > 0 && height > 0) { "Tệp không có luồng video hợp lệ hoặc định dạng chưa được điện thoại hỗ trợ." }
            return ImportedVideo(id, name, file.absolutePath, mime, file.length(), duration, width, height, source)
        } finally {
            retriever.release()
        }
    }

    private fun ensureSpace(expectedBytes: Long) {
        val folder = File(context.filesDir, "managed_media").apply { mkdirs() }
        val required = if (expectedBytes > 0) expectedBytes + MIN_FREE_BYTES else MIN_FREE_BYTES
        if (folder.usableSpace < required) throw IllegalStateException("Bộ nhớ trống không đủ. Hãy giải phóng dung lượng rồi nhập lại.")
    }

    private fun looksLikeVideo(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "m4v", "mkv", "webm", "3gp", "ts")

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "video.mp4" }
}
