package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SyncScreen(
    entries: List<SyncLogEntry>,
    signedIn: Boolean,
    syncing: Boolean,
    onDecks: () -> Unit,
    onAdd: () -> Unit,
    onBrowse: () -> Unit,
    onOptions: () -> Unit,
    onSync: () -> Unit,
    onClear: () -> Unit,
    onRedownloadCollection: () -> Unit = {},
) {
    var showRedownloadConfirmation by remember { mutableStateOf(false) }
    if (!isDesktopApp) {
        MobileSyncScreen(
            entries, signedIn, syncing, onDecks, onBrowse, onAdd, onOptions, onSync, onClear,
            onRedownloadCollection,
        )
        return
    }
    Surface(Modifier.fillMaxSize(), color = KelmaDesktopColors.Background) {
        Column(Modifier.safeContentPadding()) {
            if (isDesktopApp) {
                DesktopTopToolbar(onDecks, {}, onAdd, onBrowse, onOptions, syncing, "Sync")
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDecks) { Text("Decks", color = KelmaColors.TextMuted) }
                    Text("Sync log", Modifier.weight(1f), color = KelmaColors.TextPrimary,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onClear, enabled = entries.isNotEmpty() && !syncing) {
                        Text("Clear", color = KelmaColors.TextMuted)
                    }
                }
                HorizontalDivider(color = KelmaColors.Hairline)
            }
            Column(
                Modifier.fillMaxSize().widthIn(max = 900.dp).align(Alignment.CenterHorizontally)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "KelmaSync activity",
                            color = if (isDesktopApp) KelmaDesktopColors.TextPrimary else KelmaColors.TextPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Newest entries first · retained across restarts",
                            color = KelmaDesktopColors.TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (signedIn) {
                            TextButton(
                                onClick = { showRedownloadConfirmation = true },
                                enabled = !syncing,
                            ) { Text("Redownload") }
                        }
                        if (isDesktopApp) {
                            TextButton(onClick = onClear, enabled = entries.isNotEmpty() && !syncing) { Text("Clear") }
                        }
                        Button(onClick = onSync, enabled = !syncing) {
                            if (syncing) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                            else Text(if (signedIn) "Sync now" else "Sign in to sync")
                        }
                    }
                }
                Surface(
                    Modifier.fillMaxSize(),
                    color = if (isDesktopApp) KelmaDesktopColors.Surface else KelmaColors.Surface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, if (isDesktopApp) KelmaDesktopColors.Border else KelmaColors.Hairline),
                ) {
                    if (entries.isEmpty()) {
                        Text("No sync activity yet.", Modifier.padding(24.dp),
                            color = if (isDesktopApp) KelmaDesktopColors.TextMuted else KelmaColors.TextMuted)
                    } else {
                        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                            items(entries, key = SyncLogEntry::id) { entry -> DesktopSyncLogRow(entry) }
                        }
                    }
                }
            }
        }
    }
    RedownloadCollectionDialog(
        visible = showRedownloadConfirmation,
        enabled = signedIn && !syncing,
        onDismiss = { showRedownloadConfirmation = false },
        onConfirm = {
            showRedownloadConfirmation = false
            onRedownloadCollection()
        },
    )
}

@Composable
private fun MobileSyncScreen(
    entries: List<SyncLogEntry>,
    signedIn: Boolean,
    syncing: Boolean,
    onDecks: () -> Unit,
    onBrowse: () -> Unit,
    onAdd: () -> Unit,
    onOptions: () -> Unit,
    onSync: () -> Unit,
    onClear: () -> Unit,
    onRedownloadCollection: () -> Unit,
) {
    val logListState = rememberLazyListState()
    var showRedownloadConfirmation by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = KelmaColors.Background,
        topBar = {
            Surface(modifier = Modifier.statusBarsPadding(), color = KelmaColors.Background) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sync", Modifier.weight(1f), color = KelmaColors.TextPrimary,
                        fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    TextButton(onClick = onClear, enabled = entries.isNotEmpty() && !syncing) {
                        Text("Clear", color = KelmaColors.TextSecondary)
                    }
                    IconButton(onClick = onSync, enabled = !syncing) {
                        if (syncing) {
                            CircularProgressIndicator(
                                Modifier.size(22.dp),
                                color = KelmaColors.Gold,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Rounded.Sync, contentDescription = "Sync now", tint = KelmaColors.GoldSoft)
                        }
                    }
                }
            }
        },
        bottomBar = {
            MobileBottomNavigation(
                selected = MobileCollectionTab.Sync,
                onDecks = onDecks,
                onBrowse = onBrowse,
                onAdd = onAdd,
                onOptions = onOptions,
                onSyncLog = {},
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("Activity", color = KelmaColors.TextPrimary, fontSize = 30.sp,
                lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (signedIn) "Detailed sync history, newest first" else "Sign in from Decks to start syncing",
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                color = KelmaColors.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            if (signedIn) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { showRedownloadConfirmation = true },
                        enabled = !syncing,
                    ) { Text("Redownload collection", color = KelmaColors.Bad) }
                }
            }
            Surface(
                Modifier.fillMaxSize(),
                color = KelmaColors.Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, KelmaColors.SurfaceBorder),
            ) {
                if (entries.isEmpty()) {
                    Text("No sync activity yet.", Modifier.padding(20.dp), color = KelmaColors.TextMuted,
                        fontSize = 14.sp, lineHeight = 20.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier.platformPointerScroll(logListState),
                        state = logListState,
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(entries, key = SyncLogEntry::id) { MobileSyncLogRow(it) }
                    }
                }
            }
        }
    }
    RedownloadCollectionDialog(
        visible = showRedownloadConfirmation,
        enabled = signedIn && !syncing,
        onDismiss = { showRedownloadConfirmation = false },
        onConfirm = {
            showRedownloadConfirmation = false
            onRedownloadCollection()
        },
    )
}

@Composable
private fun RedownloadCollectionDialog(
    visible: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KelmaColors.SurfaceElevated,
        title = { Text("Redownload collection?", color = KelmaColors.TextPrimary) },
        text = {
            Text(
                "Downloaded cards, records, and media will be removed from this device and fetched again. " +
                    "Pending local reviews and edits are preserved.",
                color = KelmaColors.TextSecondary,
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled) {
                Text("Confirm redownload", color = KelmaColors.Bad)
            }
        },
    )
}

@Composable
private fun DesktopSyncLogRow(entry: SyncLogEntry) {
    val color = syncLogColor(entry.level, desktop = true)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            syncLogTime(entry.occurredAtMillis),
            Modifier.padding(end = 12.dp),
            color = KelmaColors.TextMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Text(
            entry.phase,
            Modifier.padding(end = 12.dp).widthIn(min = 76.dp),
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        Text(entry.message, Modifier.weight(1f), color = color, fontSize = 13.sp)
    }
}

@Composable
private fun MobileSyncLogRow(entry: SyncLogEntry) {
    val color = syncLogColor(entry.level, desktop = false)
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.phase,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
                Text(
                    syncLogTime(entry.occurredAtMillis),
                    color = KelmaColors.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            Text(
                entry.message,
                color = color,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = KelmaColors.Hairline,
        )
    }
}

private fun syncLogColor(level: SyncLogLevel, desktop: Boolean) = when (level) {
    SyncLogLevel.Success -> KelmaColors.Good
    SyncLogLevel.Warning -> if (desktop) KelmaDesktopColors.Gold else KelmaColors.Gold
    SyncLogLevel.Error -> KelmaColors.Bad
    SyncLogLevel.Info -> if (desktop) KelmaDesktopColors.TextPrimary else KelmaColors.TextPrimary
}

private fun syncLogTime(epochMillis: Long): String {
    val seconds = ((epochMillis / 1_000L) % 86_400L + 86_400L) % 86_400L
    fun Long.two() = toString().padStart(2, '0')
    return "${(seconds / 3_600).two()}:${(seconds % 3_600 / 60).two()}:${(seconds % 60).two()}"
}
