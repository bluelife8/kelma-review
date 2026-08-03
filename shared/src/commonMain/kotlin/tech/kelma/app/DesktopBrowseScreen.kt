package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DesktopBrowseStateFlags = listOf(
    "All cards" to "",
    "New" to "is:new",
    "Learning" to "is:learning",
    "Review" to "is:review",
    "Suspended" to "is:suspended",
    "Added on this device" to "is:local",
)

@Composable
internal fun DesktopBrowseScreen(state: BrowseUiState, actions: BrowseActions, syncing: Boolean) {
    var confirmDelete by remember { mutableStateOf<BrowseCardRow?>(null) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    val browseListState = rememberLazyListState()
    BrowsePagingEffect(browseListState, state.rows.size, state.hasMore, actions.onLoadMore)
    LaunchedEffect(state.query.text, state.sorting) { browseListState.scrollToItem(0) }
    LaunchedEffect(state.selected?.cardId) { editingId = null }

    Surface(modifier = Modifier.fillMaxSize(), color = KelmaDesktopColors.Background) {
        Column(modifier = Modifier.safeContentPadding()) {
            DesktopTopToolbar(
                onDecks = actions.onDecks,
                onAdd = actions.onAdd,
                onBrowse = {},
                onOptions = actions.onOptions,
                onSync = actions.onSync,
                syncing = syncing,
                activeItem = "Browse",
            )
            Row(modifier = Modifier.fillMaxSize()) {
                DesktopBrowseSidebar(state, actions)
                Surface(color = KelmaDesktopColors.Border, modifier = Modifier.width(1.dp).fillMaxHeight()) {}
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    DesktopBrowseSearch(state, actions)
                    DesktopBrowseHeader(state, actions)
                    Surface(color = KelmaDesktopColors.Border, modifier = Modifier.fillMaxWidth().height(1.dp)) {}
                    if (state.rows.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                when {
                                    state.loading -> "Loading cards…"
                                    state.totalCount == 0 -> "No cards in the collection"
                                    else -> "No cards match this search"
                                },
                                color = KelmaDesktopColors.TextMuted,
                                fontSize = 14.sp,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("browse-list"),
                            state = browseListState,
                        ) {
                            items(state.rows, key = { it.cardId }) { row ->
                                DesktopBrowseRow(row, row.cardId == state.selected?.cardId) {
                                    actions.onSelect(row.cardId)
                                }
                            }
                            if (state.loadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("Loading more…", color = KelmaDesktopColors.TextMuted, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                state.selected?.let { selected ->
                    Surface(color = KelmaDesktopColors.Border, modifier = Modifier.width(1.dp).fillMaxHeight()) {}
                    DesktopBrowseDetail(
                        row = selected,
                        state = state,
                        actions = actions,
                        editing = editingId == selected.cardId,
                        onStartEdit = { editingId = selected.cardId },
                        onStopEdit = { editingId = null },
                        onDelete = { confirmDelete = selected },
                    )
                }
            }
        }
    }

    confirmDelete?.let { row ->
        DeleteNoteDialog(
            row = row,
            surface = KelmaDesktopColors.Surface,
            textPrimary = KelmaDesktopColors.TextPrimary,
            textSecondary = KelmaDesktopColors.TextSecondary,
            accent = KelmaDesktopColors.Gold,
            onConfirm = { actions.onDelete(row); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun DesktopBrowseSidebar(state: BrowseUiState, actions: BrowseActions) {
    var creationDateDialogOpen by remember { mutableStateOf(false) }
    val selectedCreationDate = selectedBrowseCreationDate(state.query.text)
    val creationDate = selectedCreationDate ?: formatDueDate(state.nowMillis)
    val creationFilterActive = parseBrowseQuery(state.query.text).any { it is BrowseTerm.Created }
    Column(
        modifier = Modifier
            .width(210.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        SidebarSection("SEARCH") {
            DesktopBrowseStateFlags.forEach { (label, term) ->
                val active = if (term.isEmpty()) {
                    state.query.text.isBlank()
                } else {
                    queryHasTerm(state.query.text, term)
                }
                SidebarItem(label, count = null, active = active) {
                    if (term.isEmpty()) {
                        actions.onQueryChange(TextFieldValue())
                    } else {
                        actions.onApplyTerm(term)
                    }
                }
            }
            SidebarItem(
                label = "Created · $creationDate",
                count = null,
                active = creationFilterActive,
                onClick = { creationDateDialogOpen = true },
            )
        }
        SidebarSection("DECKS") {
            state.decks.forEach { (deck, count) ->
                val term = browseQualifier("deck", deck)
                SidebarItem(deck, count, active = queryHasTerm(state.query.text, term)) {
                    actions.onApplyTerm(term)
                }
            }
        }
        if (state.tags.isNotEmpty()) {
            SidebarSection("TAGS") {
                state.tags.forEach { (tag, count) ->
                    val term = browseQualifier("tag", tag)
                    SidebarItem(tag, count, active = queryHasTerm(state.query.text, term)) {
                        actions.onApplyTerm(term)
                    }
                }
            }
        }
    }
    if (creationDateDialogOpen) {
        CreationDateFilterDialog(
            initialDate = creationDate,
            onDismiss = { creationDateDialogOpen = false },
            onConfirm = { date ->
                creationDateDialogOpen = false
                val updated = setQueryTerm(state.query.text, "created:$date")
                actions.onQueryChange(TextFieldValue(updated, TextRange(updated.length)))
            },
        )
    }
}

@Composable
private fun SidebarSection(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 6.dp),
        color = KelmaDesktopColors.TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
    )
    content()
}

@Composable
private fun SidebarItem(label: String, count: Int?, active: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val background = when {
        active -> KelmaDesktopColors.Gold.copy(alpha = 0.14f)
        hovered -> KelmaDesktopColors.SurfaceHigh
        else -> Color.Transparent
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interactions)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick),
        color = background,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                color = if (active) KelmaDesktopColors.Gold else KelmaDesktopColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            count?.let {
                Text("$it", color = KelmaDesktopColors.TextMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DesktopBrowseSearch(state: BrowseUiState, actions: BrowseActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = actions.onQueryChange,
            modifier = Modifier.weight(1f).testTag("browse-search"),
            placeholder = {
                Text(
                    "Search — try  deck:spanish  created:2026-08-01..2026-08-31",
                    color = KelmaDesktopColors.TextMuted,
                )
            },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = KelmaDesktopColors.TextMuted)
            },
            trailingIcon = {
                if (state.query.text.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = KelmaDesktopColors.TextMuted,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable { actions.onQueryChange(TextFieldValue()) },
                    )
                }
            },
            shape = RoundedCornerShape(10.dp),
        )
        Text(
            "${state.rows.size} of ${state.totalCount} cards",
            modifier = Modifier.padding(start = 14.dp).width(110.dp),
            color = KelmaDesktopColors.TextMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun DesktopBrowseHeader(state: BrowseUiState, actions: BrowseActions) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        SortHeader("Question", BrowseSort.Question, state, actions, Modifier.weight(2.6f))
        SortHeader("Answer", BrowseSort.Answer, state, actions, Modifier.weight(2.2f))
        SortHeader("Deck", BrowseSort.Deck, state, actions, Modifier.weight(1.2f))
        SortHeader("State", BrowseSort.State, state, actions, Modifier.weight(0.9f))
        SortHeader("Due", BrowseSort.Due, state, actions, Modifier.weight(0.9f))
        SortHeader("Created", BrowseSort.Created, state, actions, Modifier.weight(0.9f))
        SortHeader("Tags", BrowseSort.Tags, state, actions, Modifier.weight(1.2f))
    }
}

@Composable
private fun SortHeader(
    label: String,
    field: BrowseSort,
    state: BrowseUiState,
    actions: BrowseActions,
    modifier: Modifier,
) {
    val active = state.sorting.field == field
    Row(
        modifier = modifier.pointerHoverIcon(PointerIcon.Hand).clickable { actions.onSort(field) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label + if (active) (if (state.sorting.ascending) " ▲" else " ▼") else "",
            color = if (active) KelmaDesktopColors.Gold else KelmaDesktopColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DesktopBrowseRow(row: BrowseCardRow, selected: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val background = when {
        selected -> KelmaDesktopColors.Gold.copy(alpha = 0.12f)
        hovered -> KelmaDesktopColors.SurfaceHigh
        else -> Color.Transparent
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .testTag("browse-row-${row.cardId}"),
        color = background,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowseCell(row.question, Modifier.weight(2.6f))
            BrowseCell(row.answer, Modifier.weight(2.2f))
            BrowseCell(row.deck, Modifier.weight(1.2f))
            StateChip(row.state, Modifier.weight(0.9f))
            BrowseCell(row.dueMillis?.let(::formatDueDate) ?: "—", Modifier.weight(0.9f))
            BrowseCell(row.createdAtMillis?.let(::formatDueDate) ?: "Unknown", Modifier.weight(0.9f))
            BrowseCell(row.tags.joinToString(", "), Modifier.weight(1.2f))
        }
    }
}

@Composable
private fun BrowseCell(text: String, modifier: Modifier) {
    Text(
        text.ifBlank { "—" },
        modifier = modifier.padding(end = 10.dp),
        color = KelmaDesktopColors.TextSecondary,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun StateChip(state: BrowseCardState, modifier: Modifier = Modifier) {
    val color = when (state) {
        BrowseCardState.New -> KelmaDesktopColors.New
        BrowseCardState.Learning -> KelmaDesktopColors.Learn
        BrowseCardState.Review -> KelmaDesktopColors.Due
        BrowseCardState.Suspended -> KelmaDesktopColors.TextMuted
    }
    Text(state.label, modifier = modifier, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun DesktopBrowseDetail(
    row: BrowseCardRow,
    state: BrowseUiState,
    actions: BrowseActions,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onStopEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(420.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .testTag("browse-detail"),
    ) {
        if (editing) {
            state.selectedEdit?.let { target ->
                BrowseInlineEditor(
                    target = target,
                    titleColor = KelmaDesktopColors.TextMuted,
                    textSecondary = KelmaDesktopColors.TextSecondary,
                    accent = KelmaDesktopColors.Gold,
                    surfaceColor = KelmaDesktopColors.Surface,
                    borderColor = KelmaDesktopColors.Border,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = 14.dp,
                    onAttach = actions.onAttach,
                    onSave = actions.onSaveEdit,
                    onSaved = onStopEdit,
                    onCancel = onStopEdit,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CARD",
                    color = KelmaDesktopColors.TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
                Spacer(Modifier.weight(1f))
                DetailAction("Edit", onClick = onStartEdit)
            }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                color = KelmaDesktopColors.Surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, KelmaDesktopColors.Border),
            ) {
                Column(Modifier.padding(14.dp)) {
                    state.selectedCard?.let { card ->
                        BrowseCardPreview(
                            card = card,
                            labelColor = KelmaDesktopColors.TextMuted,
                            dividerColor = KelmaDesktopColors.Border,
                            frontStyle = TextStyle(
                                fontSize = 15.sp,
                                color = KelmaDesktopColors.TextPrimary,
                                fontWeight = FontWeight.Medium,
                            ),
                            backStyle = TextStyle(fontSize = 14.sp, color = KelmaDesktopColors.TextSecondary),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            DetailLine("Note type", row.notetype)
            DetailLine("Deck", row.deck)
            DetailLine("State", row.state.label)
            DetailLine("Due", row.dueMillis?.let(::formatDueDate) ?: "—")
            DetailLine("Created", row.createdAtMillis?.let(::formatDueDate) ?: "Unknown")
            DetailLine("Tags", row.tags.joinToString(", ").ifBlank { "—" })
            DetailLine("Card id", row.cardId.toString())
            Spacer(Modifier.height(18.dp))
            DesktopGoldButton(
                label = "Study deck",
                width = 200.dp,
                height = 40.dp,
                onClick = { actions.onStudyDeck(row.deck) },
            )
            if (row.isLocal) {
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    DetailAction("Delete", destructive = true, onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, modifier = Modifier.width(82.dp), color = KelmaDesktopColors.TextMuted, fontSize = 12.sp)
        Text(value, color = KelmaDesktopColors.TextPrimary, fontSize = 13.sp)
    }
}

@Composable
private fun DetailAction(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.height(36.dp).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, KelmaDesktopColors.Border),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                label,
                color = if (destructive) KelmaColors.Bad else KelmaDesktopColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
