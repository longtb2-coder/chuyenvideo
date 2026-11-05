package ai.longkaka.seller.speech.vieneu

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.ZipFile
import org.json.JSONObject

internal data class VieNeuNanoVoice(
    val name: String,
    val description: String,
    val speakerEmbedding: FloatArray,
    val style: FloatArray
)

internal data class VieNeuNanoModelConfig(
    val vocabulary: Map<Char, Long>,
    val emotionCharacters: Map<String, Char>,
    val bosId: Long,
    val eosId: Long,
    val padId: Long,
    val flowFps: Float,
    val defaultVoice: String
)

internal data class VieNeuNanoConstants(
    val nullSpeaker: FloatArray,
    val nullStyle: FloatArray
)

internal object VieNeuNanoAssets {
    fun loadConfig(directory: File): VieNeuNanoModelConfig {
        val json = JSONObject(File(directory, "config.json").readText(Charsets.UTF_8))
        val vocabJson = json.getJSONObject("vocab")
        val vocabulary = buildMap {
            val keys = vocabJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.length == 1) put(key[0], vocabJson.getLong(key))
            }
        }
        val emotionJson = json.optJSONObject("emotion_tags") ?: JSONObject()
        val emotions = buildMap {
            val keys = emotionJson.keys()
            while (keys.hasNext()) {
                val token = keys.next()
                val value = emotionJson.getString(token)
                if (value.length == 1) put(token, value[0])
            }
        }
        return VieNeuNanoModelConfig(
            vocabulary = vocabulary,
            emotionCharacters = emotions,
            bosId = json.getLong("bos_id"),
            eosId = json.getLong("eos_id"),
            padId = json.getLong("pad_id"),
            flowFps = json.optDouble("flow_fps", 15.625).toFloat(),
            defaultVoice = loadDefaultVoice(directory)
        )
    }

    fun loadVoices(directory: File): List<VieNeuNanoVoice> {
        val root = JSONObject(File(directory, "voices_v3_nano.json").readText(Charsets.UTF_8))
        val presets = root.getJSONObject("presets")
        val voices = ArrayList<VieNeuNanoVoice>(presets.length())
        val keys = presets.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val entry = presets.getJSONObject(name)
            val speaker = jsonArrayToFloats(entry.getJSONArray("speaker_emb"))
            val styleJson = entry.getJSONArray("style")
            val style = FloatArray(styleJson.length() * 256)
            var offset = 0
            for (row in 0 until styleJson.length()) {
                val values = styleJson.getJSONArray(row)
                require(values.length() == 256) { "Sai kích thước style của giọng $name." }
                for (column in 0 until values.length()) {
                    style[offset++] = values.getDouble(column).toFloat()
                }
            }
            require(speaker.size == 192) { "Sai kích thước speaker embedding của giọng $name." }
            require(style.size == 50 * 256) { "Sai kích thước style của giọng $name." }
            voices += VieNeuNanoVoice(
                name = name,
                description = entry.optString("description", ""),
                speakerEmbedding = speaker,
                style = style
            )
        }
        return voices
    }

    fun loadConstants(directory: File): VieNeuNanoConstants {
        ZipFile(File(directory, "constants.npz")).use { zip ->
            val nullSpeaker = readNpyFloatArray(zip, "null_spk.npy")
            val nullStyle = readNpyFloatArray(zip, "null_style.npy")
            require(nullSpeaker.size == 192) { "constants.npz: null_spk sai kích thước." }
            require(nullStyle.size == 50 * 256) { "constants.npz: null_style sai kích thước." }
            return VieNeuNanoConstants(nullSpeaker, nullStyle)
        }
    }

    private fun loadDefaultVoice(directory: File): String {
        val root = JSONObject(File(directory, "voices_v3_nano.json").readText(Charsets.UTF_8))
        return root.optString("default_voice", "Adam").ifBlank { "Adam" }
    }

    private fun jsonArrayToFloats(array: org.json.JSONArray): FloatArray =
        FloatArray(array.length()) { array.getDouble(it).toFloat() }

    private fun readNpyFloatArray(zip: ZipFile, entryName: String): FloatArray {
        val entry = zip.getEntry(entryName) ?: error("Thiếu $entryName trong constants.npz.")
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        require(bytes.size >= 10) { "$entryName không hợp lệ." }
        val magic = byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte())
        require(bytes.copyOfRange(0, 6).contentEquals(magic)) { "$entryName sai định dạng NPY." }

        val major = bytes[6].toInt() and 0xff
        val headerStart: Int
        val headerLength: Int
        when (major) {
            1 -> {
                headerStart = 10
                headerLength = (bytes[8].toInt() and 0xff) or ((bytes[9].toInt() and 0xff) shl 8)
            }
            2, 3 -> {
                require(bytes.size >= 12)
                headerStart = 12
                headerLength = ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }
            else -> error("Phiên bản NPY không hỗ trợ: $major")
        }
        require(headerLength > 0 && headerStart + headerLength <= bytes.size) { "$entryName có header lỗi." }
        val header = String(bytes, headerStart, headerLength, Charset.forName("US-ASCII"))
        require(Regex("""['"]descr['"]\s*:\s*['"]<f4['"]""").containsMatchIn(header) ||
                Regex("""['"]descr['"]\s*:\s*['"]=f4['"]""").containsMatchIn(header)) {
            "$entryName không phải mảng float32 little-endian."
        }
        require(Regex("""['"]fortran_order['"]\s*:\s*False""").containsMatchIn(header)) {
            "$entryName dùng thứ tự Fortran không hỗ trợ."
        }
        val dataOffset = headerStart + headerLength
        val dataBytes = bytes.size - dataOffset
        require(dataBytes > 0 && dataBytes % 4 == 0) { "$entryName thiếu dữ liệu float32." }
        val buffer = ByteBuffer.wrap(bytes, dataOffset, dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dataBytes / 4) { buffer.float }
    }
}
