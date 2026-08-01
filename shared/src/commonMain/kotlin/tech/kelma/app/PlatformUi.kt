package tech.kelma.app

expect val isDesktopApp: Boolean

/** Opens the platform save UI and writes a UTF-8 Kelma deck export. Returns false when cancelled. */
expect fun saveDeckExportFile(suggestedName: String, content: String): Boolean
