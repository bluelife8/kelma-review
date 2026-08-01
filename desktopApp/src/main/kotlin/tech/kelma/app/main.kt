package tech.kelma.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Color
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.UIManager

private val DesktopWindowBackground = Color(15, 16, 10)

fun main() {
    // Swing interop containers exist one frame before/after Compose content during browser
    // mounting. Make that native window layer match the application instead of AWT white.
    UIManager.put("Panel.background", DesktopWindowBackground)
    UIManager.put("RootPane.background", DesktopWindowBackground)
    UIManager.put("Viewport.background", DesktopWindowBackground)
    UIManager.put("ScrollPane.background", DesktopWindowBackground)

    // Initialize JavaFX and its WebKit engine before the application window is visible. The first
    // WebView otherwise exposes JavaFX's white bootstrap page while the native engine starts.
    warmUpDesktopRichCardRenderer()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(width = 1080.dp, height = 760.dp),
            title = "User 1 - Kelma Review",
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(900, 620)
                window.background = DesktopWindowBackground
                window.rootPane.background = DesktopWindowBackground
                window.rootPane.isOpaque = true
                window.layeredPane.background = DesktopWindowBackground
                window.layeredPane.isOpaque = true
                window.contentPane.background = DesktopWindowBackground
                (window.contentPane as? JComponent)?.isOpaque = true
            }
            App()
        }
    }
}
