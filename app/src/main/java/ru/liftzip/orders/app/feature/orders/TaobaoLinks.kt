package ru.liftzip.orders.app.feature.orders

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Parses a Taobao share message without trusting arbitrary URLs.
 * A short URL needs a separate network resolution step before it becomes permanent.
 */
internal object TaobaoLinks {
    private val urlPattern = Regex("""https://[^\s<>「」]+""", RegexOption.IGNORE_CASE)
    private val idPattern = Regex("""\d{5,20}""")

    fun sharedUrl(text: String): URI? {
        val raw = urlPattern.find(text)?.value?.trimEnd('。', '.', ',', '，', ')', '）') ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || !allowedHost(uri.host)) return null
        return uri
    }

    fun canonicalUrl(uri: URI): String? {
        if (uri.host?.lowercase(Locale.ROOT) !in setOf("item.taobao.com", "h5.m.taobao.com", "m.intl.taobao.com", "detail.tmall.com")) return null
        val id = uri.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
            val key = parameter.substringBefore('=')
            if (key != "id" && key != "itemId") return@mapNotNull null
            runCatching {
                URLDecoder.decode(parameter.substringAfter('=', ""), StandardCharsets.UTF_8.name())
            }.getOrNull()?.takeIf { idPattern.matches(it) }
        }.firstOrNull() ?: return null
        return "https://item.taobao.com/item.htm?id=$id"
    }

    private fun allowedHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        return h == "tb.cn" || h.endsWith(".tb.cn") ||
            h == "taobao.com" || h.endsWith(".taobao.com") ||
            h == "tmall.com" || h.endsWith(".tmall.com")
    }
}
