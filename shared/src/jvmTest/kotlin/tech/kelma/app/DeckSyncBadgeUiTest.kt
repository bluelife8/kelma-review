package tech.kelma.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DeckSyncBadgeUiTest {
    private val pending = PendingDeckChanges(
        addedCardIds = setOf(1),
        changedCardIds = setOf(2, 3),
    )

    @Test
    fun pendingBadgeHasReadableCenteredBounds() = runComposeUiTest {
        setContent { KelmaTheme { DeckSyncBadge(pending, Modifier.testTag("deck-sync-badge")) } }

        onNodeWithTag("deck-sync-badge", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(40.dp)
            .assertHeightIsEqualTo(20.dp)
    }

    @Test
    fun mobileDeckRowDisplaysSyncBadge() = runComposeUiTest {
        val deck = DeckSummary("Arabic", "Arabic", emptyList(), 0, 0, 0, pending)
        setContent {
            KelmaTheme {
                Box(Modifier.width(360.dp)) {
                    MobileDeckRow(deck, onClick = {})
                }
            }
        }

        onNodeWithText("+1 ~2").assertIsDisplayed()
    }

    @Test
    fun mobileLeafDeckKeepsComfortableHeightWithoutReservingDisclosureSpace() = runComposeUiTest {
        setContent {
            KelmaTheme {
                Column(Modifier.width(360.dp)) {
                    MobileDeckRow(
                        deck = DeckSummary("Parent", "Parent", emptyList(), 0, 0, 0),
                        hasChildren = true,
                        onClick = {},
                    )
                    MobileDeckRow(
                        deck = DeckSummary("Standalone", "Standalone", emptyList(), 0, 0, 0),
                        onClick = {},
                    )
                }
            }
        }

        onNodeWithText("Standalone").assertHeightIsEqualTo(48.dp)
        val parentLeft = onNodeWithText("Parent", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        val leafLeft = onNodeWithText("Standalone", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertTrue(leafLeft < parentLeft)
    }

    @Test
    fun mobileParentDeckHasIndependentCollapseControl() = runComposeUiTest {
        val collapsed = AtomicBoolean(false)
        val opened = AtomicBoolean(false)
        val deck = DeckSummary("Parent", "Parent", emptyList(), 0, 0, 0)
        setContent {
            KelmaTheme {
                Box(Modifier.width(360.dp)) {
                    MobileDeckRow(
                        deck = deck,
                        hasChildren = true,
                        onToggleCollapsed = { collapsed.set(true) },
                        onClick = { opened.set(true) },
                    )
                }
            }
        }

        onNodeWithContentDescription("Collapse deck Parent").performClick()

        assertTrue(collapsed.get())
        assertFalse(opened.get())
    }
}
