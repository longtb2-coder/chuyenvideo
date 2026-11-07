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
    fun encode(items: List<OnScreenText>): String = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject()
                .put("source", item.sourceText)
                .put("translated", item.vietnameseText)
                .put("start", item.timeMs)
                .put("end", item.lastTimeMs)
                .put("left", item.left)
                .put("top", item.top)
                .put("right", item.right)
                .put("bottom", item.bottom)
                .put("observations", JSONArray().apply {
                    item.observations.forEach { observation ->
                        put(JSONObject()
                            .put("time", observation.timeMs)
                            .put("left", observation.left)
                            .put("top", observation.top)
                            .put("right", observation.right)
                            .put("bottom", observation.bottom)
                            .put("glyphs", JSONArray().apply {
                                observation.glyphs.forEach { glyph ->
                                    put(JSONObject()
                                        .put("left", glyph.left)
                                        .put("top", glyph.top)
                                        .put("right", glyph.right)
                                        .put("bottom", glyph.bottom))
                                }
                            })
                    }
                })
        }
    }.toString()

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
    private val preferences = context.applicationContext
        .getSharedPreferences("overlay_scan_metadata_v1", Context.MODE_PRIVATE)

    fun save(videoUri: Uri, overlays: List<OnScreenText>) {
        preferences.edit().putString(key(videoUri), OverlayScanCodec.encode(overlays)).apply()
    }

    fun load(videoUri: Uri): List<OnScreenText>? {
        val raw = preferences.getString(key(videoUri), null) ?: return null
        return OverlayScanCodec.decode(raw)
    }

    private fun key(uri: Uri): String =
        "video_" + uri.toString().hashCode().toUInt().toString(16)
}
