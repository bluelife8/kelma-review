package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore

internal actual fun shouldUseRichReviewCard(): Boolean = true

// Compose 1.11's replacement interop API does not yet expose WKWebView release handling.
@Suppress("DEPRECATION")
@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun PlatformHtmlCardFace(
    html: String,
    updateScript: String,
    onPlayAudio: (String) -> Unit,
    onCardTap: (Float) -> Unit,
    modifier: Modifier,
) {
    UIKitView(
        factory = { RichCardContainer() },
        update = { container -> container.update(html, updateScript, onPlayAudio, onCardTap) },
        onRelease = RichCardContainer::release,
        modifier = modifier,
    )
}

@OptIn(ExperimentalForeignApi::class)
private class RichCardContainer : UIView(CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val messageHandler = AudioMessageHandler()
    private val tapMessageHandler = CardTapMessageHandler()
    private val navigationBlocker = CardNavigationDelegate(::documentDidFinish)
    private val userContentController = WKUserContentController().apply {
        addScriptMessageHandler(messageHandler, AudioHandlerName)
        addScriptMessageHandler(tapMessageHandler, TapHandlerName)
    }
    private val webView = WKWebView(
        CGRectMake(0.0, 0.0, 0.0, 0.0),
        WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            userContentController = this@RichCardContainer.userContentController
        },
    ).apply {
        opaque = false
        backgroundColor = CardSurfaceColor
        underPageBackgroundColor = CardSurfaceColor
        scrollView.backgroundColor = CardSurfaceColor
        allowsLinkPreview = false
        navigationDelegate = navigationBlocker
    }
    private var requestedHtml: String? = null
    private var requestedUpdateScript = ""
    private var displayedHtml: String? = null
    private var documentReady = false
    private var documentLoading = false

    init {
        backgroundColor = CardSurfaceColor
        addSubview(webView)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        webView.setFrame(bounds)
    }

    fun update(
        html: String,
        updateScript: String,
        onPlayAudio: (String) -> Unit,
        onCardTap: (Float) -> Unit,
    ) {
        messageHandler.onPlayAudio = onPlayAudio
        tapMessageHandler.onCardTap = onCardTap
        requestedUpdateScript = updateScript
        if (requestedHtml == html) return
        requestedHtml = html
        when {
            documentReady -> applyDocument(html, updateScript)
            !documentLoading -> {
                documentLoading = true
                displayedHtml = html
                webView.loadHTMLString(html, null)
            }
        }
    }

    fun release() {
        requestedHtml = null
        displayedHtml = null
        documentReady = false
        webView.stopLoading()
        userContentController.removeScriptMessageHandlerForName(AudioHandlerName)
        userContentController.removeScriptMessageHandlerForName(TapHandlerName)
        webView.loadHTMLString("", null)
    }

    private fun documentDidFinish() {
        documentLoading = false
        documentReady = true
        requestedHtml?.takeIf { it != displayedHtml }?.let { html ->
            applyDocument(html, requestedUpdateScript)
        }
    }

    private fun applyDocument(html: String, updateScript: String) {
        if (displayedHtml == html) return
        displayedHtml = html
        webView.evaluateJavaScript(updateScript, completionHandler = null)
    }
}

private class CardNavigationDelegate(
    private val onDocumentFinished: () -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        didFinishNavigation: WKNavigation?,
    ) {
        onDocumentFinished()
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val scheme = decidePolicyForNavigationAction.request.URL?.scheme
        decisionHandler(
            if (scheme == null || scheme == "about") {
                WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            } else {
                WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            },
        )
    }
}

private class AudioMessageHandler : NSObject(), WKScriptMessageHandlerProtocol {
    var onPlayAudio: (String) -> Unit = {}

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val filename = didReceiveScriptMessage.body as? String ?: return
        onPlayAudio(filename)
    }
}

private class CardTapMessageHandler : NSObject(), WKScriptMessageHandlerProtocol {
    var onCardTap: (Float) -> Unit = {}

    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val fraction = (didReceiveScriptMessage.body as? NSNumber)?.floatValue ?: return
        onCardTap(fraction.coerceIn(0f, 1f))
    }
}

private val CardSurfaceColor = UIColor(
    red = 27.0 / 255.0,
    green = 29.0 / 255.0,
    blue = 22.0 / 255.0,
    alpha = 1.0,
)

private const val AudioHandlerName = "kelmaAudio"
private const val TapHandlerName = "kelmaCardTap"
