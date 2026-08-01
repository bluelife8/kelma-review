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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MobileToolbarPalette = AddToolbarPalette(
    icon = KelmaColors.TextSecondary,
    hover = KelmaColors.SurfaceHigh,
    surface = KelmaColors.Surface,
    border = KelmaColors.SurfaceBorder,
    accent = KelmaColors.Gold,
    menuSurface = KelmaColors.Surface,
    menuText = KelmaColors.TextPrimary,
)

private val MobileDialogPalette = AddDialogPalette(
    surface = KelmaColors.SurfaceElevated,
    textPrimary = KelmaColors.TextPrimary,
    textSecondary = KelmaColors.TextSecondary,
    textMuted = KelmaColors.TextMuted,
    accent = KelmaColors.Gold,
    codeSurface = KelmaColors.Background,
    border = KelmaColors.SurfaceBorder,
)

@Composable
internal fun MobileAddScreen(state: AddUiState, actions: AddActions) {
    var showFields by remember { mutableStateOf(false) }
    var showCards by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val editorScroll = rememberScrollState()

    Surface(modifier = Modifier.fillMaxSize(), color = KelmaColors.Background) {
        Column(modifier = Modifier.statusBarsPadding().imePadding()) {
            MobileAddHeader(
                actions = actions,
                onFields = { showFields = true },
                onCards = { showCards = true },
                onHelp = { showHelp = true },
                onHistory = { showHistory = true },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .platformPointerScroll(editorScroll)
                    .verticalScroll(editorScroll)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                MobileLabel("TYPE")
                MobileSelector(
                    state.notetype.name,
                    if (state.notetypeLocked) {
                        emptyList()
                    } else {
                        state.notetypes.map { type -> type.name to { actions.onSelectNotetype(type) } }
                    },
                )
                Spacer(Modifier.height(16.dp))
                MobileLabel("DECK")
                MobileDeckField(state, actions)
                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = KelmaColors.Surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, KelmaColors.SurfaceBorder),
                ) {
                    AddFormatToolbar(
                        state = state,
                        actions = actions,
                        palette = MobileToolbarPalette,
                        onFields = { showFields = true },
                        onCards = { showCards = true },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                state.fields.forEachIndexed { index, field ->
                    if (index > 0) Spacer(Modifier.height(13.dp))
                    MobileFieldEditor(index, field, actions)
                }
                Spacer(Modifier.height(16.dp))
                MobileTagsEditor(state, actions)
                Spacer(Modifier.height(8.dp))
            }
            MobileAddBottomBar(state, actions)
            MobileBottomNavigation(
                selected = MobileCollectionTab.Add,
                onDecks = actions.onBack,
                onBrowse = actions.onBrowse,
                onAdd = {},
                onOptions = actions.onOptions,
                onSyncLog = actions.onOpenSync,
            )
        }
    }

    if (showFields) FieldsDialog(state.notetype, MobileDialogPalette) { showFields = false }
    if (showCards) CardsDialog(state.notetype, MobileDialogPalette) { showCards = false }
    if (showHelp) EditorHelpDialog(MobileDialogPalette) { showHelp = false }
    if (showHistory) HistoryDialog(state.history, MobileDialogPalette) { showHistory = false }
}

@Composable
private fun MobileAddHeader(
    actions: AddActions,
    onFields: () -> Unit,
    onCards: () -> Unit,
    onHelp: () -> Unit,
    onHistory: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Add card", color = KelmaColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = actions.onSync) {
            Icon(Icons.Rounded.Sync, contentDescription = "Sync now", tint = KelmaColors.GoldSoft)
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = KelmaColors.TextPrimary)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MobileMenuItem("Fields…") { menu = false; onFields() }
                MobileMenuItem("Card templates…") { menu = false; onCards() }
                MobileMenuItem("History") { menu = false; onHistory() }
                MobileMenuItem("Editor help") { menu = false; onHelp() }
                MobileMenuItem("Clear all fields") { menu = false; actions.onClearFields() }
            }
        }
    }
    HorizontalDivider(color = KelmaColors.Hairline)
}

@Composable
private fun MobileFieldEditor(index: Int, field: AddFieldState, actions: AddActions) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = KelmaColors.Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, KelmaColors.SurfaceBorder),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(field.name, color = KelmaColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                MobileToggle(Icons.Rounded.RemoveRedEye, "Preview", field.showPreview) { actions.onTogglePreview(index) }
                MobileToggle(Icons.Rounded.PushPin, "Pin field", field.sticky) { actions.onToggleSticky(index) }
            }
            OutlinedTextField(
                value = field.value,
                onValueChange = { actions.onFieldChange(index, it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .heightIn(min = 108.dp)
                    .onFocusChanged { if (it.isFocused) actions.onFocusField(index) }
                    .testTag(fieldTag(index)),
                minLines = 4,
                shape = RoundedCornerShape(12.dp),
            )
            if (field.showPreview && field.value.text.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = KelmaColors.Background,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        renderInlineHtml(field.value.text),
                        modifier = Modifier.padding(12.dp),
                        color = KelmaColors.TextPrimary,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MobileTagsEditor(state: AddUiState, actions: AddActions) {
    MobileLabel("TAGS")
    if (state.tags.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            state.tags.forEach { tag ->
                Surface(
                    color = KelmaColors.Gold.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, KelmaColors.Gold.copy(alpha = 0.45f)),
                    modifier = Modifier.clickable { actions.onRemoveTag(tag) },
                ) {
                    Row(
                        modifier = Modifier.padding(start = 11.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tag, color = KelmaColors.GoldSoft, fontSize = 12.sp)
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Remove $tag",
                            tint = KelmaColors.GoldSoft,
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
        placeholder = { Text("Add a tag") },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        trailingIcon = {
            if (state.tagInput.isNotBlank()) {
                IconButton(onClick = actions.onCommitTag) {
                    Icon(Icons.Rounded.Check, contentDescription = "Add tag", tint = KelmaColors.Gold)
                }
            }
        },
    )
}

@Composable
private fun MobileAddBottomBar(state: AddUiState, actions: AddActions) {
    Surface(color = KelmaColors.BackgroundAlt, border = BorderStroke(1.dp, KelmaColors.Hairline)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
            state.message?.let {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Icon(
                        if (state.messageIsError) Icons.Rounded.Close else Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (state.messageIsError) KelmaColors.Bad else KelmaColors.Good,
                    )
                    Text(
                        it,
                        modifier = Modifier.padding(start = 7.dp),
                        color = if (state.messageIsError) KelmaColors.Bad else KelmaColors.Good,
                        fontSize = 13.sp,
                    )
                }
            }
            Button(
                onClick = actions.onSave,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("add-save"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KelmaColors.Gold,
                    contentColor = KelmaColors.Background,
                    disabledContainerColor = KelmaColors.Gold.copy(alpha = 0.45f),
                ),
                shape = RoundedCornerShape(15.dp),
            ) {
                Text(
                    if (state.saving) "Adding…" else "Add",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

@Composable
private fun MobileSelector(text: String, options: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .then(if (options.isEmpty()) Modifier else Modifier.clickable { expanded = true }),
            color = KelmaColors.Surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, KelmaColors.SurfaceBorder),
        ) {
            Row(modifier = Modifier.padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = KelmaColors.TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (options.isNotEmpty()) {
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = KelmaColors.TextSecondary)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, onClick) ->
                MobileMenuItem(label) { onClick(); expanded = false }
            }
        }
    }
}

@Composable
private fun MobileDeckField(state: AddUiState, actions: AddActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = state.deckName,
            onValueChange = actions.onDeckNameChange,
            modifier = Modifier.fillMaxWidth().testTag("add-deck"),
            singleLine = true,
            placeholder = { Text("Deck name") },
            shape = RoundedCornerShape(14.dp),
            trailingIcon = {
                if (state.deckNames.isNotEmpty()) {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose deck", tint = KelmaColors.TextSecondary)
                    }
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.deckNames.forEach { name ->
                MobileMenuItem(name) { actions.onDeckNameChange(name); expanded = false }
            }
        }
    }
}

@Composable
private fun MobileMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = KelmaColors.TextPrimary, fontSize = 15.sp) },
        onClick = onClick,
    )
}

@Composable
private fun MobileToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(start = 5.dp).size(32.dp).clickable(onClick = onClick),
        color = if (active) KelmaColors.Gold.copy(alpha = 0.18f) else Color.Transparent,
        shape = RoundedCornerShape(9.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) KelmaColors.Gold else KelmaColors.TextMuted,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
private fun MobileLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(bottom = 8.dp),
        color = KelmaColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.2.sp,
    )
}
