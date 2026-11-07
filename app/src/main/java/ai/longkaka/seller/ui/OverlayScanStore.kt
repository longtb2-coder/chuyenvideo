package ai.longkaka.seller.ui

import android.content.Context
import android.net.Uri
import ai.longkaka.seller.translation.OnScreenText
import ai.longkaka.seller.translation.TextGlyphBox
import ai.longkaka.seller.translation.TextRegionObservation
import org.json.JSONArray
import org.json.JSONObject

/** Persists OCR wording, timing, glyph masks and motion geometry before video restoration. */
internal object OverlayScanCodec {
    fun encode(items: List<OnScreenText>): String {
        val tracks = JSONArray()
        items.forEach { item ->
            val observations = JSONArray()
            item.observations.forEach { observation ->
                val glyphs = JSONArray()
                observation.glyphs.forEach { glyph ->
                    val glyphJson = JSONObject()
                    glyphJson.put("left", glyph.left)
                    glyphJson.put("top", glyph.top)
                    glyphJson.put("right", glyph.right)
                    glyphJson.put("bottom", glyph.bottom)
                    glyphs.put(glyphJson)
                }

                val observationJson = JSONObject()
                observationJson.put("time", observation.timeMs)
                observationJson.put("left", observation.left)
                observationJson.put("top", observation.top)
                observationJson.put("right", observation.right)
                observationJson.put("bottom", observation.bottom)
                observationJson.put("glyphs", glyphs)
                observations.put(observationJson)
            }

            val trackJson = JSONObject()
            trackJson.put("source", item.sourceText)
            trackJson.put("translated", item.vietnameseText)
            trackJson.put("start", item.timeMs)
            trackJson.put("end", item.lastTimeMs)
            trackJson.put("left", item.left)
            trackJson.put("top", item.top)
            trackJson.put("right", item.right)
            trackJson.put("bottom", item.bottom)
            trackJson.put("observations", observations)
            tracks.put(trackJson)
        }
        return tracks.toString()
    }

    fun decode(raw: String): List<OnScreenText> = runCatching {
        val array = JSONArray(raw)
        List(array.length()) { index ->
            val item = requireNotNull(array.optJSONObject(index)) { "Invalid OCR track." }
            val observationsArray = item.optJSONArray("observations") ?: JSONArray()
            val observations = List(observationsArray.length()) { observationIndex ->
                val observation = requireNotNull(observationsArray.optJSONObject(observationIndex))
                val glyphArray = observation.optJSONArray("glyphs") ?: JSONArray()
                val glyphs = List(glyphArray.length()) { glyphIndex ->
                    val glyph = requireNotNull(glyphArray.optJSONObject(glyphIndex))
                    TextGlyphBox(
                        glyph.optDouble("left").toFloat(),
                        glyph.optDouble("top").toFloat(),
                        glyph.optDouble("right").toFloat(),
                        glyph.optDouble("bottom").toFloat()
                    )
                }
                TextRegionObservation(
                    observation.optLong("time"),
                    observation.optDouble("left").toFloat(),
                    observation.optDouble("top").toFloat(),
                    observation.optDouble("right").toFloat(),
                    observation.optDouble("bottom").toFloat(),
                    glyphs
                )
            }
            OnScreenText(
                item.optString("source"),
                item.optString("translated"),
                item.optLong("start"),
                item.optLong("end"),
                item.optDouble("left").toFloat(),
                item.optDouble("top").toFloat(),
                item.optDouble("right").toFloat(),
                item.optDouble("bottom").toFloat(),
                observations
            )
        }
    }.getOrDefault(emptyList())
}

internal class OverlayScanStore(context: Context) {
    private val directory = java.io.File(context.applicationContext.filesDir, "overlay_scans").apply {
        check(exists() || mkdirs()) { "Không tạo được thư mục lưu dữ liệu quét video." }
    }

    fun save(videoUri: Uri, overlays: List<OnScreenText>) {
        val destination = fileFor(videoUri)
        val temporary = java.io.File(directory, destination.name + ".part")
        try {
            temporary.writeText(OverlayScanCodec.encode(overlays), Charsets.UTF_8)
            if (destination.exists()) check(destination.delete()) { "Không thay thế được dữ liệu quét cũ." }
            check(temporary.renameTo(destination)) { "Không hoàn tất được dữ liệu quét video." }
        } finally {
            temporary.delete()
        }
    }

    fun load(videoUri: Uri): List<OnScreenText>? {
        val source = fileFor(videoUri)
        if (!source.isFile) return null
        return runCatching { OverlayScanCodec.decode(source.readText(Charsets.UTF_8)) }
            .getOrDefault(emptyList())
    }

    private fun fileFor(uri: Uri): java.io.File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return java.io.File(directory, "$digest.json")
    }
}