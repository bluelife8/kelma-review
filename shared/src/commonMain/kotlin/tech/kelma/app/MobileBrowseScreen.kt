package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MobileBrowseStateFlags = listOf(
    "All" to "",
    "New" to "is:new",
    "Learning" to "is:learning",
    "Review" to "is:review",
    "Suspended" to "is:suspended",
    "On this device" to "is:local",
)

@Composable
internal fun MobileBrowseScreen(state: BrowseUiState, actions: BrowseActions) {
    var confirmDelete by remember { mutableStateOf<BrowseCardRow?>(null) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.selected?.cardId) { editingId = null }

    Surface(modifier = Modifier.fillMaxSize(), color = KelmaColors.Background) {
        Column(modifier = Modifier.statusBarsPadding()) {
            val selected = state.selected
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    if (selected == null) {
                        MobileBrowseHeader(
                            "Browse",
                            onBack = actions.onBack,
                            actionLabel = "Sync",
                            onAction = actions.onSync,
                            showBack = false,
                        )
                        MobileBrowseSearch(state, actions)
                        MobileBrowseChips(state, actions)
                        MobileBrowseList(state, actions)
                    } else {
                        MobileBrowseDetail(
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
            if (selected == null) {
                MobileBottomNavigation(
                    selected = MobileCollectionTab.Browse,
                    onDecks = actions.onDecks,
                    onBrowse = {},
                    onAdd = actions.onAdd,
                    onOptions = actions.onOptions,
                    onSyncLog = actions.onOpenSync,
                )
            }
        }
    }

    confirmDelete?.let { row ->
        DeleteNoteDialog(
            row = row,
            surface = KelmaColors.SurfaceElevated,
            textPrimary = KelmaColors.TextPrimary,
            textSecondary = KelmaColors.TextSecondary,
            accent = KelmaColors.Gold,
            onConfirm = { actions.onDelete(row); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun MobileBrowseHeader(
    title: String,
    onBack: () -> Unit,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    showBack: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = KelmaColors.TextPrimary)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(title, color = KelmaColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        actionLabel?.let { label ->
            Text(
                label,
                modifier = Modifier.clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 9.dp),
                color = KelmaColors.GoldSoft,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    HorizontalDivider(color = KelmaColors.Hairline)
}

@Composable
private fun MobileBrowseSearch(state: BrowseUiState, actions: BrowseActions) {
    OutlinedTextField(
        value = state.query,
        onValueChange = actions.onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("browse-search"),
        placeholder = { Text("Search cards") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = KelmaColors.TextMuted) },
        trailingIcon = {
            if (state.query.text.isNotEmpty()) {
                IconButton(onClick = { actions.onQueryChange(TextFieldValue()) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = KelmaColors.TextMuted)
                }
            }
        },
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun MobileBrowseChips(state: BrowseUiState, actions: BrowseActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MobileBrowseStateFlags.forEach { (label, term) ->
            val active = if (term.isEmpty()) {
                state.query.text.isBlank()
            } else {
                queryHasTerm(state.query.text, term)
            }
            FilterChip(label, active) {
                if (term.isEmpty()) actions.onQueryChange(TextFieldValue()) else actions.onApplyTerm(term)
            }
        }
        state.decks.take(6).forEach { (deck, _) ->
            val term = browseQualifier("deck", deck)
            FilterChip(deck, queryHasTerm(state.query.text, term)) {
                actions.onApplyTerm(term)
            }
        }
        state.tags.take(6).forEach { (tag, _) ->
            val term = browseQualifier("tag", tag)
            FilterChip("#$tag", queryHasTerm(state.query.text, term)) {
                actions.onApplyTerm(term)
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        color = if (active) KelmaColors.Gold.copy(alpha = 0.18f) else KelmaColors.Surface,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, if (active) KelmaColors.Gold else KelmaColors.SurfaceBorder),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = if (active) KelmaColors.GoldSoft else KelmaColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun MobileBrowseList(state: BrowseUiState, actions: BrowseActions) {
    val listState = rememberLazyListState()
    BrowsePagingEffect(listState, state.rows.size, state.hasMore, actions.onLoadMore)
    LaunchedEffect(state.query.text, state.sorting) { listState.scrollToItem(0) }
    Text(
        "${state.rows.size} of ${state.totalCount} cards",
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        color = KelmaColors.TextMuted,
        fontSize = 12.sp,
    )
    if (state.rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                when {
                    state.loading -> "Loading cards…"
                    state.totalCount == 0 -> "No cards in the collection"
                    else -> "No cards match this search"
                },
                color = KelmaColors.TextMuted,
                fontSize = 14.sp,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().platformPointerScroll(listState).testTag("browse-list"),
        state = listState,
    ) {
        items(state.rows, key = { it.cardId }) { row ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { actions.onSelect(row.cardId) }
                    .testTag("browse-row-${row.cardId}"),
                color = KelmaColors.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, KelmaColors.SurfaceBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            row.question.ifBlank { "—" },
                            color = KelmaColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${row.deck} · ${row.state.label} · ${row.dueMillis?.let(::formatDueDate) ?: "—"}",
                            modifier = Modifier.padding(top = 3.dp),
                            color = KelmaColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = KelmaColors.TextMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        if (state.loadingMore) {
            item {
                Text(
                    "Loading more…",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    color = KelmaColors.TextMuted,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MobileBrowseDetail(
    row: BrowseCardRow,
    state: BrowseUiState,
    actions: BrowseActions,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onStopEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val detailScroll = rememberScrollState()
    MobileBrowseHeader(
        title = if (editing) "Edit note" else "Card",
        onBack = { if (editing) onStopEdit() else actions.onSelect(null) },
        actionLabel = if (editing) null else "Edit",
        onAction = onStartEdit,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .platformPointerScroll(detailScroll)
            .verticalScroll(detailScroll)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("browse-detail"),
    ) {
        if (editing) {
            state.selectedEdit?.let { target ->
                BrowseInlineEditor(
                    target = target,
                    titleColor = KelmaColors.TextMuted,
                    textSecondary = KelmaColors.TextSecondary,
                    accent = KelmaColors.Gold,
                    surfaceColor = KelmaColors.Surface,
                    borderColor = KelmaColors.SurfaceBorder,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = 14.dp,
                    onAttach = actions.onAttach,
                    onSave = actions.onSaveEdit,
                    onSaved = onStopEdit,
                    onCancel = onStopEdit,
                )
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = KelmaColors.Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, KelmaColors.SurfaceBorder),
            ) {
                Column(Modifier.padding(14.dp)) {
                    state.selectedCard?.let { card ->
                        BrowseCardPreview(
                            card = card,
                            labelColor = KelmaColors.TextMuted,
                            dividerColor = KelmaColors.SurfaceBorder,
                            frontStyle = TextStyle(
                                fontSize = 16.sp,
                                color = KelmaColors.TextPrimary,
                                fontWeight = FontWeight.Medium,
                            ),
                            backStyle = TextStyle(fontSize = 15.sp, color = KelmaColors.TextSecondary),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            MobileDetailLine("Note type", row.notetype)
            MobileDetailLine("Deck", row.deck)
            MobileDetailLine("State", row.state.label)
            MobileDetailLine("Due", row.dueMillis?.let(::formatDueDate) ?: "—")
            MobileDetailLine("Tags", row.tags.joinToString(", ").ifBlank { "—" })
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { actions.onStudyDeck(row.deck) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KelmaColors.Gold,
                    contentColor = KelmaColors.Background,
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Study deck", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (row.isLocal) {
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    MobileDetailAction("Delete", Modifier.weight(1f), destructive = true, onClick = onDelete)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MobileDetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.width(90.dp), color = KelmaColors.TextMuted, fontSize = 13.sp)
        Text(value, color = KelmaColors.TextPrimary, fontSize = 14.sp)
    }
}

@Composable
private fun MobileDetailAction(label: String, modifier: Modifier, destructive: Boolean = false, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(46.dp).clickable(onClick = onClick),
        color = KelmaColors.Surface,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, KelmaColors.SurfaceBorder),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (destructive) KelmaColors.Bad else KelmaColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
