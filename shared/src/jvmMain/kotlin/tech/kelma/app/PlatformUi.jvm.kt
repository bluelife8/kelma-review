package tech.kelma.app

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual val isDesktopApp: Boolean = true

actual fun saveDeckExportFile(suggestedName: String, content: String): Boolean {
    val dialog = FileDialog(null as Frame?, "Export Deck", FileDialog.SAVE).apply {
        file = suggestedName
        isVisible = true
    }
    val selectedName = dialog.file ?: return false
    File(dialog.directory, selectedName).writeText(content, Charsets.UTF_8)
    return true
}
