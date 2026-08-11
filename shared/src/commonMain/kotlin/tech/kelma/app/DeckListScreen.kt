package tech.kelma.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MobileCountWidth = 48.dp
internal const val KelmaAccountDeletionUrl = "https://kelma.tech/review/account-deletion"

@Composable
fun DeckListScreen(
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
    confirmBeforeUndo: Boolean = true,
    onUndo: () -> Unit,
    onAdd: () -> Unit,
    onCreateDeck: suspend (String) -> String?,
    deckManagement: DeckManagementActions,
    onBrowse: () -> Unit,
    onOptions: () -> Unit,
    onImportFile: () -> Unit,
    onExportCollection: () -> Unit,
    onOpenDeck: (DeckSummary) -> Unit,
    onSignIn: () -> Unit,
    onSync: () -> Unit,
    onOpenSync: () -> Unit,
    onSignOut: () -> Unit,
) {
    var showGetShared by remember { mutableStateOf(false) }
    var showUndoConfirmation by remember { mutableStateOf(false) }
    val requestUndo = {
        if (canUndo && !syncing) {
            if (confirmBeforeUndo) showUndoConfirmation = true else onUndo()
        }
    }
    val uriHandler = LocalUriHandler.current
    if (isDesktopApp) {
        DesktopDeckListScreen(
            decks = decks,
            loading = loading,
            signedIn = signedIn,
            syncing = syncing,
            syncMessage = syncMessage,
            syncMessageIsError = syncMessageIsError,
            studiedToday = studiedToday,
            syncedCardCount = syncedCardCount,
            localCardCount = localCardCount,
            syncedMediaBytes = syncedMediaBytes,
            canUndo = canUndo && !syncing,
            onUndo = requestUndo,
            onAdd = onAdd,
            onCreateDeck = onCreateDeck,
            deckManagement = deckManagement,
            onBrowse = onBrowse,
            onOptions = onOptions,
            onImportFile = onImportFile,
            onExportCollection = onExportCollection,
            onGetShared = { showGetShared = true },
            onOpenDeck = onOpenDeck,
            onSignIn = onSignIn,
            onSync = onOpenSync,
            onSyncNow = onSync,
            onSignOut = onSignOut,
        )
    } else {
        MobileDeckListScreen(
            decks = decks,
            loading = loading,
            signedIn = signedIn,
            syncing = syncing,
            syncMessage = syncMessage,
            syncMessageIsError = syncMessageIsError,
            canUndo = canUndo,
            onUndo = requestUndo,
            onAdd = onAdd,
            onBrowse = onBrowse,
            onOptions = onOptions,
            onImportFile = onImportFile,
            onExportCollection = onExportCollection,
            onGetShared = { showGetShared = true },
            onOpenDeck = onOpenDeck,
            onSignIn = onSignIn,
            onSync = onSync,
            onOpenSync = onOpenSync,
            onSignOut = onSignOut,
            onDeleteAccount = { uriHandler.openUri(KelmaAccountDeletionUrl) },
        )
    }
    if (showUndoConfirmation) {
        UndoReviewConfirmationDialog(
            onConfirm = {
                showUndoConfirmation = false
                if (canUndo && !syncing) onUndo()
            },
            onDismiss = { showUndoConfirmation = false },
        )
    }
    if (showGetShared) {
        GetSharedDecksDialog(
            onOpenUri = uriHandler::openUri,
            onDismiss = { showGetShared = false },
        )
    }
}

@Composable
private fun MobileDeckListScreen(
    decks: List<DeckSummary>,
    loading: Boolean,
    signedIn: Boolean,
    syncing: Boolean,
    syncMessage: String?,
    syncMessageIsError: Boolean,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onAdd: () -> Unit,
    onBrowse: () -> Unit,
    onOptions: () -> Unit,
    onImportFile: () -> Unit,
    onExportCollection: () -> Unit,
    onGetShared: () -> Unit,
    onOpenDeck: (DeckSummary) -> Unit,
    onSignIn: () -> Unit,
    onSync: () -> Unit,
    onOpenSync: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    val deckListState = rememberLazyListState()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = KelmaColors.Background,
        topBar = {
            MobileToolbar(
                signedIn,
                syncing,
                canUndo,
                onSignIn,
                onSync,
                onSignOut,
                onUndo,
                onImportFile,
                onExportCollection,
                onGetShared,
                onDeleteAccount,
            )
        },
        bottomBar = {
            MobileBottomNavigation(
                selected = MobileCollectionTab.Decks,
                onDecks = {},
                onBrowse = onBrowse,
                onAdd = onAdd,
                onOptions = onOptions,
                onSyncLog = onOpenSync,
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(scaffoldPadding).platformPointerScroll(deckListState),
            state = deckListState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 96.dp),
        ) {
            item { MobileDeckTitle() }
            item { Spacer(Modifier.height(8.dp)) }
            syncMessage?.let { message ->
                item {
                    Text(
                        text = message,
                        modifier = Modifier.padding(bottom = 20.dp),
                        color = if (syncMessageIsError) KelmaColors.Bad else KelmaColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item { MobileDeckHeader() }
            items(decks, key = { it.id }) { deck ->
                MobileDeckRow(deck) { onOpenDeck(deck) }
            }
            if (decks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(color = KelmaColors.Gold, strokeWidth = 2.dp)
                            Text(
                                "Loading decks…",
                                modifier = Modifier.padding(top = 12.dp),
                                color = KelmaColors.TextSecondary,
                            )
                        } else {
                            Text(
                                text = "No decks yet\nAdd a card to start your first deck.",
                                color = KelmaColors.TextSecondary,
                                lineHeight = 22.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileToolbar(
    signedIn: Boolean,
    syncing: Boolean,
    canUndo: Boolean,
    onSignIn: () -> Unit,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
    onUndo: () -> Unit,
    onImportFile: () -> Unit,
    onExportCollection: () -> Unit,
    onGetShared: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    var accountMenu by remember { mutableStateOf(false) }
    val onStats = LocalOpenStats.current
    Surface(modifier = Modifier.statusBarsPadding(), color = KelmaColors.Background) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Kelma Review",
                color = KelmaColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
            )
            Spacer(Modifier.weight(1f))
            if (canUndo) {
                TextButton(onClick = onUndo, enabled = !syncing) {
                    Text("Undo", color = KelmaColors.TextSecondary)
                }
            }
            IconButton(onClick = if (signedIn) onSync else onSignIn, enabled = !syncing) {
                if (syncing) {
                    CircularProgressIndicator(
                        Modifier.width(22.dp).height(22.dp),
                        color = KelmaColors.Gold,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Rounded.Sync, contentDescription = "Sync now", tint = KelmaColors.GoldSoft)
                }
            }
            Box {
                IconButton(onClick = { accountMenu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Account", tint = KelmaColors.TextSecondary)
                }
                DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Stats") },
                        onClick = {
                            accountMenu = false
                            onStats()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Import File") },
                        onClick = {
                            accountMenu = false
                            onImportFile()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Export Collection") },
                        onClick = {
                            accountMenu = false
                            onExportCollection()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Get Shared") },
                        onClick = {
                            accountMenu = false
                            onGetShared()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (signedIn) "Switch account" else "Choose account") },
                        onClick = {
                            accountMenu = false
                            if (signedIn) onSignOut() else onSignIn()
                        },
                    )
                    if (signedIn) {
                        DropdownMenuItem(
                            text = { Text("Delete Kelma account…") },
                            onClick = {
                                accountMenu = false
                                onDeleteAccount()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileDeckTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(32.dp)
                .background(KelmaColors.Gold, RoundedCornerShape(3.dp)),
        )
        Text(
            text = "Decks",
            modifier = Modifier.padding(start = 12.dp),
            color = KelmaColors.TextPrimary,
            fontSize = 34.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.8).sp,
        )
    }
}

@Composable
private fun MobileDeckHeader() {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            MobileColumnLabel("New", KelmaColors.NewCard, MobileCountWidth)
            MobileColumnLabel("Learn", KelmaColors.Bad, MobileCountWidth)
            MobileColumnLabel("Due", KelmaColors.Good, MobileCountWidth)
        }
        HorizontalDivider(color = KelmaColors.Hairline)
    }
}

@Composable
internal fun MobileDeckRow(deck: DeckSummary, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DeckSyncBadgeSlot(deck.pendingChanges)
                Spacer(Modifier.width(9.dp))
                Text(
                    text = deck.name,
                    modifier = Modifier.weight(1f),
                    color = KelmaColors.TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MobileCount(deck.newCount, KelmaColors.NewCard)
            MobileCount(deck.learningCount, KelmaColors.Bad)
            MobileCount(deck.dueCount, KelmaColors.Good)
        }
        HorizontalDivider(color = KelmaColors.Hairline)
    }
}

@Composable
private fun MobileColumnLabel(text: String, color: Color, width: Dp) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.width(width),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.6.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun MobileCount(value: Int, color: Color) {
    Text(
        text = value.toString(),
        modifier = Modifier.width(MobileCountWidth),
        color = if (value == 0) KelmaColors.TextMuted else color,
        fontSize = 17.sp,
        fontWeight = if (value == 0) FontWeight.Medium else FontWeight.ExtraBold,
        textAlign = TextAlign.Center,
    )
}
