package tech.kelma.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.layout.Pane
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopRichCardRendererAcceptanceTest {
    @Test
    fun retainedBrowserIsolatesConsecutiveCardsAndAnswerSides() {
        val panelClass = Class.forName("tech.kelma.app.DesktopBrowserCardPanel")
        val constructor = panelClass.getDeclaredConstructor().apply { isAccessible = true }
        val panel = AtomicReference<Any>()
        SwingUtilities.invokeAndWait { panel.set(constructor.newInstance()) }
        panelClass.getDeclaredMethod("awaitInitialShell").apply { isAccessible = true }.invoke(panel.get())
        panelClass.getDeclaredMethod("prepareForMount").apply { isAccessible = true }.invoke(panel.get())
        val update = panelClass.declaredMethods.single { it.name == "update" }.apply { isAccessible = true }
        val playedAudio = AtomicReference("")
        val onAudio: (String) -> Unit = playedAudio::set
        val noTap: (Float) -> Unit = {}

        val loading = panelClass.getDeclaredField("loadingPanel").apply { isAccessible = true }
            .get(panel.get()) as JPanel
        val first = "<script>window.firstCardState='stale';</script>first card"
        update.invoke(panel.get(), document(first), script(first), onAudio, noTap)
        assertTrue(awaitBodyText(panel.get(), "first card"))
        assertTrue(awaitScript(panel.get(), "window.firstCardState === 'stale'"))
        assertTrue(awaitLoadingHidden(loading))

        panelClass.getDeclaredMethod("prepareForMount").apply { isAccessible = true }.invoke(panel.get())
        assertTrue(awaitLoadingHidden(loading))
        update.invoke(panel.get(), document("second card"), script("second card"), onAudio, noTap)
        assertTrue(awaitBodyText(panel.get(), "second card"))
        assertTrue(awaitScript(panel.get(), "typeof window.firstCardState === 'undefined'"))
        assertTrue(awaitLoadingHidden(loading))

        val answer = "second card<hr id=\"answer\">second answer [sound:voice.mp3]"
        update.invoke(
            panel.get(),
            document(answer, answer = true, withAudio = true),
            script(answer, answer = true, withAudio = true),
            onAudio,
            noTap,
        )
        assertTrue(awaitBodyText(panel.get(), "second answer"))
        assertTrue(
            awaitScript(
                panel.get(),
                "document.querySelector('.kelma-audio-play').getBoundingClientRect().width >= 11",
            ),
        )
        assertTrue(audioTriangleIsPainted(panel.get()))
        assertTrue(awaitScript(panel.get(), "(document.querySelector('.kelma-audio').click(), true)"))
        assertTrue(awaitValue(playedAudio, "voice.mp3"))
        assertTrue(awaitLoadingHidden(loading))
    }

    private fun audioTriangleIsPainted(panel: Any): Boolean {
        val fxPanel = panel.javaClass.getDeclaredField("fxPanel").apply { isAccessible = true }
            .get(panel) as JFXPanel
        val painted = AtomicReference(false)
        val ready = CountDownLatch(1)
        Platform.runLater {
            painted.set(
                runCatching {
                    val webView = ((fxPanel.scene.root as Pane).children.single() as WebView)
                    val coordinates = webView.engine.executeScript(
                        "(function(){var r=document.querySelector('.kelma-audio').getBoundingClientRect();" +
                            "return [r.left,r.top,r.width,r.height].join(',');})()",
                    ).toString().split(',').map(String::toDouble)
                    val image = webView.snapshot(null, null)
                    val scaleX = image.width / webView.width
                    val scaleY = image.height / webView.height
                    val centerX = ((coordinates[0] + coordinates[2] / 2) * scaleX).toInt()
                    val centerY = ((coordinates[1] + coordinates[3] / 2) * scaleY).toInt()
                    var darkPixels = 0
                    for (y in centerY - 7..centerY + 7) {
                        for (x in centerX - 5..centerX + 6) {
                            val color = image.pixelReader.getColor(x, y)
                            if (color.red < 0.25 && color.green < 0.25 && color.blue < 0.25) darkPixels++
                        }
                    }
                    darkPixels >= 12
                }.getOrDefault(false),
            )
            ready.countDown()
        }
        ready.await(5, TimeUnit.SECONDS)
        return painted.get()
    }

    private fun awaitValue(value: AtomicReference<String>, expected: String): Boolean {
        repeat(100) {
            if (value.get() == expected) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun awaitLoadingHidden(loading: JPanel): Boolean {
        repeat(100) {
            val visible = AtomicReference(true)
            SwingUtilities.invokeAndWait { visible.set(loading.isVisible) }
            if (!visible.get()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun awaitBodyText(panel: Any, expected: String): Boolean =
        awaitScript(panel, "document.body.innerText.includes('${expected.replace("'", "\\'")}')")

    private fun awaitScript(panel: Any, script: String): Boolean {
        val engine = panel.javaClass.getDeclaredField("webEngine").apply { isAccessible = true }
            .get(panel) as WebEngine
        repeat(100) {
            val result = AtomicReference(false)
            val ready = CountDownLatch(1)
            Platform.runLater {
                result.set(runCatching { engine.executeScript(script) == true }.getOrDefault(false))
                ready.countDown()
            }
            ready.await(2, TimeUnit.SECONDS)
            if (result.get()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun document(
        text: String,
        answer: Boolean = false,
        withAudio: Boolean = false,
    ): String = buildCardHtmlDocument(
        CardHtmlFace(
            body = text,
            css = "",
            images = emptyList(),
            audio = if (withAudio) listOf(CardMedia("voice.mp3", byteArrayOf(1))) else emptyList(),
        ),
        desktop = true,
        scrollToAnswer = answer,
    )

    private fun script(
        text: String,
        answer: Boolean = false,
        withAudio: Boolean = false,
    ): String = cardDomUpdateScript(document(text, answer, withAudio))
}
