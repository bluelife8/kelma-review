package tech.kelma.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp

private enum class MobileReviewMenuPage { Actions, Flags }

private val FrequentMobileReviewActions = listOf(
    ReviewMoreAction.SuspendCard,
    ReviewMoreAction.BuryCard,
    ReviewMoreAction.SuspendNote,
    ReviewMoreAction.BuryNote,
)

private val RemainingCardActions = listOf(
    ReviewMoreAction.FlagCard,
    ReviewMoreAction.ResetCard,
    ReviewMoreAction.SetDueDate,
    ReviewMoreAction.Options,
    ReviewMoreAction.CardInfo,
    ReviewMoreAction.PreviousCardInfo,
)

private val RemainingNoteActions = listOf(
    ReviewMoreAction.MarkNote,
    ReviewMoreAction.CreateCopy,
    ReviewMoreAction.DeleteNote,
)

private val MobileAudioActions = listOf(
    ReviewMoreAction.ReplayAudio,
    ReviewMoreAction.PauseAudio,
    ReviewMoreAction.AudioBackFive,
    ReviewMoreAction.AudioForwardFive,
    ReviewMoreAction.RecordOwnVoice,
    ReviewMoreAction.ReplayOwnVoice,
    ReviewMoreAction.AutoAdvance,
)

/** Touch-sized review actions anchored to the mobile review top bar. */
@Composable
internal fun MobileReviewMoreMenu(
    expanded: Boolean,
    currentFlag: Int,
    currentNoteMarked: Boolean,
    onDismiss: () -> Unit,
    onCommand: (ReviewMenuCommand) -> Unit,
) {
    var page by remember(expanded) { mutableStateOf(MobileReviewMenuPage.Actions) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 248.dp, max = 300.dp).testTag("mobile-review-more-menu"),
        containerColor = KelmaColors.SurfaceElevated,
    ) {
        when (page) {
            MobileReviewMenuPage.Actions -> {
                MobileReviewActionSection(
                    actions = FrequentMobileReviewActions,
                    currentNoteMarked = currentNoteMarked,
                    onAction = { action ->
                        onDismiss()
                        onCommand(ReviewMenuCommand.Action(action))
                    },
                )
                MobileMenuDivider()
                RemainingCardActions.forEach { action ->
                    MobileReviewActionRow(
                        action = action,
                        label = if (action == ReviewMoreAction.FlagCard) "Flag Card ›" else action.label,
                        onClick = {
                            if (action == ReviewMoreAction.FlagCard) {
                                page = MobileReviewMenuPage.Flags
                            } else {
                                onDismiss()
                                onCommand(ReviewMenuCommand.Action(action))
                            }
                        },
                    )
                }
                MobileMenuDivider()
                MobileReviewActionSection(
                    actions = RemainingNoteActions,
                    currentNoteMarked = currentNoteMarked,
                    onAction = { action ->
                        onDismiss()
                        onCommand(ReviewMenuCommand.Action(action))
                    },
                )
                MobileMenuDivider()
                MobileReviewActionSection(
                    actions = MobileAudioActions,
                    currentNoteMarked = currentNoteMarked,
                    onAction = { action ->
                        onDismiss()
                        onCommand(ReviewMenuCommand.Action(action))
                    },
                )
            }

            MobileReviewMenuPage.Flags -> {
                MobileMenuTextRow(
                    label = "‹ Card actions",
                    testTag = "mobile-review-flags-back",
                    onClick = { page = MobileReviewMenuPage.Actions },
                )
                MobileMenuDivider()
                ReviewFlag.entries.forEach { flag ->
                    MobileMenuTextRow(
                        label = if (flag.value == currentFlag) "✓  ${flag.label}" else flag.label,
                        testTag = "mobile-review-flag-${flag.name}",
                        accent = reviewFlagColor(flag.value),
                        onClick = {
                            onDismiss()
                            onCommand(ReviewMenuCommand.Flag(flag))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MobileReviewActionSection(
    actions: List<ReviewMoreAction>,
    currentNoteMarked: Boolean,
    onAction: (ReviewMoreAction) -> Unit,
) {
    actions.forEach { action ->
        MobileReviewActionRow(
            action = action,
            label = if (action == ReviewMoreAction.MarkNote && currentNoteMarked) {
                "Unmark Note"
            } else {
                action.label
            },
            onClick = { onAction(action) },
        )
    }
}

@Composable
private fun MobileReviewActionRow(
    action: ReviewMoreAction,
    label: String,
    onClick: () -> Unit,
) {
    MobileMenuTextRow(
        label = label,
        testTag = "mobile-review-more-${action.name}",
        onClick = onClick,
    )
}

@Composable
private fun MobileMenuTextRow(
    label: String,
    testTag: String,
    accent: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(Modifier.fillMaxWidth()) {
                if (accent != Color.Transparent) {
                    Text("●", color = accent, modifier = Modifier.widthIn(min = 24.dp))
                }
                Text(label, color = KelmaColors.TextPrimary, fontWeight = FontWeight.Medium)
            }
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).testTag(testTag),
    )
}

@Composable
private fun MobileMenuDivider() {
    HorizontalDivider(color = KelmaColors.SurfaceBorder)
}
