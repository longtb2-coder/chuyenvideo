package ai.longkaka.seller.media

import java.net.URL

object DirectVideoLink {
    fun isSecureHttpUrl(value: String): Boolean = runCatching {
        val url = URL(value.trim())
        url.protocol.equals("https", ignoreCase = true) && !url.host.isNullOrBlank()
    }.getOrDefault(false)
}
