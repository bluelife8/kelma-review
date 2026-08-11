package tech.kelma.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DesktopCountColumn = 78.dp

@Composable
fun DesktopDeckListScreen(
    decks: List<DeckSummary>,
    loading: Boolean = false,
    signedIn: Boolean,
    syncing: Boolean,
    syncMessage: String?,
    syncMessageIsError: Boolean,
    studiedToday: Int,
    syncedCardCount: Int,
    localCardCount: Int,
    syncedMediaBytes: Long,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onAdd: () -> Unit,
    onCreateDeck: suspend (String) -> String?,
    deckManagement: DeckManagementActions,
    onBrowse: () -> Unit = {},
    onOptions: () -> Unit = {},
    onImportFile: () -> Unit = {},
    onExportCollection: () -> Unit = {},
    onGetShared: () -> Unit = {},
    onOpenDeck: (DeckSummary) -> Unit,
    onSignIn: () -> Unit,
    onSync: () -> Unit,
    onSyncNow: () -> Unit = onSync,
    onAccount: () -> Unit = {},
) {
    val syncAction = if (signedIn) onSync else onSignIn
    val panelHeight = (70 + decks.size.coerceAtMost(8) * 48).coerceIn(166, 440).dp
    var showCreateDeck by remember { mutableStateOf(false) }
    var renameDeck by remember { mutableStateOf<DeckSummary?>(null) }
    var deleteDeck by remember { mutableStateOf<DeckSummary?>(null) }
    Surface(modifier = Modifier.fillMaxSize(), color = KelmaDesktopColors.Background) {
        Column(modifier = Modifier.safeContentPadding()) {
            DesktopTopToolbar(
                onDecks = {},
                onSync = syncAction,
                onAdd = onAdd,
                onBrowse = onBrowse,
                onOptions = onOptions,
                syncing = syncing,
                activeItem = "Decks",
            )
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 10.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DesktopSyncSummary(
                        signedIn = signedIn,
                        cards = syncedCardCount,
                        localCards = localCardCount,
                        mediaBytes = syncedMediaBytes,
                        message = syncMessage,
                        isError = syncMessageIsError,
                    )
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        modifier = Modifier
                            .width(560.dp)
                            .height(panelHeight),
                        color = KelmaDesktopColors.Surface,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, KelmaDesktopColors.Border),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
                            DesktopTableHeader()
                            HorizontalDivider(color = KelmaDesktopColors.Border)
                            if (decks.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (loading) "Loading decks…" else "No decks yet",
                                        color = KelmaDesktopColors.TextMuted,
                                        fontSize = 14.sp,
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.weight(1f)) {
                                    items(decks, key = { it.id }) { deck ->
                                        DesktopDeckRow(
                                            deck = deck,
                                            hasChildren = decks.any {
                                                it.name.startsWith("${deck.name}::", ignoreCase = true)
                                            },
                                            onOpenDeck = onOpenDeck,
                                            onAddCards = { deckManagement.onAddCards(deck.name) },
                                            onBrowseCards = { deckManagement.onBrowseCards(deck.name) },
                                            onOptions = { deckManagement.onOptions(deck.name) },
                                            onExport = { deckManagement.onExport(deck.name) },
                                            onRename = { renameDeck = deck },
                                            onDelete = { deleteDeck = deck },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (studiedToday == 0) {
                            "Ready to study"
                        } else {
                            "Studied $studiedToday cards today"
                        },
                        modifier = Modifier.padding(bottom = 20.dp),
                        color = KelmaDesktopColors.TextPrimary,
                        fontSize = 14.sp,
                    )
                    Row(
                        modifier = Modifier.padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (canUndo) DesktopUtilityButton("Undo", onClick = onUndo)
                        DesktopSyncUtilityButton(
                            syncing = syncing,
                            onClick = if (signedIn) onSyncNow else onSignIn,
                        )
                        DesktopUtilityButton("Account…", onClick = onAccount)
                        DesktopUtilityButton("Get Shared", onClick = onGetShared)
                        DesktopUtilityButton("Create Deck", onClick = { showCreateDeck = true })
                        DesktopUtilityButton("Import File", onClick = onImportFile)
                        DesktopUtilityButton("Export", onClick = onExportCollection)
                    }
                }
            }
        }
    }
    if (showCreateDeck) {
        CreateDeckDialog(
            existingDeckNames = decks.map(DeckSummary::name).toSet(),
            onCreate = onCreateDeck,
            onDismiss = { showCreateDeck = false },
        )
    }
    renameDeck?.let { deck ->
        RenameDeckDialog(
            deckName = deck.name,
            existingDeckNames = decks.map(DeckSummary::name).toSet(),
            onRename = { newName -> deckManagement.onRename(deck.name, newName) },
            onDismiss = { renameDeck = null },
        )
    }
    deleteDeck?.let { deck ->
        DeleteDeckDialog(
            deckName = deck.name,
            onDelete = { deckManagement.onDelete(deck.name) },
            onDismiss = { deleteDeck = null },
        )
    }
}

@Composable
private fun DesktopSyncUtilityButton(syncing: Boolean, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val color by animateColorAsState(
        if (hovered && !syncing) KelmaDesktopColors.TextMuted else KelmaDesktopColors.UtilityButton,
    )
    Surface(
        modifier = Modifier
            .height(28.dp)
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactions, indication = null) {
                if (!syncing) onClick()
            },
        color = color,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, KelmaDesktopColors.Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Sync,
                contentDescription = "Sync now",
                modifier = Modifier.size(15.dp),
                tint = KelmaDesktopColors.TextPrimary,
            )
            Text(if (syncing) "Syncing…" else "Sync", color = KelmaDesktopColors.TextPrimary, fontSize = 12.sp)
            Text("Ctrl/Cmd+S", color = KelmaDesktopColors.TextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun DesktopSyncSummary(
    signedIn: Boolean,
    cards: Int,
    localCards: Int,
    mediaBytes: Long,
    message: String?,
    isError: Boolean,
) {
    val text = when {
        message != null -> message
        signedIn && localCards > 0 -> "KelmaSync: ${formatCount(cards)} downloaded · " +
            "$localCards local · ${formatBytes(mediaBytes)}"
        signedIn -> "KelmaSync: ${formatCount(cards)} cards · ${formatBytes(mediaBytes)}"
        localCards > 0 -> "$localCards local ${if (localCards == 1) "card" else "cards"} · Sync is optional"
        else -> "KelmaSync: click Sync to sign in"
    }
    Text(
        text = text,
        modifier = Modifier.height(32.dp).padding(top = 4.dp),
        color = if (isError) KelmaColors.Bad else KelmaDesktopColors.Accent,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun DesktopTableHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableLabel("DECK", Modifier.weight(1f), TextAlign.Start)
        TableLabel("NEW", Modifier.width(DesktopCountColumn), TextAlign.Center)
        TableLabel("LEARN", Modifier.width(DesktopCountColumn), TextAlign.Center)
        TableLabel("DUE", Modifier.width(DesktopCountColumn), TextAlign.Center)
        Spacer(Modifier.width(38.dp))
    }
}

@Composable
private fun TableLabel(text: String, modifier: Modifier, alignment: TextAlign) {
    Text(
        text = text,
        modifier = modifier,
        color = KelmaDesktopColors.TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        textAlign = alignment,
    )
}

@Composable
private fun DesktopDeckRow(
    deck: DeckSummary,
    hasChildren: Boolean,
    onOpenDeck: (DeckSummary) -> Unit,
    onAddCards: () -> Unit,
    onBrowseCards: () -> Unit,
    onOptions: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val background by animateColorAsState(
        if (hovered) KelmaDesktopColors.SurfaceHigh else Color.Transparent,
    )
    var menuExpanded by remember(deck.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(background, RoundedCornerShape(11.dp))
            .hoverable(interactions)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactions, indication = null) { onOpenDeck(deck) }
            .padding(horizontal = 14.dp)
            .testTag("deck-row-${deck.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(18.dp), contentAlignment = Alignment.Center) {
            if (hasChildren) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Expand deck",
                    modifier = Modifier.size(16.dp),
                    tint = KelmaDesktopColors.TextPrimary,
                )
            }
        }
        DeckSyncBadgeSlot(deck.pendingChanges)
        Text(
            text = deck.name.substringAfterLast("::"),
            modifier = Modifier.weight(1f).padding(start = 11.dp),
            color = KelmaDesktopColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        DesktopCount(deck.newCount, KelmaDesktopColors.New, DesktopCountColumn)
        DesktopCount(deck.learningCount, KelmaDesktopColors.Learn, DesktopCountColumn)
        DesktopCount(deck.dueCount, KelmaDesktopColors.Due, DesktopCountColumn)
        Box(modifier = Modifier.width(38.dp), contentAlignment = Alignment.Center) {
            DesktopIconButton(
                icon = Icons.Rounded.Settings,
                contentDescription = "Deck options for ${deck.name}",
                onClick = { menuExpanded = true },
            )
            DeckOptionsMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                onAddCards = onAddCards,
                onBrowseCards = onBrowseCards,
                onOptions = onOptions,
                onExport = onExport,
                onRename = onRename,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun DeckOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAddCards: () -> Unit,
    onBrowseCards: () -> Unit,
    onOptions: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    fun run(action: () -> Unit) {
        onDismiss()
        action()
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(190.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = KelmaDesktopColors.Surface,
        border = BorderStroke(1.dp, KelmaDesktopColors.Border),
    ) {
        DeckMenuItem("Rename", Icons.Rounded.Edit) { run(onRename) }
        DeckMenuItem("Options", Icons.Rounded.Settings) { run(onOptions) }
        DeckMenuItem("Export", Icons.Rounded.FileUpload) { run(onExport) }
        DeckMenuItem("Delete", Icons.Rounded.Delete, destructive = true) { run(onDelete) }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            color = KelmaDesktopColors.Border,
        )
        DeckMenuItem("Add cards", Icons.Rounded.Add) { run(onAddCards) }
        DeckMenuItem("Browse cards", Icons.Rounded.Search) { run(onBrowseCards) }
    }
}

@Composable
private fun DeckMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> KelmaDesktopColors.TextMuted.copy(alpha = 0.55f)
        destructive -> KelmaColors.Bad
        else -> KelmaDesktopColors.TextPrimary
    }
    DropdownMenuItem(
        text = { Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium) },
        modifier = Modifier.testTag("deck-menu-${label.lowercase().replace(' ', '-')}"),
        onClick = onClick,
        enabled = enabled,
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = color) },
    )
}

@Composable
private fun DesktopCount(value: Int, color: Color, width: Dp) {
    Text(
        text = value.toString(),
        modifier = Modifier.width(width),
        color = if (value == 0) KelmaDesktopColors.TextMuted else color,
        fontSize = 14.sp,
        fontWeight = if (value == 0) FontWeight.Normal else FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
}

private fun formatCount(value: Int): String = value.toString().reversed()
    .chunked(3)
    .joinToString(",")
    .reversed()

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "${(bytes / 10_000_000) / 100.0} GB"
    bytes >= 1_000_000 -> "${(bytes / 10_000) / 100.0} MB"
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}
