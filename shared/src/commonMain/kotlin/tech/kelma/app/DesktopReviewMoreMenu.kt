package tech.kelma.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class ReviewFlag(val label: String, val value: Int, val shortcut: String) {
    None("No Flag", 0, "⌘0"),
    Red("Red", 1, "⌘1"),
    Orange("Orange", 2, "⌘2"),
    Green("Green", 3, "⌘3"),
    Blue("Blue", 4, "⌘4"),
    Pink("Pink", 5, "⌘5"),
    Turquoise("Turquoise", 6, "⌘6"),
    Purple("Purple", 7, "⌘7"),
}

internal fun reviewFlagColor(value: Int): Color = when (value) {
    1 -> Color(0xFFE05252)
    2 -> Color(0xFFE58A3A)
    3 -> Color(0xFF54B96B)
    4 -> Color(0xFF5B8DEF)
    5 -> Color(0xFFE56AA6)
    6 -> Color(0xFF4FC4C4)
    7 -> Color(0xFF9B6CE3)
    else -> Color.Transparent
}

internal sealed interface ReviewMenuCommand {
    data class Action(val action: ReviewMoreAction) : ReviewMenuCommand
    data class Flag(val flag: ReviewFlag) : ReviewMenuCommand
}

internal enum class ReviewMoreAction(val label: String) {
    FlagCard("Flag Card"),
    BuryCard("Bury Card"),
    ResetCard("Reset Card…"),
    SetDueDate("Set Due Date…"),
    EditNote("Edit"),
    SuspendCard("Suspend Card"),
    Options("Options"),
    CardInfo("Card Info"),
    PreviousCardInfo("Previous Card Info"),
    MarkNote("Mark Note"),
    BuryNote("Bury Note"),
    SuspendNote("Suspend Note"),
    CreateCopy("Create Copy…"),
    DeleteNote("Delete Note"),
    ReplayAudio("Replay Audio"),
    PauseAudio("Pause Audio"),
    AudioBackFive("Audio -5s"),
    AudioForwardFive("Audio +5s"),
    RecordOwnVoice("Record Own Voice"),
    ReplayOwnVoice("Replay Own Voice"),
    AutoAdvance("Auto Advance"),
}

private data class ReviewMoreItem(
    val action: ReviewMoreAction,
    val shortcut: String = "",
    val submenu: Boolean = false,
)

private val CardActions = listOf(
    ReviewMoreItem(ReviewMoreAction.BuryCard, "-"),
    ReviewMoreItem(ReviewMoreAction.ResetCard, "⌥⌘N"),
    ReviewMoreItem(ReviewMoreAction.SetDueDate, "⇧⌘D"),
    ReviewMoreItem(ReviewMoreAction.EditNote, "E"),
    ReviewMoreItem(ReviewMoreAction.SuspendCard, "@"),
    ReviewMoreItem(ReviewMoreAction.Options, "O"),
    ReviewMoreItem(ReviewMoreAction.CardInfo, "I"),
    ReviewMoreItem(ReviewMoreAction.PreviousCardInfo, "⌥⌘I"),
)
private val NoteActions = listOf(
    ReviewMoreItem(ReviewMoreAction.MarkNote, "*"),
    ReviewMoreItem(ReviewMoreAction.BuryNote, "="),
    ReviewMoreItem(ReviewMoreAction.SuspendNote, "!"),
    ReviewMoreItem(ReviewMoreAction.CreateCopy, "⌥⌘E"),
    ReviewMoreItem(ReviewMoreAction.DeleteNote, "⌘⌫"),
)
private val AudioActions = listOf(
    ReviewMoreItem(ReviewMoreAction.ReplayAudio, "R"),
    ReviewMoreItem(ReviewMoreAction.PauseAudio, "5"),
    ReviewMoreItem(ReviewMoreAction.AudioBackFive, "6"),
    ReviewMoreItem(ReviewMoreAction.AudioForwardFive, "7"),
    ReviewMoreItem(ReviewMoreAction.RecordOwnVoice, "⇧V"),
    ReviewMoreItem(ReviewMoreAction.ReplayOwnVoice, "V"),
    ReviewMoreItem(ReviewMoreAction.AutoAdvance, "⇧A"),
)

@Composable
internal fun DesktopReviewMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (ReviewMoreAction) -> Unit,
    currentFlag: Int = 0,
    currentNoteMarked: Boolean = false,
    autoAdvanceEnabled: Boolean = false,
    onFlag: (ReviewFlag) -> Unit = {},
) {
    var flagsExpanded by remember(expanded) { mutableStateOf(false) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(210.dp).testTag("review-more-menu"),
        containerColor = Color(0xFF303030),
    ) {
        Box {
            ReviewMoreRow(
                item = ReviewMoreItem(ReviewMoreAction.FlagCard, submenu = true),
                onClick = { flagsExpanded = true },
            )
            DropdownMenu(
                expanded = flagsExpanded,
                onDismissRequest = { flagsExpanded = false },
                modifier = Modifier.width(140.dp).testTag("review-flag-menu"),
                offset = DpOffset(x = (-142).dp, y = (-4).dp),
                containerColor = Color(0xFF303030),
            ) {
                ReviewFlag.entries.forEach { flag ->
                    ReviewMenuRow(
                        label = flag.label,
                        shortcut = if (flag.value == currentFlag) "✓  ${flag.shortcut}" else flag.shortcut,
                        testTag = "review-flag-${flag.name}",
                        onClick = {
                            flagsExpanded = false
                            onDismiss()
                            onFlag(flag)
                        },
                    )
                }
            }
        }
        ReviewMoreSection(CardActions, onDismiss, onAction)
        HorizontalDivider(color = Color(0xFF484848))
        ReviewMoreSection(NoteActions, onDismiss, onAction, currentNoteMarked)
        HorizontalDivider(color = Color(0xFF484848))
        ReviewMoreSection(
            AudioActions,
            onDismiss,
            onAction,
            autoAdvanceEnabled = autoAdvanceEnabled,
        )
    }
}

@Composable
private fun ReviewMoreSection(
    items: List<ReviewMoreItem>,
    onDismiss: () -> Unit,
    onAction: (ReviewMoreAction) -> Unit,
    currentNoteMarked: Boolean = false,
    autoAdvanceEnabled: Boolean = false,
) {
    items.forEach { item ->
        ReviewMoreRow(
            item = item,
            label = when {
                item.action == ReviewMoreAction.MarkNote && currentNoteMarked -> "Unmark Note"
                item.action == ReviewMoreAction.AutoAdvance && autoAdvanceEnabled -> "Disable Auto Advance"
                else -> item.action.label
            },
        ) {
            onDismiss()
            onAction(item.action)
        }
    }
}

@Composable
private fun ReviewMoreRow(
    item: ReviewMoreItem,
    label: String = item.action.label,
    onClick: () -> Unit,
) {
    ReviewMenuRow(
        label = label,
        shortcut = if (item.submenu) "▶" else item.shortcut,
        testTag = "review-more-${item.action.name}",
        onClick = onClick,
    )
}

@Composable
private fun ReviewMenuRow(
    label: String,
    shortcut: String,
    testTag: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(shortcut, color = Color.White, fontSize = 11.sp)
            }
        },
        modifier = Modifier.height(21.dp).testTag(testTag),
        onClick = onClick,
    )
}
