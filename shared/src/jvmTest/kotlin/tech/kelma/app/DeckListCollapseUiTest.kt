package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class DeckListCollapseUiTest {
    @Test
    fun desktopParentDeckCanCollapseAndExpandItsChildren() = runComposeUiTest {
        val opened = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                TestDesktopDeckList(
                    decks = listOf(
                        deckSummary("Parent"),
                        deckSummary("Parent::Child"),
                        deckSummary("Other"),
                    ),
                    onOpenDeck = { opened.set(true) },
                )
            }
        }

        onNodeWithTag("deck-row-Parent::Child").assertIsDisplayed()
        onNodeWithContentDescription("Collapse deck Parent").performClick()
        onNodeWithTag("deck-row-Parent::Child").assertDoesNotExist()
        assertFalse(opened.get())
        onNodeWithContentDescription("Expand deck Parent").performClick()
        onNodeWithTag("deck-row-Parent::Child").assertIsDisplayed()
    }
}

@Composable
private fun TestDesktopDeckList(
    decks: List<DeckSummary>,
    onOpenDeck: (DeckSummary) -> Unit,
) {
    var collapsedDeckIds by remember { mutableStateOf(emptySet<String>()) }
    DesktopDeckListScreen(
        decks = decks,
        signedIn = false,
        syncing = false,
        syncMessage = null,
        syncMessageIsError = false,
        studiedToday = 0,
        syncedCardCount = 0,
        localCardCount = 0,
        syncedMediaBytes = 0,
        canUndo = false,
        collapsedDeckIds = collapsedDeckIds,
        onDeckCollapsedChange = { deckId, collapsed ->
            collapsedDeckIds = if (collapsed) collapsedDeckIds + deckId else collapsedDeckIds - deckId
        },
        onUndo = {},
        onAdd = {},
        onCreateDeck = { null },
        deckManagement = DeckManagementActions(
            onAddCards = {},
            onBrowseCards = {},
            onOptions = {},
            onExport = {},
            onRename = { _, _ -> null },
            onDelete = { null },
        ),
        onOpenDeck = onOpenDeck,
        onSignIn = {},
        onSync = {},
    )
}

private fun deckSummary(name: String) = DeckSummary(
    id = name,
    name = name,
    cards = emptyList(),
    newCount = 0,
    learningCount = 0,
    dueCount = 0,
)
