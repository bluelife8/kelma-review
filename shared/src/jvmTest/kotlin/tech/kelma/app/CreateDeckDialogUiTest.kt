package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CreateDeckDialogUiTest {
    @Test
    fun deckGearOffersStyledFunctionalActionsForLocallyManagedDecks() = runComposeUiTest {
        val addedTo = AtomicReference<String?>(null)
        val browsed = AtomicReference<String?>(null)
        val optioned = AtomicReference<String?>(null)
        val exported = AtomicReference<String?>(null)
        val collectionExported = AtomicReference(false)
        val renamed = AtomicReference<Pair<String, String>?>(null)
        val deleted = AtomicReference<String?>(null)
        val deck = DeckSummary("Local", "Local", emptyList(), 0, 0, 0)
        val downloadedDeck = DeckSummary("Downloaded", "Downloaded", emptyList(), 0, 0, 0)
        setContent {
            KelmaTheme {
                DesktopDeckListScreen(
                    decks = listOf(deck, downloadedDeck),
                    signedIn = false,
                    syncing = false,
                    syncMessage = null,
                    syncMessageIsError = false,
                    studiedToday = 0,
                    syncedCardCount = 0,
                    localCardCount = 0,
                    syncedMediaBytes = 0,
                    canUndo = false,
                    onUndo = {},
                    onAdd = {},
                    onCreateDeck = { null },
                    deckManagement = DeckManagementActions(
                        onAddCards = { addedTo.set(it) },
                        onBrowseCards = { browsed.set(it) },
                        onOptions = { optioned.set(it) },
                        onExport = { exported.set(it) },
                        onRename = { old, new -> renamed.set(old to new); null },
                        onDelete = { name -> deleted.set(name); null },
                    ),
                    onExportCollection = { collectionExported.set(true) },
                    onOpenDeck = {},
                    onSignIn = {},
                    onSync = {},
                )
            }
        }

        onNodeWithContentDescription("Sync now").assertIsDisplayed()
        onNodeWithText("Ctrl/Cmd+S").assertIsDisplayed()
        onNodeWithText("Export").assertIsDisplayed().performClick()
        assertEquals(true, collectionExported.get())
        onNodeWithContentDescription("Deck options for Local").performClick()
        onNodeWithTag("deck-menu-rename").assertIsDisplayed().assertIsEnabled()
        onNodeWithTag("deck-menu-options").assertIsDisplayed().assertIsEnabled()
        onNodeWithTag("deck-menu-export").assertIsDisplayed().assertIsEnabled()
        onNodeWithText("Add cards").assertIsDisplayed()
        onNodeWithText("Browse cards").assertIsDisplayed()
        onNodeWithText("Delete").assertIsDisplayed()

        onNodeWithTag("deck-menu-options").performClick()
        assertEquals("Local", optioned.get())
        onNodeWithContentDescription("Deck options for Local").performClick()
        onNodeWithTag("deck-menu-export").performClick()
        assertEquals("Local", exported.get())
        onNodeWithContentDescription("Deck options for Local").performClick()
        onNodeWithText("Add cards").performClick()
        assertEquals("Local", addedTo.get())
        onNodeWithContentDescription("Deck options for Local").performClick()
        onNodeWithText("Browse cards").performClick()
        assertEquals("Local", browsed.get())
        onNodeWithContentDescription("Deck options for Local").performClick()
        onNodeWithText("Rename").performClick()
        onNodeWithTag("rename-deck-name").performTextReplacement("Study")
        onNodeWithText("Rename").performClick()
        waitUntil { renamed.get() != null }
        assertEquals("Local" to "Study", renamed.get())

        onNodeWithContentDescription("Deck options for Local").performClick()
        onNodeWithText("Delete").performClick()
        onNodeWithTag("delete-deck-confirm").performClick()
        waitUntil { deleted.get() != null }
        assertEquals("Local", deleted.get())

        onNodeWithContentDescription("Deck options for Downloaded").performClick()
        onNodeWithText("Add cards").assertIsDisplayed()
        onNodeWithText("Browse cards").assertIsDisplayed()
        onNodeWithTag("deck-menu-rename").assertIsDisplayed().assertIsEnabled()
        onNodeWithTag("deck-menu-options").assertIsDisplayed().assertIsEnabled()
        onNodeWithTag("deck-menu-export").assertIsDisplayed().assertIsEnabled()
        onNodeWithTag("deck-menu-delete").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun desktopCreateDeckControlCreatesNamedDeckInsteadOfOpeningAdd() = runComposeUiTest {
        val created = AtomicReference<String?>(null)
        setContent {
            KelmaTheme {
                DesktopDeckListScreen(
                    decks = emptyList(),
                    signedIn = false,
                    syncing = false,
                    syncMessage = null,
                    syncMessageIsError = false,
                    studiedToday = 0,
                    syncedCardCount = 0,
                    localCardCount = 0,
                    syncedMediaBytes = 0,
                    canUndo = false,
                    onUndo = {},
                    onAdd = {},
                    onCreateDeck = { name -> created.set(name); null },
                    deckManagement = DeckManagementActions(
                        onAddCards = {},
                        onBrowseCards = {},
                        onOptions = {},
                        onExport = {},
                        onRename = { _, _ -> null },
                        onDelete = { null },
                    ),
                    onOpenDeck = {},
                    onSignIn = {},
                    onSync = {},
                )
            }
        }

        onNodeWithText("Create Card").assertDoesNotExist()
        onNodeWithText("Create Deck").performClick()
        onNodeWithText("Create deck").assertIsDisplayed()
        onNodeWithTag("create-deck-name").performTextInput("Languages :: Verbs")
        onNodeWithText("Create", useUnmergedTree = true).performClick()
        waitUntil { created.get() != null }

        assertEquals("Languages::Verbs", created.get())
        onNodeWithText("Create deck").assertDoesNotExist()
    }
}
