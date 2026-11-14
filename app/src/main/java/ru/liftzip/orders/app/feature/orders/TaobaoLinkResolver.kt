package ru.liftzip.orders.app.feature.orders

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves a Taobao share link without cookies, credentials, or WebView.
 * Redirects are handled manually to prevent requests to unrelated hosts.
 */
internal object TaobaoLinkResolver {
    suspend fun resolve(shareText: String): String? = withContext(Dispatchers.IO) {
        var current = TaobaoLinks.sharedUrl(shareText) ?: return@withContext null
        TaobaoLinks.canonicalUrl(current)?.let { return@withContext it }
        val visited = mutableSetOf<URI>()
        repeat(8) {
            if (!visited.add(current)) return@withContext null
            val connection = (URL(current.toString()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            try {
                val status = connection.responseCode
                val redirect = connection.getHeaderField("Location")
                if (status in 300..399 && !redirect.isNullOrBlank()) {
                    val next = current.resolve(redirect)
                    // sharedUrl checks HTTPS and the exact allowed domain suffixes.
                    current = TaobaoLinks.sharedUrl(next.toString()) ?: return@withContext null
                    TaobaoLinks.canonicalUrl(current)?.let { return@withContext it }
                } else if (status in 200..299 &&
                    connection.contentType.orEmpty().lowercase(Locale.ROOT).contains("text/html")) {
                    val html = connection.inputStream.bufferedReader().use { reader ->
                        buildString {
                            val buffer = CharArray(4096)
                            while (length < 256_000) {
                                val count = reader.read(buffer, 0, minOf(buffer.size, 256_000 - length))
                                if (count < 0) break
                                append(buffer, 0, count)
                            }
                        }
                    }
                    val embedded = Regex("""(?:item\.taobao\.com/item\.htm\?id=|[?&]itemId=)(\d{5,20})""")
                        .find(html)?.groupValues?.get(1)
                    return@withContext embedded?.let { "https://item.taobao.com/item.htm?id=$it" }
                } else return@withContext null
            } finally {
                connection.disconnect()
            }
        }
        null
    }
}
