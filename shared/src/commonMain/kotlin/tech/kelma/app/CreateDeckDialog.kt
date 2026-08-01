package tech.kelma.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun CreateDeckDialog(
    existingDeckNames: Set<String>,
    onCreate: suspend (String) -> String?,
    onDismiss: () -> Unit,
) = DeckNameDialog(
    title = "Create deck",
    confirmLabel = "Create",
    initialName = "",
    inputTag = "create-deck-name",
    existingDeckNames = existingDeckNames,
    onSubmit = onCreate,
    onDismiss = onDismiss,
)

@Composable
internal fun RenameDeckDialog(
    deckName: String,
    existingDeckNames: Set<String>,
    onRename: suspend (String) -> String?,
    onDismiss: () -> Unit,
) = DeckNameDialog(
    title = "Rename deck",
    confirmLabel = "Rename",
    initialName = deckName,
    inputTag = "rename-deck-name",
    existingDeckNames = existingDeckNames.filterNot { it.equals(deckName, ignoreCase = true) }.toSet(),
    onSubmit = onRename,
    onDismiss = onDismiss,
)

@Composable
private fun DeckNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    inputTag: String,
    existingDeckNames: Set<String>,
    onSubmit: suspend (String) -> String?,
    onDismiss: () -> Unit,
) {
    var name by remember {
        mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length)))
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (saving) return
        val normalized = try {
            normalizeDeckName(name.text)
        } catch (exception: IllegalArgumentException) {
            error = exception.message
            return
        }
        if (existingDeckNames.any { it.equals(normalized, ignoreCase = true) }) {
            error = "A deck with this name already exists"
            return
        }
        saving = true
        error = null
        scope.launch {
            val saveError = onSubmit(normalized)
            saving = false
            if (saveError == null) onDismiss() else error = saveError
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = KelmaDesktopColors.Surface,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text(title, color = KelmaDesktopColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                submit()
                                true
                            } else {
                                false
                            }
                        }
                        .testTag(inputTag),
                    label = { Text("Deck name") },
                    supportingText = { Text("Use :: to create a nested deck") },
                    singleLine = true,
                    enabled = !saving,
                )
                error?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 8.dp).testTag("deck-name-error"),
                        color = KelmaColors.Bad,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text("Cancel", color = KelmaDesktopColors.TextSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = name.text.isNotBlank() && !saving) {
                Text(
                    if (saving) "Saving…" else confirmLabel,
                    color = KelmaDesktopColors.Gold,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

@Composable
internal fun DeleteDeckDialog(
    deckName: String,
    onDelete: suspend () -> String?,
    onDismiss: () -> Unit,
) {
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun delete() {
        if (deleting) return
        deleting = true
        error = null
        scope.launch {
            val deleteError = onDelete()
            deleting = false
            if (deleteError == null) onDismiss() else error = deleteError
        }
    }

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        containerColor = KelmaDesktopColors.Surface,
        shape = RoundedCornerShape(16.dp),
        title = {
            Text("Delete deck?", color = KelmaDesktopColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "Delete “$deckName”, its subdecks, and all of their cards? This cannot be undone.",
                    color = KelmaDesktopColors.TextSecondary,
                    fontSize = 14.sp,
                )
                error?.let {
                    Text(it, modifier = Modifier.padding(top = 8.dp), color = KelmaColors.Bad, fontSize = 12.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text("Cancel", color = KelmaDesktopColors.TextSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = ::delete, enabled = !deleting, modifier = Modifier.testTag("delete-deck-confirm")) {
                Text(if (deleting) "Deleting…" else "Delete", color = KelmaColors.Bad, fontWeight = FontWeight.Bold)
            }
        },
    )
}
