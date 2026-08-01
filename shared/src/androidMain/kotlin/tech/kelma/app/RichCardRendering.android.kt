package tech.kelma.app

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

internal actual fun shouldUseRichReviewCard(): Boolean = true

@Composable
internal actual fun PlatformHtmlCardFace(
    html: String,
    updateScript: String,
    onPlayAudio: (String) -> Unit,
    onCardTap: (Float) -> Unit,
    modifier: Modifier,
) {
    AndroidView(
        factory = ::RichCardWebView,
        update = { webView -> webView.update(html, updateScript, onPlayAudio, onCardTap) },
        onRelease = RichCardWebView::release,
        modifier = modifier,
    )
}

private class RichCardWebView(context: Context) : WebView(context) {
    private var requestedHtml: String? = null
    private var requestedUpdateScript = ""
    private var displayedHtml: String? = null
    private var documentReady = false
    private var documentLoading = false
    private var onPlayAudio: (String) -> Unit = {}
    private var onCardTap: (Float) -> Unit = {}

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            builtInZoomControls = false
            displayZoomControls = false
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                when {
                    url.startsWith(AudioScheme) ->
                        this@RichCardWebView.onPlayAudio(Uri.decode(url.removePrefix(AudioScheme)))
                    url.startsWith(TapScheme) ->
                        url.removePrefix(TapScheme).toFloatOrNull()?.let(this@RichCardWebView.onCardTap)
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                documentLoading = false
                documentReady = true
                requestedHtml?.takeIf { it != displayedHtml }?.let { html ->
                    applyDocument(html, requestedUpdateScript)
                }
            }
        }
    }

    fun update(
        html: String,
        updateScript: String,
        onPlayAudio: (String) -> Unit,
        onCardTap: (Float) -> Unit,
    ) {
        this.onPlayAudio = onPlayAudio
        this.onCardTap = onCardTap
        requestedUpdateScript = updateScript
        if (requestedHtml == html) return
        requestedHtml = html
        when {
            documentReady -> applyDocument(html, updateScript)
            !documentLoading -> {
                documentLoading = true
                displayedHtml = html
                loadDataWithBaseURL(CardBaseUrl, html, "text/html", "UTF-8", null)
            }
        }
    }

    fun release() {
        requestedHtml = null
        displayedHtml = null
        documentReady = false
        stopLoading()
        loadUrl("about:blank")
        destroy()
    }

    private fun applyDocument(html: String, updateScript: String) {
        if (displayedHtml == html) return
        displayedHtml = html
        evaluateJavascript(updateScript, null)
    }
}

private const val CardBaseUrl = "https://card.kelma.invalid/"
private const val AudioScheme = "kelma-audio:"
private const val TapScheme = "kelma-tap:"
