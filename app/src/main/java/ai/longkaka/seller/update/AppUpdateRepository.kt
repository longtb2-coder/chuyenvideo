package ai.longkaka.seller.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(
    val tagName: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?
)

sealed interface UpdateCheckResult {
    data class Available(val release: AppRelease) : UpdateCheckResult
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data object NoRelease : UpdateCheckResult
}

class AppUpdateRepository(
    private val owner: String = "longtb2-coder",
    private val repository: String = "chuyenvideo"
) {
    fun check(currentVersion: String): UpdateCheckResult {
        val connection = (URL("https://api.github.com/repos/$owner/$repository/releases/latest").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "LongkakaSellerAI")
        }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return UpdateCheckResult.NoRelease
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Máy chủ cập nhật trả về mã ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name", "").removePrefix("v").trim()
            if (tag.isBlank()) return UpdateCheckResult.NoRelease
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apk = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                        if (apk != null) break
                    }
                }
            }
            val release = AppRelease(
                tagName = tag,
                title = json.optString("name", tag),
                notes = json.optString("body", "").take(2_000),
                pageUrl = json.optString("html_url", "https://github.com/$owner/$repository/releases").let {
                    if (it.startsWith("https://")) it else "https://github.com/$owner/$repository/releases"
                },
                apkUrl = apk
            )
            return if (isNewer(tag, currentVersion)) UpdateCheckResult.Available(release)
            else UpdateCheckResult.UpToDate(currentVersion)
        } finally {
            connection.disconnect()
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        fun parts(value: String) = value.removePrefix("v").split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}
