package ru.liftzip.orders.app.feature.orders

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Runs JavaScript redirects without placing a browser in the order editor's view hierarchy. */
@SuppressLint("SetJavaScriptEnabled")
internal suspend fun resolveTaobaoInBackground(
    context: Context,
    shareText: String,
    loadPage: (WebView, String) -> Unit = WebView::loadUrl,
): String? = withContext(Dispatchers.Main) {
    val initial = TaobaoLinks.sharedUrl(shareText) ?: return@withContext null
    val webView = WebView(context)
    try {
        withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { result ->
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                webView.webViewClient = object : WebViewClient() {
                    private fun capture(url: String): Boolean {
                        val canonical = TaobaoLinks.sharedUrl(url)?.let(TaobaoLinks::canonicalUrl) ?: return false
                        if (result.isActive) result.resume(canonical)
                        return true
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        return (request.isForMainFrame && capture(url)) || TaobaoLinks.sharedUrl(url) == null
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        capture(url)
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame && result.isActive) result.resume(null)
                    }
                }
                loadPage(webView, initial.toString())
            }
        }
    } finally {
        webView.stopLoading()
        webView.destroy()
    }
}
