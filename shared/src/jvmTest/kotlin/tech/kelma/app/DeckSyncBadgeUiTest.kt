package tech.kelma.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

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
}
