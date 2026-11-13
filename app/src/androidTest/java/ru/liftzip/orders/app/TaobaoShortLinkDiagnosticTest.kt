package ru.liftzip.orders.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual, network-dependent investigation. Not part of CI.
 * Run with instrumentation argument taobaoDiagnostic=true on a device/emulator.
 * Does not access cookies, credentials, app data, or persist the response body.
 */
@RunWith(AndroidJUnit4::class)
class TaobaoShortLinkDiagnosticTest {
    @Test fun shortShareLinkResolvesToKnownItemId() {
        assumeTrue(
            "Manual network diagnostic only",
            InstrumentationRegistry.getArguments().getString("taobaoDiagnostic") == "true",
        )
        val expectedId = "45184215152"
        var current = URI("https://e.tb.cn/h.jPf9texwTwwAHxe?tk=ShmK6WCstGY")
        val seen = mutableSetOf<String>()

        repeat(8) { step ->
            require(current.scheme.equals("https", ignoreCase = true))
            require(isAllowedHost(current.host)) { "Blocked redirect host: ${current.host}" }
            check(seen.add(current.toString())) { "Redirect loop" }
            val connection = (URL(current.toString()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            try {
                val status = connection.responseCode
                val location = connection.getHeaderField("Location")
                val directId = extractItemId(current)
                android.util.Log.i("TaobaoDiagnostic", "step=$step host=${current.host} path=${current.path} status=$status itemId=${directId ?: "none"}")
                if (directId != null) {
                    assertEquals("Unexpected Taobao product", expectedId, directId)
                    return
                }
                if (status in 300..399 && !location.isNullOrBlank()) {
                    current = current.resolve(location)
                    return@repeat
                }
                val html = if (status in 200..299 && connection.contentType.orEmpty().lowercase(Locale.ROOT).contains("text/html")) {
                    connection.inputStream.bufferedReader().use { it.readText().take(256_000) }
                } else ""
                val embedded = Regex("""(?:item\.taobao\.com/item\.htm\?id=|[?&]itemId=)(\d{5,20})""")
                    .find(html)?.groupValues?.get(1)
                if (embedded != null) {
                    assertEquals("Unexpected embedded item ID", expectedId, embedded)
                    return
                }
                throw AssertionError("No verified product ID: HTTP $status at ${current.host}${current.path}; possible login/interstitial")
            } finally {
                connection.disconnect()
            }
        }
        throw AssertionError("Exceeded redirect limit; last host=${current.host}")
    }

    private fun extractItemId(uri: URI): String? {
        if (uri.host?.lowercase(Locale.ROOT) !in setOf("item.taobao.com", "h5.m.taobao.com", "m.intl.taobao.com")) return null
        return uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("id=") }
            ?.substringAfter('=')?.takeIf { it.matches(Regex("""\d{5,20}""")) }
    }

    private fun isAllowedHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT) ?: return false
        return h == "tb.cn" || h.endsWith(".tb.cn") ||
            h == "taobao.com" || h.endsWith(".taobao.com") ||
            h == "tmall.com" || h.endsWith(".tmall.com")
    }
}
