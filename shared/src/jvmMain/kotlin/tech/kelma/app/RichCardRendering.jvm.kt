package tech.kelma.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color as ComposeColor
import java.awt.Color as AwtColor
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import netscape.javascript.JSObject

private var retainedDesktopBrowserPanel: DesktopBrowserCardPanel? = null

internal actual fun shouldUseRichReviewCard(): Boolean =
    !System.getProperty(DisableBrowserRendererProperty).toBoolean()

fun warmUpDesktopRichCardRenderer() {
    if (System.getProperty(DisableBrowserRendererProperty).toBoolean()) return

    lateinit var panel: DesktopBrowserCardPanel
    SwingUtilities.invokeAndWait {
        panel = retainedDesktopBrowserPanel ?: DesktopBrowserCardPanel().also {
            retainedDesktopBrowserPanel = it
        }
    }
    panel.awaitInitialShell()
}

@Composable
internal actual fun PlatformHtmlCardFace(
    html: String,
    updateScript: String,
    onPlayAudio: (String) -> Unit,
    onCardTap: (Float) -> Unit,
    modifier: Modifier,
) {
    if (System.getProperty(DisableBrowserRendererProperty).toBoolean()) {
        Text(
            renderInlineHtml(html.substringAfter(CardBodyStartMarker).substringBefore(CardBodyEndMarker)),
            modifier,
        )
        return
    }
    SwingPanel(
        factory = {
            val panel = retainedDesktopBrowserPanel ?: DesktopBrowserCardPanel().also {
                retainedDesktopBrowserPanel = it
            }
            panel.parent?.remove(panel)
            panel.prepareForMount()
            panel
        },
        update = { panel -> panel.update(html, updateScript, onPlayAudio, onCardTap) },
        background = DesktopSurfaceCompose,
        modifier = modifier,
    )
}

private class DesktopBrowserCardPanel : JLayeredPane() {
    private val callbacks = AtomicReference(CardCallbacks())
    private val initialShellReady = CountDownLatch(1)
    private val generation = AtomicInteger()
    private val fxPanel = JFXPanel().apply {
        isOpaque = true
        background = DesktopSurfaceAwt
        isFocusable = false
        focusTraversalKeysEnabled = false
    }
    private val loadingPanel = JPanel().apply {
        isOpaque = true
        background = DesktopSurfaceAwt
        isFocusable = false
    }
    private var webEngine: WebEngine? = null
    @Volatile private var requestedHtml: String? = null
    @Volatile private var shellReady = false
    @Volatile private var browserHasCard = false
    @Volatile private var activeLoadGeneration = 0
    private var bridge: DesktopCardBridge? = null

    init {
        isOpaque = true
        background = DesktopSurfaceAwt
        isFocusable = false
        focusTraversalKeysEnabled = false
        add(fxPanel, JLayeredPane.DEFAULT_LAYER)
        add(loadingPanel, JLayeredPane.PALETTE_LAYER)
        showLoadingCard()
        Platform.setImplicitExit(false)
        Platform.runLater {
            val browser = WebView().apply {
                isContextMenuEnabled = false
                isFocusTraversable = false
                style = "-fx-background-color: #0F100A; -fx-page-fill: #0F100A;"
            }
            val engine = browser.engine.apply {
                isJavaScriptEnabled = true
                userStyleSheetLocation = DesktopUserStyleSheet
                loadWorker.stateProperty().addListener { _, _, state ->
                    when (state) {
                        Worker.State.SUCCEEDED -> {
                            shellReady = true
                            initialShellReady.countDown()
                            val completedGeneration = activeLoadGeneration
                            val requested = requestedHtml
                            if (
                                completedGeneration != 0 &&
                                completedGeneration == generation.get() &&
                                requested != null
                            ) {
                                applyDesktopCardTextCompatibility(this)
                                installBridge(this)
                                browserHasCard = true
                                scheduleBrowserReveal(completedGeneration)
                            } else if (completedGeneration == 0 && requested != null) {
                                startCardLoad(this, requested, generation.get())
                            }
                        }
                        Worker.State.FAILED, Worker.State.CANCELLED -> initialShellReady.countDown()
                        else -> Unit
                    }
                }
                locationProperty().addListener { _, _, location ->
                    if (!isAllowedInternalLocation(location)) resetBlockedNavigation(this)
                }
            }
            webEngine = engine
            val root = StackPane(browser).apply {
                style = "-fx-background-color: #0F100A;"
            }
            fxPanel.scene = Scene(root).apply { fill = DesktopSurfaceFx }
            engine.loadUtf8Html(DesktopShellHtml)
        }
    }

    fun awaitInitialShell() {
        initialShellReady.await(15, TimeUnit.SECONDS)
    }

    fun prepareForMount() {
        generation.incrementAndGet()
        if (browserHasCard) {
            // SwingPanel may remount the retained browser between cards. Keep
            // the last complete frame visible until update() installs the next
            // card instead of covering the browser with an indefinite blank
            // loading panel.
            loadingPanel.isVisible = false
        } else {
            showLoadingCard()
        }
    }

    fun update(
        html: String,
        _updateScript: String,
        onPlayAudio: (String) -> Unit,
        onCardTap: (Float) -> Unit,
    ) {
        callbacks.set(CardCallbacks(onPlayAudio, onCardTap))
        val sameDocument = requestedHtml == html
        if (
            sameDocument &&
            (browserHasCard || activeLoadGeneration == generation.get())
        ) {
            Platform.runLater { webEngine?.let(::installBridge) }
            return
        }
        requestedHtml = html
        val requestedGeneration = if (sameDocument) {
            generation.get()
        } else {
            generation.incrementAndGet()
        }
        showLoadingCard()
        Platform.runLater {
            val engine = webEngine ?: return@runLater
            if (shellReady) startCardLoad(engine, html, requestedGeneration)
        }
    }

    override fun doLayout() {
        fxPanel.setBounds(0, 0, width, height)
        loadingPanel.setBounds(0, 0, width, height)
    }

    private fun startCardLoad(
        engine: WebEngine,
        html: String,
        requestedGeneration: Int,
    ) {
        if (requestedHtml != html || generation.get() != requestedGeneration) return
        activeLoadGeneration = requestedGeneration
        browserHasCard = false
        // A complete document load isolates every card side from scripts,
        // styles, and DOM mutations left by the previous card. The WebView is
        // retained, but its document is not.
        engine.loadUtf8Html(html)
    }

    private fun resetBlockedNavigation(engine: WebEngine) {
        shellReady = false
        browserHasCard = false
        activeLoadGeneration = 0
        generation.incrementAndGet()
        SwingUtilities.invokeLater { showLoadingCard() }
        Platform.runLater { engine.loadUtf8Html(DesktopShellHtml) }
    }

    private fun showLoadingCard() {
        loadingPanel.isVisible = true
        loadingPanel.repaint()
    }

    private fun scheduleBrowserReveal(completedGeneration: Int) {
        SwingUtilities.invokeLater {
            Timer(BrowserRevealDelayMillis) {
                if (generation.get() == completedGeneration) {
                    loadingPanel.isVisible = false
                    repaint()
                }
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    private fun installBridge(engine: WebEngine) {
        val host = DesktopCardBridge(callbacks)
        bridge = host
        (engine.executeScript("window") as? JSObject)?.setMember("kelmaHost", host)
    }
}

internal data class CardCallbacks(
    val onPlayAudio: (String) -> Unit = {},
    val onCardTap: (Float) -> Unit = {},
)

internal class DesktopCardBridge(
    private val callbacks: AtomicReference<CardCallbacks>,
) {
    @Suppress("unused")
    fun playAudio(filename: String) {
        SwingUtilities.invokeLater { callbacks.get().onPlayAudio(filename) }
    }

    @Suppress("unused")
    fun cardTap(fraction: Double) {
        SwingUtilities.invokeLater { callbacks.get().onCardTap(fraction.toFloat().coerceIn(0f, 1f)) }
    }
}

private fun WebEngine.loadUtf8Html(html: String) {
    // JavaFX 21 loadContent() corrupts supplementary Unicode code points while
    // crossing its native string boundary. Preserve the cheaper direct load
    // for BMP-only cards; otherwise let WebKit decode the original UTF-8 bytes
    // from an ASCII base64 data URL.
    if (html.none { it.isSurrogate() }) {
        loadContent(html, "text/html")
        return
    }
    val encoded = Base64.getEncoder().encodeToString(html.encodeToByteArray())
    load("data:text/html;charset=utf-8;base64,$encoded")
}

private fun isAllowedInternalLocation(location: String?): Boolean =
    location.isNullOrBlank() || location == "about:blank" || location.startsWith("data:text/html")

private val DesktopSurfaceCompose = ComposeColor(0xFF0F100A)
private val DesktopSurfaceAwt = AwtColor(15, 16, 10)
private val DesktopSurfaceFx = Color.rgb(15, 16, 10)
private const val DesktopUserStyleSheet =
    "data:text/css;base64,aHRtbCxib2R5e2JhY2tncm91bmQtY29sb3I6IzBGMTAwQSFpbXBvcnRhbnQ7Y29sb3I6I0Y0RjFFNyFpbXBvcnRhbnQ7fQ=="
private const val DesktopShellHtml = """<!doctype html>
<html><head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<style id="kelma-card-style">html,body{margin:0;min-height:100%;background:#0F100A;color:#F4F1E7}</style>
</head><body class="card" style="margin:0;background:#0F100A!important;color:#F4F1E7!important">
<script>document.addEventListener('click',function(e){var b=e.target.closest('[data-kelma-audio]');if(b){e.preventDefault();e.stopPropagation();var n=b.getAttribute('data-kelma-audio');if(window.kelmaHost)window.kelmaHost.playAudio(n);return;}var a=e.target.closest('a');if(a){e.preventDefault();return;}if(e.target.closest('button,input,textarea,select,[contenteditable=true]'))return;var x=Math.max(0,Math.min(1,e.clientX/Math.max(1,window.innerWidth)));if(window.kelmaHost)window.kelmaHost.cardTap(x);});</script>
</body></html>"""
private const val BrowserRevealDelayMillis = 50
private const val DisableBrowserRendererProperty = "tech.kelma.disable-native-card-renderer"
