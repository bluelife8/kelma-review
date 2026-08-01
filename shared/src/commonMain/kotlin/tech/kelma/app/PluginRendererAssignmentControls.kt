package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class RendererNoteTypeTarget(val id: Long, val name: String)

@Composable
internal fun PluginRendererAssignmentControls(
    assignments: PluginRendererAssignmentState,
    rendererIds: List<String>,
    deckNames: List<String>,
    noteTypes: List<RendererNoteTypeTarget>,
    busy: Boolean,
    onAssign: (PluginRendererScope, String, String) -> Unit,
    onRemove: (PluginRendererScope, String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val textPrimary = if (isDesktopApp) KelmaDesktopColors.TextPrimary else KelmaColors.TextPrimary
    val textSecondary = if (isDesktopApp) KelmaDesktopColors.TextSecondary else KelmaColors.TextSecondary
    val panel = if (isDesktopApp) KelmaDesktopColors.Surface else KelmaColors.Surface
    val border = if (isDesktopApp) KelmaDesktopColors.Border else KelmaColors.SurfaceBorder
    Surface(
        color = panel,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth().testTag("renderer-assignments"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Card renderer assignments", color = textPrimary, fontWeight = FontWeight.Bold)
            Text(
                "The nearest deck assignment overrides note type. Unavailable renderers safely fall back to Kelma.",
                color = textSecondary,
                fontSize = 13.sp,
            )
            assignments.assignments.forEach { assignment ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(assignmentTargetLabel(assignment, deckNames, noteTypes), color = textPrimary)
                        Text(
                            assignment.rendererId +
                                if (assignment.rendererId !in rendererIds) " · unavailable" else "",
                            color = textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(
                        onClick = { onRemove(assignment.scope, assignment.targetId) },
                        enabled = !busy,
                    ) { Text("Remove") }
                }
            }
            Button(
                onClick = { showDialog = true },
                enabled = !busy && rendererIds.isNotEmpty() && (deckNames.isNotEmpty() || noteTypes.isNotEmpty()),
                modifier = Modifier.testTag("assign-renderer"),
            ) { Text("Assign renderer") }
        }
    }
    if (showDialog) {
        RendererAssignmentDialog(
            rendererIds = rendererIds,
            deckNames = deckNames,
            noteTypes = noteTypes,
            onDismiss = { showDialog = false },
            onSave = { scope, target, renderer ->
                showDialog = false
                onAssign(scope, target, renderer)
            },
        )
    }
}

@Composable
private fun RendererAssignmentDialog(
    rendererIds: List<String>,
    deckNames: List<String>,
    noteTypes: List<RendererNoteTypeTarget>,
    onDismiss: () -> Unit,
    onSave: (PluginRendererScope, String, String) -> Unit,
) {
    var scope by remember {
        mutableStateOf(if (deckNames.isNotEmpty()) PluginRendererScope.Deck else PluginRendererScope.NoteType)
    }
    var target by remember(scope) {
        mutableStateOf(targetOptions(scope, deckNames, noteTypes).firstOrNull()?.first.orEmpty())
    }
    var renderer by remember(rendererIds) { mutableStateOf(rendererIds.firstOrNull().orEmpty()) }
    val targets = targetOptions(scope, deckNames, noteTypes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign card renderer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PluginRendererScope.entries.forEach { candidate ->
                        val enabled = when (candidate) {
                            PluginRendererScope.Deck -> deckNames.isNotEmpty()
                            PluginRendererScope.NoteType -> noteTypes.isNotEmpty()
                        }
                        if (candidate == scope) {
                            Button(onClick = {}, enabled = enabled) { Text(scopeLabel(candidate)) }
                        } else {
                            OutlinedButton(
                                onClick = { scope = candidate },
                                enabled = enabled,
                            ) { Text(scopeLabel(candidate)) }
                        }
                    }
                }
                AssignmentSelector("Target", target, targets, { target = it }, "renderer-target")
                AssignmentSelector(
                    "Renderer",
                    renderer,
                    rendererIds.map { it to it },
                    { renderer = it },
                    "renderer-id",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(scope, target, renderer) },
                enabled = target.isNotBlank() && renderer.isNotBlank(),
            ) { Text("Assign") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AssignmentSelector(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    testTag: String,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected
    Column {
        Text(label, fontSize = 12.sp, color = KelmaColors.TextSecondary)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().testTag(testTag),
            ) { Text(selectedLabel.ifBlank { "Choose" }) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = { expanded = false; onSelect(value) },
                    )
                }
            }
        }
    }
}

private fun targetOptions(
    scope: PluginRendererScope,
    deckNames: List<String>,
    noteTypes: List<RendererNoteTypeTarget>,
): List<Pair<String, String>> = when (scope) {
    PluginRendererScope.Deck -> deckNames.sortedWith(String.CASE_INSENSITIVE_ORDER).map { it to it }
    PluginRendererScope.NoteType -> noteTypes.sortedBy(RendererNoteTypeTarget::name).map {
        it.id.toString() to "${it.name} · ${it.id}"
    }
}

private fun assignmentTargetLabel(
    assignment: PluginRendererAssignment,
    deckNames: List<String>,
    noteTypes: List<RendererNoteTypeTarget>,
): String = when (assignment.scope) {
    PluginRendererScope.Deck -> "Deck · " +
        (deckNames.firstOrNull { it.equals(assignment.targetId, ignoreCase = true) } ?: assignment.targetId)
    PluginRendererScope.NoteType -> {
        val target = noteTypes.firstOrNull { it.id.toString() == assignment.targetId }
        "Note type · ${target?.name ?: assignment.targetId}"
    }
}

private fun scopeLabel(scope: PluginRendererScope): String = when (scope) {
    PluginRendererScope.Deck -> "Deck"
    PluginRendererScope.NoteType -> "Note type"
}
