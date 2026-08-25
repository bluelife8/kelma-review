package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class DeckListUndoConfirmationUiTest {
    @Test
    fun decksUndoUsesConfirmationSetting() = runComposeUiTest {
        val undoCalled = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                TestDeckList(confirmBeforeUndo = true) { undoCalled.set(true) }
            }
        }

        onNodeWithText("Undo").performClick()
        onNodeWithText("Undo last review?").assertExists()
        assertFalse(undoCalled.get())
        onNodeWithText("Undo review").performClick()
        waitUntil(timeoutMillis = 5_000) { undoCalled.get() }
    }

    @Test
    fun decksUndoCanSkipConfirmation() = runComposeUiTest {
        val undoCalled = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                TestDeckList(confirmBeforeUndo = false) { undoCalled.set(true) }
            }
        }

        onNodeWithText("Undo").performClick()
        waitUntil(timeoutMillis = 5_000) { undoCalled.get() }
        onNodeWithText("Undo last review?").assertDoesNotExist()
    }
}

@Composable
private fun TestDeckList(
    confirmBeforeUndo: Boolean,
    onUndo: () -> Unit,
) {
    DeckListScreen(
        decks = listOf(DeckSummary("Deck", "Deck", emptyList(), 0, 0, 0)),
        signedIn = false,
        activeAccountUsername = null,
        syncing = false,
        syncMessage = null,
        syncMessageIsError = false,
        studiedToday = 1,
        syncedCardCount = 1,
        localCardCount = 0,
        syncedMediaBytes = 0,
        canUndo = true,
        confirmBeforeUndo = confirmBeforeUndo,
        onUndo = onUndo,
        onAdd = {},
        onCreateDeck = { null },
        deckManagement = DeckManagementActions({}, {}, {}, {}, { _, _ -> null }, { null }),
        onBrowse = {},
        onOptions = {},
        onImportFile = {},
        onExportCollection = {},
        onOpenDeck = {},
        onSignIn = {},
        onSync = {},
        onOpenSync = {},
        onSwitchAccount = {},
        onSignOut = {},
        onRemoveFromDevice = {},
    )
}
