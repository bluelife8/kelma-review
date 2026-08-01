package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class UndoReviewConfirmationUiTest {
    @Test
    fun cancellingConfirmationDoesNotUndo() = runComposeUiTest {
        val undoCalled = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = testDeck(),
                    syncing = false,
                    canUndo = true,
                    onSync = {},
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = {
                        undoCalled.set(true)
                        null
                    },
                    onBack = {},
                )
            }
        }

        onNodeWithText("Undo").performClick()
        onNodeWithText("Undo last review?").assertExists()
        onNodeWithText("Cancel").performClick()
        onNodeWithText("Undo last review?").assertDoesNotExist()
        assertFalse(undoCalled.get())
    }

    @Test
    fun confirmationCanBeDisabledPerDeck() = runComposeUiTest {
        val undoCalled = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = testDeck(),
                    syncing = false,
                    canUndo = true,
                    options = DeckOptions(confirmBeforeUndo = false),
                    onSync = {},
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = {
                        undoCalled.set(true)
                        null
                    },
                    onBack = {},
                )
            }
        }

        onNodeWithText("Undo").performClick()
        waitUntil(timeoutMillis = 5_000) { undoCalled.get() }
        onNodeWithText("Undo last review?").assertDoesNotExist()
    }

    private fun testDeck() = DeckSummary(
        id = "Deck",
        name = "Deck",
        cards = listOf(ReviewCard(1, "front", "back")),
        newCount = 1,
        learningCount = 0,
        dueCount = 0,
    )
}
