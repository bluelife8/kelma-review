package tech.kelma.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopReviewMoreMenuUiTest {
    @Test
    fun moreButtonOffersEveryCardNoteAndAudioAction() = runComposeUiTest {
        val selected = AtomicReference<ReviewMoreAction?>(null)
        val selectedFlag = AtomicReference<ReviewFlag?>(null)
        setContent {
            KelmaTheme {
                var expanded by remember { mutableStateOf(false) }
                Box(Modifier.width(900.dp).height(760.dp)) {
                    DesktopReviewFooter(
                        deck = DeckSummary("Deck", "Deck", emptyList(), 1, 2, 3),
                        showingAnswer = false,
                        savingReview = false,
                        canUndo = false,
                        ratingIntervals = emptyMap(),
                        onReveal = {},
                        onRate = {},
                        onUndo = {},
                        moreExpanded = expanded,
                        onMoreExpandedChange = { expanded = it },
                        onMoreAction = selected::set,
                        onFlag = selectedFlag::set,
                    )
                }
            }
        }

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-menu").assertWidthIsEqualTo(210.dp)
        ReviewMoreAction.entries.forEach { action ->
            onNodeWithText(action.label).assertExists()
        }
        val editTop = onNodeWithText("Edit").fetchSemanticsNode().boundsInRoot.top
        val suspendTop = onNodeWithText("Suspend Card").fetchSemanticsNode().boundsInRoot.top
        assertTrue(editTop < suspendTop, "Edit must sit above Suspend Card in the review menu")
        onNodeWithTag("review-more-FlagCard").performClick()
        ReviewFlag.entries.forEach { flag -> onNodeWithText(flag.label).assertExists() }
        onNodeWithTag("review-flag-Blue").performClick()
        assertEquals(ReviewFlag.Blue, selectedFlag.get())

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-FlagCard").performClick()
        onNodeWithTag("review-flag-None").performClick()
        assertEquals(ReviewFlag.None, selectedFlag.get())

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-CardInfo").performClick()
        assertEquals(ReviewMoreAction.CardInfo, selected.get())
    }
}
