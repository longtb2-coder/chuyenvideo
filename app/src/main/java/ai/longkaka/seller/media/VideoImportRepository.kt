package ai.longkaka.seller.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Imports videos into the phone's shared Movies/LONGKAKA SELLER AI folder. */
class VideoImportRepository(private val context: Context) {
    companion object {
        private const val MIN_FREE_BYTES = 128L * 1024L * 1024L
        private const val BUFFER_SIZE = 256 * 1024
        private const val PUBLIC_FOLDER = "Movies/LONGKAKA SELLER AI"
    }

    suspend fun importFromUri(uri: Uri, onProgress: (Int) -> Unit = {}): ImportedVideo =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = cursor.getColumnIndex(OpenableColumns.SIZE)
                        (if (ni >= 0) cursor.getString(ni) else null) to
                            (if (si >= 0 && !cursor.isNull(si)) cursor.getLong(si) else -1L)
                    } else null
                } ?: (null to -1L)

            val name = sanitize(metadata.first ?: "video_${UUID.randomUUID()}.mp4")
            val mime = resolver.getType(uri)?.takeIf { it.startsWith("video/") }
                ?: mimeFromName(name) ?: "video/mp4"
            ensureSpace(metadata.second)
            val destination = createDestination(name, mime)
            try {
                resolver.openInputStream(uri)?.use { input ->
                    resolver.openOutputStream(destination, "w")?.use { output ->
                        copyStream(input, output, metadata.second, onProgress)
                    } ?: throw IllegalStateException("Không mở được tệp đích trong bộ nhớ điện thoại.")
                } ?: throw IllegalStateException("Không thể đọc video từ vị trí đã chọn.")
                publish(destination)
                inspect(destination, name, mime, ImportSource.DEVICE)
            } catch (error: Throwable) {
                resolver.delete(destination, null, null)
                throw error
            }
        }

    suspend fun importFromDirectLink(rawUrl: String, onProgress: (Int) -> Unit = {}): ImportedVideo =
        withContext(Dispatchers.IO) {
            val parsed = URL(rawUrl.trim())
            require(parsed.protocol.equals("https", true) && parsed.host.isNotBlank()) {
                "Chỉ chấp nhận link HTTPS an toàn."
            }
            val connection = (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LONGKAKA-SELLER-AI/0.2")
            }
            var destination: Uri? = null
            try {
                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("Máy chủ từ chối tải video (HTTP $code).")
                val headerMime = connection.contentType.orEmpty().substringBefore(';').trim()
                val urlName = parsed.path.substringAfterLast('/').ifBlank { "video.mp4" }
                val extensionMime = mimeFromName(urlName)
                val mime = when {
                    headerMime.startsWith("video/", true) -> headerMime
                    extensionMime != null -> extensionMime
                    else -> throw IllegalArgumentException(
                        "Địa chỉ này không trả về tệp video trực tiếp. Hãy tải video hợp lệ về máy rồi chọn tệp."
                    )
                }
                val name = sanitize(urlName.substringBefore('?').ifBlank { "video_${UUID.randomUUID()}.${extensionFor(mime)}" })
                val length = connection.contentLengthLong
                ensureSpace(length)
                destination = createDestination(name, mime)
                connection.inputStream.use { input ->
                    context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                        copyStream(input, output, length, onProgress)
                    } ?: throw IllegalStateException("Không mở được tệp đích trong bộ nhớ điện thoại.")
                }
                publish(destination)
                inspect(destination, name, mime, ImportSource.DIRECT_LINK)
            } catch (error: Throwable) {
                destination?.let { context.contentResolver.delete(it, null, null) }
                throw error
            } finally {
                connection.disconnect()
            }
        }

    private fun createDestination(displayName: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, PUBLIC_FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Không tạo được tệp trong thư mục Movies của điện thoại.")
    }

    private fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    private fun copyStream(input: java.io.InputStream, output: java.io.OutputStream, totalBytes: Long, onProgress: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        var lastPercent = -1
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied += count
            ensureSpace(0L)
            output.write(buffer, 0, count)
            if (totalBytes > 0L) {
                val percent = ((copied * 100L) / totalBytes).toInt().coerceIn(0, 100)
                if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
            } else onProgress(-1)
        }
        output.flush()
        onProgress(100)
    }

    private fun inspect(uri: Uri, name: String, mime: String, source: ImportSource): ImportedVideo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            require(duration > 0L && width > 0 && height > 0) {
                "Tệp tải về không có luồng video hợp lệ hoặc định dạng chưa được điện thoại hỗ trợ."
            }
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            return ImportedVideo(UUID.randomUUID().toString(), name, uri.toString(), mime, size.coerceAtLeast(0L),
                duration, width, height, source)
        } finally { retriever.release() }
    }

    private fun ensureSpace(expectedBytes: Long) {
        val stats = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val required = (if (expectedBytes > 0L) expectedBytes else 0L) + MIN_FREE_BYTES
        if (stats.availableBytes < required) {
            throw IllegalStateException("Bộ nhớ điện thoại không đủ. Hãy giải phóng dung lượng rồi thử lại.")
        }
    }

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "video.mp4" }

    private fun mimeFromName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        "mkv" -> "video/x-matroska"
        "ts" -> "video/mp2t"
        else -> null
    }

    private fun extensionFor(mime: String): String = when (mime.lowercase()) {
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        "video/x-matroska" -> "mkv"
        else -> "mp4"
    }
}
