package ai.longkaka.seller.speech.vieneu

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * Isolated downloader for the pinned VieNeu v3 Nano bundle.
 *
 * It never touches or initializes the existing Offline TTS engine.
 * Model assets and the SEA-G2P dictionary are stored in app-private storage.
 */
internal object VieNeuNanoModelStore {
    const val MODEL_REPO = "pnnbao-ump/VieNeu-TTS-v3-Nano"
    private const val MODEL_REVISION = "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc"
    private const val VOICE_ASSET_REVISION = "04044e1b8f822f946bafb2c88b716459f4164152"
    private const val SEA_G2P_SHA256 = "4346e690d0711ebc5231e7a42c5c88aaf6e40377e894b4617c018fd81c6f4096"

    private val requiredFiles = listOf(
        "text_encoder.onnx",
        "duration_predictor.onnx",
        "vector_estimator.onnx",
        "codec_decoder.onnx",
        "config.json",
        "constants.npz",
        "voices_v3_nano.json",
        "sea_g2p.bin"
    )

    fun modelDirectory(context: Context): File =
        File(context.filesDir, "LONGKAKA SELLER AI/models/vieneu-v3-nano")

    fun hasDownloadedFiles(context: Context): Boolean {
        val directory = modelDirectory(context)
        return requiredFiles.all { name ->
            File(directory, name).let { it.isFile && it.length() > 0L }
        }
    }

    /**
     * Downloads missing or invalid files sequentially.
     * Invoke from Dispatchers.IO / a worker thread, never the main thread.
     */
    fun ensureDownloaded(
        context: Context,
        onProgress: (fileName: String, fileIndex: Int, fileCount: Int, bytes: Long) -> Unit = { _, _, _, _ -> }
    ): File {
        val directory = modelDirectory(context)
        check(directory.exists() || directory.mkdirs()) { "Không tạo được thư mục lưu mô hình." }

        requiredFiles.forEachIndexed { index, name ->
            val destination = File(directory, name)
            val valid = destination.isFile && destination.length() > 0L &&
                (name != "sea_g2p.bin" || sha256(destination) == SEA_G2P_SHA256)
            if (!valid) {
                downloadAtomically(name, destination) { bytes ->
                    onProgress(name, index + 1, requiredFiles.size, bytes)
                }
            } else {
                onProgress(name, index + 1, requiredFiles.size, destination.length())
            }
        }
        validateBundle(directory)
        return directory
    }

    private fun sourceUrl(fileName: String): String = when (fileName) {
        "voices_v3_nano.json" ->
            "https://raw.githubusercontent.com/pnnbao97/VieNeu-TTS/$VOICE_ASSET_REVISION/src/vieneu/assets/voices_v3_nano.json"
        "sea_g2p.bin" ->
            "https://github.com/pnnbao97/sea-g2p/releases/download/v0.10.0/sea_g2p.bin"
        else ->
            "https://huggingface.co/$MODEL_REPO/resolve/$MODEL_REVISION/$fileName?download=true"
    }

    private fun downloadAtomically(
        fileName: String,
        destination: File,
        onBytes: (Long) -> Unit
    ) {
        val connection = (URL(sourceUrl(fileName)).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 600_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "LONGKAKA-SELLER-AI/0.1")
        }
        val partial = File(destination.parentFile, destination.name + ".part")
        try {
            val code = connection.responseCode
            check(code in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                "Không tải được $fileName (HTTP $code): ${detail.take(240)}"
            }
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        total += count
                        onBytes(total)
                    }
                    output.flush()
                }
            }
            check(partial.isFile && partial.length() > 0L) { "Tệp tải về rỗng: $fileName" }
            if (fileName == "sea_g2p.bin") {
                check(sha256(partial) == SEA_G2P_SHA256) { "Kiểm tra SHA-256 thất bại: $fileName" }
            }
            if (destination.exists()) check(destination.delete()) { "Không thay thế được $fileName." }
            check(partial.renameTo(destination)) { "Không hoàn tất lưu $fileName." }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun validateBundle(directory: File) {
        requiredFiles.forEach { name ->
            val file = File(directory, name)
            check(file.isFile && file.length() > 0L) { "Thiếu tệp mô hình: $name" }
        }
        JSONObject(File(directory, "config.json").readText(Charsets.UTF_8))
        val voices = JSONObject(File(directory, "voices_v3_nano.json").readText(Charsets.UTF_8))
        check(voices.optJSONObject("presets")?.length() == 11) {
            "Danh sách giọng VieNeu Nano không hợp lệ."
        }
        check(sha256(File(directory, "sea_g2p.bin")) == SEA_G2P_SHA256) {
            "Từ điển SEA-G2P không hợp lệ."
        }
        ZipFile(File(directory, "constants.npz")).use { archive ->
            check(archive.getEntry("null_spk.npy") != null && archive.getEntry("null_style.npy") != null) {
                "constants.npz thiếu dữ liệu điều kiện giọng."
            }
        }
    }
}
