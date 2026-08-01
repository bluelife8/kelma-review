package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun DeckPresetControls(
    state: DeckPresetState,
    deckName: String,
    desktop: Boolean,
    currentOptions: () -> DeckOptions,
    onAssign: suspend (String, String?) -> String?,
    onCreate: suspend (String, String, DeckOptions) -> String?,
    onClone: suspend (String, String, String) -> String?,
    onRename: suspend (String, String) -> String?,
    onDelete: suspend (String) -> String?,
) {
    val selected = state.presetForDeck(deckName)
    var expanded by remember { mutableStateOf(false) }
    var name by remember(selected?.id) { mutableStateOf(selected?.name.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val primary = if (desktop) KelmaDesktopColors.TextPrimary else KelmaColors.TextPrimary
    val secondary = if (desktop) KelmaDesktopColors.TextSecondary else KelmaColors.TextSecondary
    val border = if (desktop) KelmaDesktopColors.Border else KelmaColors.SurfaceBorder
    val surface = if (desktop) KelmaDesktopColors.Background else KelmaColors.Background

    fun launchAction(success: String, action: suspend () -> String?) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val failure = try {
                action()
            } catch (exception: Exception) {
                exception.message ?: "Could not update presets"
            }
            busy = false
            message = failure ?: success
            isError = failure != null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Preset for this deck", color = secondary, fontSize = 12.sp)
        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { expanded = true }
                    .testTag("options-preset-selector"),
                color = surface,
                shape = RoundedCornerShape(if (desktop) 8.dp else 12.dp),
                border = BorderStroke(1.dp, border),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = if (desktop) 9.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(selected?.name ?: "Custom for this deck", Modifier.weight(1f), color = primary, fontSize = 13.sp)
                    Icon(Icons.Rounded.ArrowDropDown, null, tint = secondary)
                }
            }
            DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Custom for this deck") },
                    onClick = {
                        expanded = false
                        launchAction("Using deck-specific options") { onAssign(deckName, null) }
                    },
                )
                state.presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.name) },
                        onClick = {
                            expanded = false
                            launchAction("Using ${preset.name}") { onAssign(deckName, preset.id) }
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth().testTag("options-preset-name"),
            label = { Text("Preset name") },
            singleLine = true,
        )
        PresetButtons(
            selectedId = selected?.id,
            enabled = !busy,
            vertical = !desktop,
            create = { launchAction("Preset created") { onCreate(deckName, name, currentOptions()) } },
            clone = { id -> launchAction("Preset cloned") { onClone(deckName, id, name) } },
            rename = { id -> launchAction("Preset renamed") { onRename(id, name) } },
            delete = { id -> launchAction("Preset removed; deck options preserved") { onDelete(id) } },
        )
        message?.let {
            Text(it, color = if (isError) KelmaColors.Bad else KelmaColors.Good, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PresetButtons(
    selectedId: String?,
    enabled: Boolean,
    vertical: Boolean,
    create: () -> Unit,
    clone: (String) -> Unit,
    rename: (String) -> Unit,
    delete: (String) -> Unit,
) {
    val actions = @Composable { modifier: Modifier ->
        Button(create, enabled = enabled, modifier = modifier.testTag("options-preset-create")) { Text("Add") }
        OutlinedButton(
            onClick = { selectedId?.let(clone) },
            enabled = enabled && selectedId != null,
            modifier = modifier,
        ) { Text("Clone") }
        OutlinedButton(
            onClick = { selectedId?.let(rename) },
            enabled = enabled && selectedId != null,
            modifier = modifier,
        ) { Text("Rename") }
        OutlinedButton(
            onClick = { selectedId?.let(delete) },
            enabled = enabled && selectedId != null,
            modifier = modifier,
        ) { Text("Remove") }
    }
    if (vertical) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            actions(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            actions(Modifier.weight(1f))
        }
    }
}
