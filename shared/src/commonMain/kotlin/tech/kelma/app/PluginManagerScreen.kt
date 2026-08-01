package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun PluginManagerScreen(
    state: PluginHostState,
    busy: Boolean,
    message: String?,
    rendererAssignments: PluginRendererAssignmentState,
    rendererIds: List<String>,
    deckNames: List<String>,
    noteTypes: List<RendererNoteTypeTarget>,
    onDecks: () -> Unit,
    onAdd: () -> Unit,
    onBrowse: () -> Unit,
    onOptions: () -> Unit,
    onSync: () -> Unit,
    onInstall: () -> Unit,
    pendingInstall: PluginManifest?,
    onConfirmInstall: () -> Unit,
    onDismissInstall: () -> Unit,
    onReload: () -> Unit,
    onSafeMode: (Boolean) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onRunCommand: (String) -> Unit,
    onAssignRenderer: (PluginRendererScope, String, String) -> Unit,
    onRemoveRenderer: (PluginRendererScope, String) -> Unit,
    onLoadLogs: suspend (String) -> List<PluginLogEntry>,
) {
    if (isDesktopApp) {
        Surface(Modifier.fillMaxSize(), color = KelmaDesktopColors.Background) {
            Column(Modifier.safeContentPadding()) {
                DesktopTopToolbar(
                    onDecks = onDecks,
                    onAdd = onAdd,
                    onBrowse = onBrowse,
                    onOptions = onOptions,
                    onSync = onSync,
                    syncing = busy,
                    activeItem = "Options",
                )
                PluginManagerBody(
                    Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                    state, busy, message, rendererAssignments, rendererIds, deckNames, noteTypes,
                    onInstall, onReload, onSafeMode, onEnabled, onUninstall, onRunCommand,
                    onAssignRenderer, onRemoveRenderer, onLoadLogs,
                )
            }
        }
    } else {
        Scaffold(
            containerColor = KelmaColors.Background,
            topBar = {
                Surface(modifier = Modifier.statusBarsPadding(), color = KelmaColors.Background) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onOptions) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to options", tint = KelmaColors.GoldSoft)
                        }
                        Text(
                            "Plugins",
                            color = KelmaColors.TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            },
            bottomBar = {
                MobileBottomNavigation(
                    selected = MobileCollectionTab.Options,
                    onDecks = onDecks,
                    onBrowse = onBrowse,
                    onAdd = onAdd,
                    onOptions = onOptions,
                    onSyncLog = onSync,
                )
            },
        ) { padding ->
            PluginManagerBody(
                Modifier.padding(padding).padding(horizontal = 20.dp, vertical = 12.dp),
                state, busy, message, rendererAssignments, rendererIds, deckNames, noteTypes,
                onInstall, onReload, onSafeMode, onEnabled, onUninstall, onRunCommand,
                onAssignRenderer, onRemoveRenderer, onLoadLogs,
            )
        }
    }
    pendingInstall?.let { manifest ->
        AlertDialog(
            onDismissRequest = onDismissInstall,
            title = { Text("Install ${manifest.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${manifest.id} · ${manifest.version}")
                    Text("Requested capabilities:")
                    Text(
                        manifest.capabilities.sortedBy(PluginCapability::name)
                            .joinToString().ifBlank { "None" },
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (manifest.dependencies.isNotEmpty()) {
                        Text("Dependencies: ${manifest.dependencies.joinToString { it.id }}")
                    }
                    Text("Install only if you trust the plugin source and publisher.")
                }
            },
            confirmButton = { TextButton(onClick = onConfirmInstall) { Text("Install") } },
            dismissButton = { TextButton(onClick = onDismissInstall) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PluginManagerBody(
    modifier: Modifier,
    state: PluginHostState,
    busy: Boolean,
    message: String?,
    rendererAssignments: PluginRendererAssignmentState,
    rendererIds: List<String>,
    deckNames: List<String>,
    noteTypes: List<RendererNoteTypeTarget>,
    onInstall: () -> Unit,
    onReload: () -> Unit,
    onSafeMode: (Boolean) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    onRunCommand: (String) -> Unit,
    onAssignRenderer: (PluginRendererScope, String, String) -> Unit,
    onRemoveRenderer: (PluginRendererScope, String) -> Unit,
    onLoadLogs: suspend (String) -> List<PluginLogEntry>,
) {
    val textPrimary = if (isDesktopApp) KelmaDesktopColors.TextPrimary else KelmaColors.TextPrimary
    val textSecondary = if (isDesktopApp) KelmaDesktopColors.TextSecondary else KelmaColors.TextSecondary
    val panel = if (isDesktopApp) KelmaDesktopColors.Surface else KelmaColors.Surface
    val border = if (isDesktopApp) KelmaDesktopColors.Border else KelmaColors.SurfaceBorder
    var pendingRemoval by remember { mutableStateOf<InstalledPlugin?>(null) }
    var displayedLogs by remember { mutableStateOf<Pair<InstalledPlugin, List<PluginLogEntry>>?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Lua plugins",
                    color = textPrimary,
                    fontSize = if (isDesktopApp) 30.sp else 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("Portable Lua 5.4 extensions", color = textSecondary)
            }
            Icon(
                Icons.Rounded.Extension,
                null,
                tint = if (isDesktopApp) KelmaDesktopColors.Gold else KelmaColors.Gold,
            )
        }
        Surface(
            color = panel,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, border),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Plugins are third-party software", color = textPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "Review the requested capabilities before installing. Runtime limits and restricted " +
                        "libraries reduce exposure, but plugins are not an operating-system security boundary.",
                    color = textSecondary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Safe mode", color = textPrimary, fontWeight = FontWeight.SemiBold)
                        Text("Start without executing plugins", color = textSecondary)
                    }
                    Switch(
                        checked = state.safeMode,
                        onCheckedChange = onSafeMode,
                        enabled = !busy,
                        modifier = Modifier.testTag("plugin-safe-mode").semantics {
                            contentDescription = "Plugin safe mode"
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onInstall, enabled = !busy) { Text("Install .kelmaplugin") }
                    OutlinedButton(onClick = onReload, enabled = !busy) {
                        Icon(Icons.Rounded.Refresh, null)
                        Text(" Reload")
                    }
                }
                Text(
                    "${state.running.size} running · ${state.commandCount} commands · " +
                        "${state.eventCount} events · ${state.rendererCount} renderers",
                    color = textSecondary,
                )
                message?.let { Text(it, color = KelmaColors.GoldSoft) }
            }
        }
        if (state.installed.isEmpty()) {
            Text("No plugins installed", color = textSecondary, modifier = Modifier.padding(vertical = 24.dp))
        }
        state.installed.forEach { plugin ->
            key(plugin.manifest.id) {
                val running = state.running.firstOrNull { it.pluginId == plugin.manifest.id }
                PluginRow(
                    plugin = plugin,
                    running = running,
                    busy = busy,
                    panel = panel,
                    border = border,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    onEnabled = onEnabled,
                    onRemove = { pendingRemoval = plugin },
                    onLogs = {
                        scope.launch { displayedLogs = plugin to onLoadLogs(plugin.manifest.id) }
                    },
                    onRunCommand = onRunCommand,
                )
            }
        }
        PluginRendererAssignmentControls(
            assignments = rendererAssignments,
            rendererIds = rendererIds,
            deckNames = deckNames,
            noteTypes = noteTypes,
            busy = busy,
            onAssign = onAssignRenderer,
            onRemove = onRemoveRenderer,
        )
        Spacer(Modifier.padding(bottom = 12.dp))
    }
    pendingRemoval?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove ${plugin.manifest.name}?") },
            text = { Text("The plugin package, logs, and settings will be deleted.") },
            confirmButton = {
                TextButton(onClick = { pendingRemoval = null; onUninstall(plugin.manifest.id) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }
    displayedLogs?.let { (plugin, logs) ->
        AlertDialog(
            onDismissRequest = { displayedLogs = null },
            title = { Text("${plugin.manifest.name} logs") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (logs.isEmpty()) Text("No diagnostics recorded")
                    logs.forEach { Text("${it.level.uppercase()}: ${it.message}", fontSize = 13.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { displayedLogs = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun PluginRow(
    plugin: InstalledPlugin,
    running: RunningPlugin?,
    busy: Boolean,
    panel: Color,
    border: Color,
    textPrimary: Color,
    textSecondary: Color,
    onEnabled: (String, Boolean) -> Unit,
    onRemove: () -> Unit,
    onLogs: () -> Unit,
    onRunCommand: (String) -> Unit,
) {
    Surface(
        color = panel,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(plugin.manifest.name, color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${plugin.manifest.id} · ${plugin.manifest.version}", color = textSecondary, fontSize = 13.sp)
                }
                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = { onEnabled(plugin.manifest.id, it) },
                    enabled = !busy,
                    modifier = Modifier.testTag("plugin-enabled-${plugin.manifest.id}").semantics {
                        contentDescription = "Enable ${plugin.manifest.name}"
                    },
                )
            }
            Text(
                when {
                    running != null -> "Running · ${running.startupMillis} ms startup"
                    !plugin.enabled -> "Disabled"
                    else -> plugin.status.name
                },
                color = if (running != null) {
                    if (isDesktopApp) KelmaDesktopColors.Due else KelmaColors.Good
                } else textSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            if (plugin.manifest.capabilities.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    plugin.manifest.capabilities.sortedBy(PluginCapability::name).forEach { capability ->
                        Surface(color = border, shape = RoundedCornerShape(10.dp)) {
                            Text(
                                capability.name,
                                color = textPrimary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(7.dp, 4.dp),
                            )
                        }
                    }
                }
            }
            if (plugin.manifest.dependencies.isNotEmpty()) {
                Text(
                    "Dependencies: ${plugin.manifest.dependencies.joinToString { it.id }}",
                    color = textSecondary,
                    fontSize = 13.sp,
                )
            }
            running?.eventNames?.takeIf { it.isNotEmpty() }?.let {
                Text("Events: ${it.sorted().joinToString()}", color = textSecondary, fontSize = 13.sp)
            }
            running?.rendererIds?.takeIf { it.isNotEmpty() }?.let {
                Text("Renderers: ${it.sorted().joinToString()}", color = textSecondary, fontSize = 13.sp)
            }
            plugin.errorMessage?.let { Text(it, color = KelmaColors.Bad, fontSize = 13.sp) }
            running?.commands?.forEach { command ->
                OutlinedButton(onClick = { onRunCommand(command.id) }, enabled = !busy) { Text("Run ${command.title}") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onLogs, enabled = !busy) { Text("Logs") }
                TextButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
            }
        }
    }
}
