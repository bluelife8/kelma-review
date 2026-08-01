package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch

private val TagSeparators = Regex("[,\\s]+")

@Composable
fun AddScreen(
    deckNames: List<String>,
    syncing: Boolean = false,
    initialDeckName: String? = null,
    editing: EditNoteTarget? = null,
    onBack: () -> Unit,
    onSync: () -> Unit = {},
    onOpenSync: () -> Unit = onSync,
    onBrowse: () -> Unit = {},
    onOptions: () -> Unit = {},
    mediaPicker: MediaPicker? = null,
    onAttach: suspend (PickedMediaFile) -> String = { it.filename },
    onSave: suspend (AddNoteDraft) -> String?,
) {
    var notetype by remember { mutableStateOf(editing?.let { NotetypeCatalog.forId(it.notetypeId) } ?: NotetypeCatalog.basic) }
    var deckName by remember {
        mutableStateOf(
            editing?.deckName ?: initialDeckName?.takeIf { requested ->
                deckNames.any { it.equals(requested, ignoreCase = true) }
            } ?: deckNames.firstOrNull().orEmpty(),
        )
    }
    var fields by remember {
        mutableStateOf(
            notetype.fieldNames.mapIndexed { index, name ->
                val initial = when (index) {
                    0 -> editing?.front.orEmpty()
                    1 -> editing?.back.orEmpty()
                    else -> ""
                }
                AddFieldState(name, TextFieldValue(initial))
            },
        )
    }
    var focusedField by remember { mutableStateOf(0) }
    var tags by remember { mutableStateOf(editing?.tags ?: emptyList()) }
    var tagInput by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<String>()) }
    val scope = rememberCoroutineScope()
    val platformMediaPicker = rememberMediaPicker()
    val attachmentPicker = mediaPicker ?: platformMediaPicker

    fun parseTags(raw: String): List<String> =
        raw.split(TagSeparators).map(String::trim).filter(String::isNotEmpty)

    fun commitTags(): List<String> {
        val merged = (tags + parseTags(tagInput)).distinct()
        tags = merged
        tagInput = ""
        return merged
    }

    fun attach(kind: AttachmentKind) {
        if (saving) return
        scope.launch {
            try {
                val picked = attachmentPicker.pick(kind) ?: return@launch
                val filename = normalizeMediaFilename(picked.filename)
                val normalized = picked.copy(filename = filename)
                val savedFilename = onAttach(normalized)
                val field = fields.getOrNull(focusedField) ?: return@launch
                val marker = if (kind == AttachmentKind.Image) {
                    "<img src=\"${savedFilename.asHtmlAttribute()}\">"
                } else {
                    "[sound:$savedFilename]"
                }
                val start = field.value.selection.min
                val end = field.value.selection.max
                val updatedText = field.value.text.replaceRange(start, end, marker)
                val updated = TextFieldValue(updatedText, androidx.compose.ui.text.TextRange(start + marker.length))
                fields = fields.mapIndexed { index, value ->
                    if (index == focusedField) value.copy(value = updated) else value
                }
                message = "Attached $savedFilename"
                messageIsError = false
            } catch (exception: Exception) {
                message = exception.message ?: "Could not attach media"
                messageIsError = true
            }
        }
    }

    val save = {
        if (!saving) {
            val front = fields.getOrNull(0)?.value?.text?.trim().orEmpty()
            val back = fields.getOrNull(1)?.value?.text?.trim().orEmpty()
            val allTags = (tags + parseTags(tagInput)).distinct()
            val validation = when {
                deckName.isBlank() -> "Choose or enter a deck"
                front.isBlank() -> "Add a front for the card"
                back.isBlank() -> "Add a back for the card"
                else -> null
            }
            if (validation != null) {
                message = validation
                messageIsError = true
            } else {
                saving = true
                message = null
                scope.launch {
                    val error = onSave(
                        AddNoteDraft(deckName.trim(), front, back, allTags, notetype.id, notetype.cardOrds),
                    )
                    saving = false
                    if (error == null) {
                        if (editing != null) {
                            message = "Note updated"
                            tags = allTags
                            tagInput = ""
                        } else {
                            val count = notetype.cardOrds.size
                            message = if (count > 1) "Added $count cards to ${deckName.trim()}" else "Added to ${deckName.trim()}"
                            history = (listOf("$front  ·  ${deckName.trim()}") + history).take(20)
                            tags = allTags
                            tagInput = ""
                            fields = fields.map { if (it.sticky) it else it.copy(value = TextFieldValue()) }
                            focusedField = 0
                        }
                        messageIsError = false
                    } else {
                        message = error
                        messageIsError = true
                    }
                }
            }
        }
    }

    val state = AddUiState(
        notetypes = NotetypeCatalog.builtIns,
        notetype = notetype,
        notetypeLocked = editing != null,
        deckNames = deckNames,
        deckName = deckName,
        fields = fields,
        focusedField = focusedField,
        tags = tags,
        tagInput = tagInput,
        saving = saving,
        message = message,
        messageIsError = messageIsError,
        history = history,
    )
    val actions = AddActions(
        onSelectNotetype = onSelectNotetype@{ selected ->
            if (editing != null) return@onSelectNotetype
            if (selected.fieldNames != fields.map(AddFieldState::name)) {
                fields = selected.fieldNames.mapIndexed { index, name ->
                    val existing = fields.getOrNull(index)
                    AddFieldState(name, existing?.value ?: TextFieldValue(), existing?.sticky ?: false)
                }
                focusedField = 0
            }
            notetype = selected
            message = null
        },
        onDeckNameChange = { deckName = it; message = null },
        onFieldChange = { index, value ->
            fields = fields.mapIndexed { i, field -> if (i == index) field.copy(value = value) else field }
            message = null
        },
        onFocusField = { focusedField = it },
        onToggleSticky = { index ->
            fields = fields.mapIndexed { i, field -> if (i == index) field.copy(sticky = !field.sticky) else field }
        },
        onTogglePreview = { index ->
            fields = fields.mapIndexed { i, field -> if (i == index) field.copy(showPreview = !field.showPreview) else field }
        },
        onClearFields = {
            fields = fields.map { it.copy(value = TextFieldValue()) }
            tags = emptyList()
            tagInput = ""
            message = null
        },
        onAttachImage = { attach(AttachmentKind.Image) },
        onAttachAudio = { attach(AttachmentKind.Audio) },
        onTagInputChange = { tagInput = it },
        onCommitTag = { commitTags() },
        onRemoveTag = { tag -> tags = tags - tag },
        onSave = save,
        onBack = { if (!saving) onBack() },
        onSync = { if (!saving) onSync() },
        onOpenSync = { if (!saving) onOpenSync() },
        onBrowse = { if (!saving) onBrowse() },
        onOptions = { if (!saving) onOptions() },
    )

    if (isDesktopApp) {
        DesktopAddScreen(state, actions, syncing || saving)
    } else {
        MobileAddScreen(state, actions)
    }
}

private fun String.asHtmlAttribute(): String = replace("&", "&amp;").replace("\"", "&quot;")
