package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.input.ImeAction
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ReviewNoteEditorKeyboardUiTest {
    @Test
    fun mobileDoneToolbarDismissesKeyboardWithoutReplacingMultilineReturn() = runComposeUiTest {
        setContent {
            KelmaTheme {
                ReviewNoteEditor(
                    target = reviewEditTarget(),
                    onAttach = { it.filename },
                    onSave = { null },
                    onClose = {},
                    desktop = false,
                )
            }
        }

        onNodeWithTag("review-editor-keyboard-done").assertDoesNotExist()
        val field = onNodeWithTag("browse-edit-field-0")
        field.assert(hasImeAction(ImeAction.Default))
        field.performClick()
        field.assertIsFocused()
        field.performTextReplacement("front\nsecond line")
        field.assertTextContains("front\nsecond line")

        onNodeWithTag("review-editor-keyboard-toolbar").assertIsDisplayed()
        onNodeWithTag("review-editor-keyboard-done").assertIsDisplayed().performClick()
        field.assertIsNotFocused()
        onNodeWithTag("review-editor-keyboard-done").assertDoesNotExist()
        onNodeWithTag("browse-edit-tags").assert(hasImeAction(ImeAction.Done))
    }

    private fun reviewEditTarget() = BrowseEditTarget(
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
}
