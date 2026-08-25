package tech.kelma.app

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController(externalPluginsEnabled: Boolean = true) = ComposeUIViewController {
    App(externalPluginsEnabled = externalPluginsEnabled)
}
