package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommandPalette(
    commands: List<PluginCommand>,
    runningCommandId: String?,
    message: String?,
    onInvoke: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val visible = commands.filter { command ->
        query.isBlank() || command.title.contains(query, ignoreCase = true) ||
            command.id.contains(query, ignoreCase = true)
    }
    if (isDesktopApp) {
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("command-palette"),
            title = {
                Column {
                    Text("Command palette")
                    Text("Cmd/Ctrl+K", fontSize = 12.sp, color = KelmaColors.TextMuted)
                }
            },
            text = {
                CommandPaletteBody(
                    query = query,
                    onQueryChange = { query = it },
                    focusRequester = focusRequester,
                    visible = visible,
                    runningCommandId = runningCommandId,
                    message = message,
                    onInvoke = onInvoke,
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("command-palette"),
            containerColor = KelmaColors.Surface,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Command palette", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                CommandPaletteBody(
                    query = query,
                    onQueryChange = { query = it },
                    focusRequester = focusRequester,
                    visible = visible,
                    runningCommandId = runningCommandId,
                    message = message,
                    onInvoke = onInvoke,
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun CommandPaletteBody(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    visible: List<PluginCommand>,
    runningCommandId: String?,
    message: String?,
    onInvoke: (String) -> Unit,
) {
    var selectedIndex by remember(visible.map(PluginCommand::id)) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedIndex, visible.size) {
        if (selectedIndex in visible.indices) listState.animateScrollToItem(selectedIndex)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search commands") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || visible.isEmpty()) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            selectedIndex = (selectedIndex + 1).mod(visible.size)
                            true
                        }
                        Key.DirectionUp -> {
                            selectedIndex = (selectedIndex - 1).mod(visible.size)
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            if (runningCommandId == null) onInvoke(visible[selectedIndex].id)
                            true
                        }
                        else -> false
                    }
                }
                .testTag("command-palette-search"),
        )
        if (visible.isEmpty()) {
            Text("No matching commands", color = KelmaColors.TextSecondary)
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 360.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(visible, key = { _, command -> command.id }) { index, command ->
                    CommandPaletteRow(
                        command = command,
                        running = command.id == runningCommandId,
                        selected = index == selectedIndex,
                        enabled = runningCommandId == null,
                        onClick = { onInvoke(command.id) },
                    )
                }
            }
        }
        message?.let { Text(it, color = KelmaColors.GoldSoft, fontSize = 13.sp) }
    }
}

@Composable
private fun CommandPaletteRow(
    command: PluginCommand,
    running: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .testTag("command-${command.id}"),
        color = if (isDesktopApp) KelmaDesktopColors.Surface else KelmaColors.SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (selected) KelmaColors.Gold
            else if (isDesktopApp) KelmaDesktopColors.Border else KelmaColors.SurfaceBorder,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(command.title, fontWeight = FontWeight.SemiBold)
                Text(
                    if (isKelmaCommand(command.id)) "Kelma" else command.pluginId,
                    color = KelmaColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
            if (running) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(4.dp))
        }
    }
}
