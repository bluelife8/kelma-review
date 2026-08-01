package tech.kelma.app

data class DeckManagementActions(
    val onAddCards: (String) -> Unit,
    val onBrowseCards: (String) -> Unit,
    val onOptions: (String) -> Unit,
    val onExport: (String) -> Unit,
    val onRename: suspend (oldName: String, newName: String) -> String?,
    val onDelete: suspend (String) -> String?,
)
