package ai.longkaka.seller.ui

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal data class SavedLayerBox(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

internal data class SavedEditorProject(
    val translations: List<SavedLayerBox>,
    val covers: List<SavedLayerBox>,
    val coverEnabled: List<Boolean>,
    val coverColor: Int,
    val coverAlpha: Float,
    val textSize: Float,
    val editedTexts: Map<Int, String>
)

internal class EditorProjectStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("editor_projects_v1", Context.MODE_PRIVATE)

    fun load(uri: Uri, count: Int, translationDefaults: List<SavedLayerBox>, coverDefaults: List<SavedLayerBox>): SavedEditorProject? {
        val raw = prefs.getString(key(uri), null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            fun boxes(name: String, fallback: List<SavedLayerBox>): List<SavedLayerBox> {
                val array = root.optJSONArray(name) ?: return fallback
                return List(count) { index ->
                    val obj = array.optJSONObject(index)
                    if (obj == null) fallback[index] else SavedLayerBox(
                        obj.optDouble("l", fallback[index].left.toDouble()).toFloat().coerceIn(0f, 1f),
                        obj.optDouble("t", fallback[index].top.toDouble()).toFloat().coerceIn(0f, 1f),
                        obj.optDouble("w", fallback[index].width.toDouble()).toFloat().coerceIn(.03f, 1f),
                        obj.optDouble("h", fallback[index].height.toDouble()).toFloat().coerceIn(.03f, 1f)
                    )
                }
            }
            val enabledArray = root.optJSONArray("enabled")
            val enabled = List(count) { enabledArray?.optBoolean(it, true) ?: true }
            val editsObject = root.optJSONObject("edits")
            val edits = buildMap {
                if (editsObject != null) {
                    val iterator = editsObject.keys()
                    while (iterator.hasNext()) {
                        val index = iterator.next().toIntOrNull() ?: continue
                        put(index, editsObject.optString(index.toString()))
                    }
                }
            }
            SavedEditorProject(
                boxes("translations", translationDefaults),
                boxes("covers", coverDefaults),
                enabled,
                root.optInt("color", 0xFF111111.toInt()),
                root.optDouble("alpha", 1.0).toFloat().coerceIn(0f, 1f),
                root.optDouble("textSize", 16.0).toFloat().coerceIn(8f, 48f),
                edits
            )
        }.getOrNull()
    }

    fun save(uri: Uri, project: SavedEditorProject) {
        fun boxArray(items: List<SavedLayerBox>) = JSONArray().apply {
            items.forEach { box ->
                put(JSONObject().put("l", box.left).put("t", box.top).put("w", box.width).put("h", box.height))
            }
        }
        val enabled = JSONArray().apply { project.coverEnabled.forEach { put(it) } }
        val edits = JSONObject().apply { project.editedTexts.forEach { (index, value) -> put(index.toString(), value) } }
        val json = JSONObject()
            .put("translations", boxArray(project.translations))
            .put("covers", boxArray(project.covers))
            .put("enabled", enabled)
            .put("color", project.coverColor)
            .put("alpha", project.coverAlpha)
            .put("textSize", project.textSize)
            .put("edits", edits)
        prefs.edit().putString(key(uri), json.toString()).apply()
    }

    private fun key(uri: Uri): String = "video_" + uri.toString().hashCode().toUInt().toString(16)
}
