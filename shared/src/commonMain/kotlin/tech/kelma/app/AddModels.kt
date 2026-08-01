package tech.kelma.app

import androidx.compose.ui.text.input.TextFieldValue

/** Editor state for a single note field, including its sticky (kept after adding) and preview toggles. */
data class AddFieldState(
    val name: String,
    val value: TextFieldValue = TextFieldValue(),
    val sticky: Boolean = false,
    val showPreview: Boolean = false,
)

/** Immutable snapshot the Add editor renders from. */
data class AddUiState(
    val notetypes: List<AddNotetype>,
    val notetype: AddNotetype,
    val notetypeLocked: Boolean = false,
    val deckNames: List<String>,
    val deckName: String,
    val fields: List<AddFieldState>,
    val focusedField: Int,
    val tags: List<String>,
    val tagInput: String,
    val saving: Boolean,
    val message: String?,
    val messageIsError: Boolean,
    val history: List<String>,
) {
    val focused: AddFieldState
        get() = fields.getOrElse(focusedField) { fields.first() }
}

/** Stable test tag for the editor field at [index]; front/back keep readable names. */
fun fieldTag(index: Int): String = when (index) {
    0 -> "add-front"
    1 -> "add-back"
    else -> "add-field-$index"
}

/** Callbacks the Add editor invokes; hoisted so desktop and mobile share the same behavior. */
class AddActions(
    val onSelectNotetype: (AddNotetype) -> Unit,
    val onDeckNameChange: (String) -> Unit,
    val onFieldChange: (Int, TextFieldValue) -> Unit,
    val onFocusField: (Int) -> Unit,
    val onToggleSticky: (Int) -> Unit,
    val onTogglePreview: (Int) -> Unit,
    val onClearFields: () -> Unit,
    val onAttachImage: () -> Unit = {},
    val onAttachAudio: () -> Unit = {},
    val onTagInputChange: (String) -> Unit,
    val onCommitTag: () -> Unit,
    val onRemoveTag: (String) -> Unit,
    val onSave: () -> Unit,
    val onBack: () -> Unit,
    val onSync: () -> Unit,
    val onOpenSync: () -> Unit = onSync,
    val onBrowse: () -> Unit = {},
    val onOptions: () -> Unit = {},
)
