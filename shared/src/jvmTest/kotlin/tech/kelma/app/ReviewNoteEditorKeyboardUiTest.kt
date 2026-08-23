package tech.kelma.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ReviewNoteEditorKeyboardUiTest {
    @Test
    fun doneImeActionClearsEditorFocus() = runComposeUiTest {
        val target = BrowseEditTarget(
            row = BrowseCardRow(
                cardId = 1L,
                noteGuid = "note-1",
                question = "front",
                answer = "back",
                deck = "Deck",
                notetype = "Basic",
                tags = emptyList(),
                state = BrowseCardState.New,
                dueMillis = null,
                isLocal = true,
            ),
            fieldNames = listOf("Front", "Back"),
            values = listOf("front", "back"),
        )
        setContent {
            KelmaTheme {
                BrowseInlineEditor(
                    target = target,
                    titleColor = KelmaColors.TextMuted,
                    textSecondary = KelmaColors.TextSecondary,
                    accent = KelmaColors.Gold,
                    surfaceColor = KelmaColors.Surface,
                    borderColor = KelmaColors.SurfaceBorder,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = 14.dp,
                    onAttach = { it.filename },
                    onSave = { null },
                    onSaved = {},
                    onCancel = {},
                )
            }
        }

        val field = onNodeWithTag("browse-edit-field-0")
        field.performClick()
        field.assertIsFocused()
        field.performImeAction()
        field.assertIsNotFocused()
    }
}
