package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DesktopAddFooter(state: AddUiState, actions: AddActions, onHelp: () -> Unit) {
    Surface(color = KelmaDesktopColors.Background) {
        Column {
            Surface(color = KelmaDesktopColors.Border, modifier = Modifier.fillMaxWidth().height(1.dp)) {}
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DesktopFooterButton("Help") { onHelp() }
                state.message?.let {
                    Icon(
                        if (state.messageIsError) Icons.Rounded.Close else Icons.Rounded.Check,
                        contentDescription = null,
                        tint = if (state.messageIsError) KelmaColors.Bad else KelmaDesktopColors.Due,
                        modifier = Modifier.padding(start = 16.dp).size(16.dp),
                    )
                    Text(
                        it,
                        modifier = Modifier.padding(start = 6.dp),
                        color = if (state.messageIsError) KelmaColors.Bad else KelmaDesktopColors.Due,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                DesktopHistoryMenu(state.history)
                DesktopFooterButton("Close") { actions.onBack() }
                Spacer(Modifier.width(10.dp))
                DesktopGoldButton(
                    label = if (state.saving) "Adding…" else "Add",
                    width = 150.dp,
                    height = 42.dp,
                    modifier = Modifier.testTag("add-save"),
                    onClick = { if (!state.saving) actions.onSave() },
                )
            }
        }
    }
}

@Composable
private fun DesktopHistoryMenu(history: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DesktopFooterButton("History", enabled = history.isNotEmpty()) { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(max = 380.dp),
        ) {
            Text(
                "Added this session",
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                color = KelmaDesktopColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            history.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry, color = KelmaDesktopColors.TextSecondary, fontSize = 13.sp, maxLines = 1) },
                    onClick = { expanded = false },
                )
            }
        }
    }
}

@Composable
private fun DesktopFooterButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .height(38.dp)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else Modifier),
        color = Color.Transparent,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, KelmaDesktopColors.Border),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                label,
                color = if (enabled) KelmaDesktopColors.TextSecondary else KelmaDesktopColors.TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
