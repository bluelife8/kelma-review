package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DesktopToolbarPalette = AddToolbarPalette(
    icon = KelmaDesktopColors.TextSecondary,
    hover = KelmaDesktopColors.SurfaceHigh,
    surface = KelmaDesktopColors.Surface,
    border = KelmaDesktopColors.Border,
    accent = KelmaDesktopColors.Gold,
    menuSurface = KelmaDesktopColors.Surface,
    menuText = KelmaDesktopColors.TextPrimary,
)

private val DesktopDialogPalette = AddDialogPalette(
    surface = KelmaDesktopColors.Surface,
    textPrimary = KelmaDesktopColors.TextPrimary,
    textSecondary = KelmaDesktopColors.TextSecondary,
    textMuted = KelmaDesktopColors.TextMuted,
    accent = KelmaDesktopColors.Gold,
    codeSurface = KelmaDesktopColors.Background,
    border = KelmaDesktopColors.Border,
)

@Composable
internal fun DesktopAddScreen(state: AddUiState, actions: AddActions, syncing: Boolean) {
    var showFields by remember { mutableStateOf(false) }
    var showCards by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Enter &&
                    (event.isMetaPressed || event.isCtrlPressed)
                ) {
                    actions.onSave()
                    true
                } else {
                    false
                }
            },
        color = KelmaDesktopColors.Background,
    ) {
        Column(modifier = Modifier.safeContentPadding()) {
            DesktopTopToolbar(
                onDecks = actions.onBack,
                onAdd = {},
                onBrowse = actions.onBrowse,
                onOptions = actions.onOptions,
                onSync = actions.onSync,
                syncing = syncing,
                activeItem = "Add",
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 1000.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 40.dp, end = 40.dp, top = 26.dp, bottom = 28.dp),
                ) {
                    DesktopDestinationRow(state, actions)
                    Spacer(Modifier.height(18.dp))
                    DesktopToolbarRow(state, actions, onFields = { showFields = true }, onCards = { showCards = true })
                    Spacer(Modifier.height(16.dp))
                    state.fields.forEachIndexed { index, field ->
                        if (index > 0) Spacer(Modifier.height(14.dp))
                        DesktopFieldEditor(index, field, actions)
                    }
                    Spacer(Modifier.height(18.dp))
                    DesktopTagsEditor(state, actions)
                }
            }
            DesktopAddFooter(state, actions, onHelp = { showHelp = true })
        }
    }

    if (showFields) FieldsDialog(state.notetype, DesktopDialogPalette) { showFields = false }
    if (showCards) CardsDialog(state.notetype, DesktopDialogPalette) { showCards = false }
    if (showHelp) EditorHelpDialog(DesktopDialogPalette) { showHelp = false }
}

@Composable
private fun DesktopDestinationRow(state: AddUiState, actions: AddActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(modifier = Modifier.width(320.dp)) {
            DesktopFieldLabel("TYPE")
            DesktopMenuButton(
                text = state.notetype.name,
                options = if (state.notetypeLocked) {
                    emptyList()
                } else {
                    state.notetypes.map { type -> type.name to { actions.onSelectNotetype(type) } }
                },
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            DesktopFieldLabel("DECK")
            DesktopDeckField(state, actions)
        }
    }
}

@Composable
private fun DesktopToolbarRow(state: AddUiState, actions: AddActions, onFields: () -> Unit, onCards: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = KelmaDesktopColors.Toolbar,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, KelmaDesktopColors.Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AddFormatToolbar(
                state = state,
                actions = actions,
                palette = DesktopToolbarPalette,
                onFields = onFields,
                onCards = onCards,
                modifier = Modifier.weight(1f),
            )
            DesktopMenuIcon(
                options = listOf(
                    "Clear all fields" to actions.onClearFields,
                ),
            )
        }
    }
}

@Composable
private fun DesktopFieldEditor(index: Int, field: AddFieldState, actions: AddActions) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.name, color = KelmaDesktopColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            DesktopToggle(Icons.Rounded.RemoveRedEye, "Preview", field.showPreview) { actions.onTogglePreview(index) }
            DesktopToggle(Icons.Rounded.PushPin, "Pin field", field.sticky) { actions.onToggleSticky(index) }
        }
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(
            value = field.value,
            onValueChange = { actions.onFieldChange(index, it) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 104.dp)
                .onFocusChanged { if (it.isFocused) actions.onFocusField(index) }
                .testTag(fieldTag(index)),
            minLines = 3,
            colors = desktopFieldColors(),
            shape = RoundedCornerShape(12.dp),
        )
        if (field.showPreview && field.value.text.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = KelmaDesktopColors.Background,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, KelmaDesktopColors.Border),
            ) {
                Text(
                    renderInlineHtml(field.value.text),
                    modifier = Modifier.padding(14.dp),
                    color = KelmaDesktopColors.TextPrimary,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DesktopTagsEditor(state: AddUiState, actions: AddActions) {
    DesktopFieldLabel("TAGS")
    if (state.tags.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            state.tags.forEach { tag ->
                Surface(
                    color = KelmaDesktopColors.Gold.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, KelmaDesktopColors.Gold.copy(alpha = 0.5f)),
                    modifier = Modifier.clickable { actions.onRemoveTag(tag) }.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 11.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tag, color = KelmaDesktopColors.Gold, fontSize = 12.sp)
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Remove $tag",
                            tint = KelmaDesktopColors.Gold,
                            modifier = Modifier.padding(start = 4.dp).size(13.dp),
                        )
                    }
                }
            }
        }
    }
    OutlinedTextField(
        value = state.tagInput,
        onValueChange = actions.onTagInputChange,
        modifier = Modifier.fillMaxWidth().testTag("add-tags"),
        placeholder = { Text("Add a tag and press Enter", color = KelmaDesktopColors.TextMuted) },
        singleLine = true,
        colors = desktopFieldColors(),
        shape = RoundedCornerShape(10.dp),
        trailingIcon = {
            if (state.tagInput.isNotBlank()) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "Add tag",
                    tint = KelmaDesktopColors.Gold,
                    modifier = Modifier.clickable { actions.onCommitTag() }.pointerHoverIcon(PointerIcon.Hand),
                )
            }
        },
    )
}

@Composable
private fun DesktopFieldLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(bottom = 7.dp),
        color = KelmaDesktopColors.TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
    )
}

@Composable
private fun DesktopMenuButton(text: String, options: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .then(
                    if (options.isEmpty()) {
                        Modifier
                    } else {
                        Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { expanded = true }
                    },
                ),
            color = KelmaDesktopColors.Surface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, KelmaDesktopColors.Border),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text, color = KelmaDesktopColors.TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (options.isNotEmpty()) {
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = KelmaDesktopColors.TextSecondary)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, onClick) ->
                DropdownMenuItem(
                    text = { Text(label, color = KelmaDesktopColors.TextPrimary, fontSize = 14.sp) },
                    onClick = { onClick(); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun DesktopDeckField(state: AddUiState, actions: AddActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = state.deckName,
            onValueChange = actions.onDeckNameChange,
            modifier = Modifier.fillMaxWidth().testTag("add-deck"),
            singleLine = true,
            placeholder = { Text("Deck name", color = KelmaDesktopColors.TextMuted) },
            colors = desktopFieldColors(),
            shape = RoundedCornerShape(10.dp),
            trailingIcon = {
                if (state.deckNames.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = "Choose deck",
                        tint = KelmaDesktopColors.TextSecondary,
                        modifier = Modifier.clickable { expanded = true }.pointerHoverIcon(PointerIcon.Hand),
                    )
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.deckNames.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name, color = KelmaDesktopColors.TextPrimary, fontSize = 14.sp) },
                    onClick = { actions.onDeckNameChange(name); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun DesktopMenuIcon(options: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DesktopIconButton(
            icon = Icons.Rounded.Settings,
            contentDescription = "Editor options",
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, onClick) ->
                DropdownMenuItem(
                    text = { Text(label, color = KelmaDesktopColors.TextPrimary, fontSize = 14.sp) },
                    onClick = { onClick(); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun DesktopToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(30.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        color = if (active) KelmaDesktopColors.Gold.copy(alpha = 0.18f) else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) KelmaDesktopColors.Gold else KelmaDesktopColors.TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun desktopFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = KelmaDesktopColors.Surface,
    unfocusedContainerColor = KelmaDesktopColors.Surface,
    focusedBorderColor = KelmaDesktopColors.Gold,
    unfocusedBorderColor = KelmaDesktopColors.Border,
    focusedTextColor = KelmaDesktopColors.TextPrimary,
    unfocusedTextColor = KelmaDesktopColors.TextPrimary,
    cursorColor = KelmaDesktopColors.Gold,
)
