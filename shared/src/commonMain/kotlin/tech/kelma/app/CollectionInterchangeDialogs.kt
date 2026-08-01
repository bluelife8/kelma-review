package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@Composable
fun CollectionExportDialog(
    deckNames: List<String>,
    initialDeckName: String?,
    onDismiss: () -> Unit,
    onExport: suspend (CollectionExportOptions) -> String?,
) {
    var format by remember { mutableStateOf(CollectionExportFormat.KelmaJson) }
    var selectedDeck by remember(initialDeckName) { mutableStateOf(initialDeckName) }
    var includeScheduling by remember { mutableStateOf(true) }
    var includePresets by remember { mutableStateOf(true) }
    var includeMedia by remember { mutableStateOf(true) }
    var legacy by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val packageFormat = format in setOf(
        CollectionExportFormat.AnkiDeckPackage,
        CollectionExportFormat.AnkiCollectionPackage,
    )
    val structuredFormat = packageFormat || format == CollectionExportFormat.KelmaJson
    val effectiveDeck = if (format == CollectionExportFormat.AnkiCollectionPackage) null else selectedDeck

    InterchangeDialogSurface(onDismiss = { if (!working) onDismiss() }) {
        Text("Export", color = KelmaColors.TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        InterchangeSelector(
            label = "Export format",
            value = format.label,
            values = CollectionExportFormat.entries.map { it.label to it },
            onSelect = { format = it },
        )
        Spacer(Modifier.height(12.dp))
        InterchangeSelector(
            label = "Include",
            value = effectiveDeck ?: "Whole collection",
            values = listOf("Whole collection" to null) + deckNames.map { it to it },
            enabled = format != CollectionExportFormat.AnkiCollectionPackage,
            onSelect = { selectedDeck = it },
        )
        Spacer(Modifier.height(14.dp))
        if (structuredFormat) {
            ExportCheckbox("Include scheduling information", includeScheduling) { includeScheduling = it }
            ExportCheckbox("Include deck presets", includePresets) { includePresets = it }
            ExportCheckbox("Include media", includeMedia) { includeMedia = it }
        }
        if (packageFormat) {
            ExportCheckbox("Support older Anki versions (slower/larger files)", legacy) { legacy = it }
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = KelmaColors.Bad, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") }
            Button(
                modifier = Modifier.testTag("confirm-export"),
                enabled = !working,
                onClick = {
                    working = true
                    error = null
                    scope.launch {
                        val failure = onExport(
                            CollectionExportOptions(
                                format = format,
                                deckName = effectiveDeck,
                                includeScheduling = includeScheduling,
                                includeDeckPresets = includePresets,
                                includeMedia = includeMedia,
                                supportOlderAnkiVersions = legacy,
                            ),
                        )
                        working = false
                        if (failure == null) onDismiss() else error = failure
                    }
                },
            ) {
                Text(if (working) "Exporting…" else "Export…")
            }
        }
    }
}

@Composable
fun CollectionImportDialog(
    document: InterchangeDocument,
    deckNames: List<String>,
    initialTextKind: TextImportKind,
    onDismiss: () -> Unit,
    onPreview: suspend (TextImportKind, String) -> CollectionImportPlan,
    onImport: suspend (CollectionImportPlan) -> String?,
) {
    val isText = document.filename.substringAfterLast('.', "").lowercase() !in setOf("apkg", "colpkg", "json")
    var textKind by remember { mutableStateOf(initialTextKind) }
    var targetDeck by remember { mutableStateOf(deckNames.firstOrNull() ?: "Imported") }
    var plan by remember { mutableStateOf<CollectionImportPlan?>(null) }
    var previewing by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(document, textKind, targetDeck) {
        previewing = true
        plan = null
        error = null
        try {
            plan = onPreview(textKind, targetDeck)
        } catch (exception: Exception) {
            error = exception.message ?: "Could not read the import file"
        } finally {
            previewing = false
        }
    }

    InterchangeDialogSurface(onDismiss = { if (!importing) onDismiss() }) {
        Text("Import", color = KelmaColors.TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(document.filename, color = KelmaColors.TextSecondary, fontSize = 13.sp)
        Text(formatInterchangeSize(document.bytes.size.toLong()), color = KelmaColors.TextMuted, fontSize = 12.sp)
        if (isText) {
            Spacer(Modifier.height(16.dp))
            InterchangeSelector(
                label = "Import as",
                value = textKind.label,
                values = TextImportKind.entries.map { it.label to it },
                onSelect = { textKind = it },
            )
            Spacer(Modifier.height(12.dp))
            InterchangeSelector(
                label = "Default deck",
                value = targetDeck,
                values = (deckNames + "Imported").distinct().map { it to it },
                onSelect = { targetDeck = it },
            )
        }
        Spacer(Modifier.height(18.dp))
        when {
            previewing -> Text("Reading file…", color = KelmaColors.TextSecondary)
            plan != null -> ImportPreview(plan = plan as CollectionImportPlan)
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = KelmaColors.Bad, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, enabled = !importing) { Text("Cancel") }
            Button(
                modifier = Modifier.testTag("confirm-import"),
                enabled = plan != null && !previewing && !importing,
                onClick = {
                    val ready = plan ?: return@Button
                    importing = true
                    error = null
                    scope.launch {
                        val failure = onImport(ready)
                        importing = false
                        if (failure == null) onDismiss() else error = failure
                    }
                },
            ) {
                Text(if (importing) "Importing…" else "Import")
            }
        }
    }
}

@Composable
private fun ImportPreview(plan: CollectionImportPlan) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Ready to import", color = KelmaColors.TextPrimary, fontWeight = FontWeight.SemiBold)
        Text(
            "${plan.notes.size} notes · ${plan.cards.size} cards · ${plan.decks.size} decks",
            color = KelmaColors.TextSecondary,
            fontSize = 14.sp,
        )
        if (plan.reviews.isNotEmpty()) {
            Text("${plan.reviews.size} immutable reviews", color = KelmaColors.TextSecondary, fontSize = 14.sp)
        }
        if (plan.media.isNotEmpty()) {
            Text("${plan.media.size} media files", color = KelmaColors.TextSecondary, fontSize = 14.sp)
        }
        if (plan.deckOptions.isNotEmpty() || plan.presets.isNotEmpty()) {
            val presetCount = (plan.deckOptions.values + plan.presets)
                .distinctBy(ImportedDeckOptions::sourceId)
                .size
            Text(
                "$presetCount deck ${if (presetCount == 1) "preset" else "presets"}",
                color = KelmaColors.TextSecondary,
                fontSize = 14.sp,
            )
        }
        plan.warnings.forEach { warning -> Text(warning, color = KelmaColors.GoldSoft, fontSize = 12.sp) }
        Text(
            "Existing GUIDs are merged only when content matches; conflicts are retained as copies.",
            color = KelmaColors.TextMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun InterchangeDialogSurface(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(max = 650.dp).fillMaxWidth(),
            color = if (isDesktopApp) KelmaDesktopColors.Surface else KelmaColors.SurfaceElevated,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, if (isDesktopApp) KelmaDesktopColors.Border else KelmaColors.SurfaceBorder),
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                content = { content() },
            )
        }
    }
}

@Composable
private fun <T> InterchangeSelector(
    label: String,
    value: String,
    values: List<Pair<String, T>>,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, color = KelmaColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = { expanded = true },
        ) {
            Text(value, modifier = Modifier.weight(1f), color = KelmaColors.TextPrimary)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { (label, item) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun ExportCheckbox(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label, color = KelmaColors.TextPrimary, fontSize = 14.sp)
    }
}

private fun formatInterchangeSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KiB"
    else -> "${bytes / 1_048_576} MiB"
}
