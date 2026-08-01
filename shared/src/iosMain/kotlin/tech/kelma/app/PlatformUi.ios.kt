package tech.kelma.app

actual val isDesktopApp: Boolean = false

actual fun saveDeckExportFile(suggestedName: String, content: String): Boolean = false
