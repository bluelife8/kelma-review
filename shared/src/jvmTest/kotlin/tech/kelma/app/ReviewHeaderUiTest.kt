package tech.kelma.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReviewHeaderUiTest {
    @Test
    fun mobileHeaderUsesCommandPalettePositionForUndo() = runComposeUiTest {
        val undone = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                Column {
                    ReviewHeader(
                        deck = DeckSummary("Deck", "Deck", emptyList(), 1, 2, 3),
                        undoEnabled = true,
                        onBack = {},
                        onUndo = { undone.set(true) },
                    )
                    MobileReviewControls(
                        showingAnswer = false,
                        savingReview = false,
                        ratingIntervals = emptyMap(),
                        onReveal = {},
                        onRate = {},
                    )
                }
            }
        }

        onNodeWithContentDescription("Commands").assertDoesNotExist()
        onNodeWithText("Undo last review").assertDoesNotExist()
        onNodeWithContentDescription("Undo last review").performClick()
        assertTrue(undone.get())
    }

    @Test
    fun mobileCardOptionsPutFrequentSuspendAndBuryActionsFirst() = runComposeUiTest {
        val selected = AtomicReference<ReviewMenuCommand?>(null)
        setContent {
            KelmaTheme {
                Box(Modifier.width(360.dp)) {
                    ReviewHeader(
                        deck = DeckSummary(
                            "Deck",
                            "A very long mobile deck name that must leave actions reachable",
                            emptyList(),
                            1,
                            2,
                            3,
                        ),
                        undoEnabled = false,
                        moreEnabled = true,
                        currentFlag = ReviewFlag.Blue.value,
                        currentNoteMarked = true,
                        onBack = {},
                        onUndo = {},
                        onMenuCommand = selected::set,
                    )
                }
            }
        }

        onNodeWithContentDescription("Card options").performClick()
        val orderedTags = listOf(
            "mobile-review-more-SuspendCard",
            "mobile-review-more-BuryCard",
            "mobile-review-more-SuspendNote",
            "mobile-review-more-BuryNote",
        )
        val tops = orderedTags.map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(tops.zipWithNext().all { (first, second) -> first < second })

        onNodeWithTag("mobile-review-more-SuspendCard").performClick()
        assertEquals(ReviewMenuCommand.Action(ReviewMoreAction.SuspendCard), selected.get())

        onNodeWithContentDescription("Card options").performClick()
        onNodeWithTag("mobile-review-more-FlagCard").performClick()
        onNodeWithText("✓  Blue").assertExists()
        onNodeWithTag("mobile-review-flag-Red").performClick()
        assertEquals(ReviewMenuCommand.Flag(ReviewFlag.Red), selected.get())
    }

    @Test
    fun mobileHeaderDisablesUndoWhenNoReviewCanBeUndone() = runComposeUiTest {
        setContent {
            KelmaTheme {
                ReviewHeader(
                    deck = DeckSummary("Deck", "Deck", emptyList(), 1, 2, 3),
                    undoEnabled = false,
                    onBack = {},
                    onUndo = {},
                )
            }
        }

        onNodeWithContentDescription("Undo last review").assertIsNotEnabled()
    }
}
