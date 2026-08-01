package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BrowseScreenUiTest {
    private val collection = SyncedCollection(
        notes = mapOf(
            "n1" to SyncNote("n1", NotetypeCatalog.BasicId, listOf("<b>bonjour</b>", "hello"), listOf("french")),
            "n2" to SyncNote("n2", NotetypeCatalog.BasicId, listOf("hola", "hello"), listOf("spanish")),
        ),
        cards = mapOf(
            1L to SyncCard(1L, "n1", "French"),
            2L to SyncCard(2L, "n2", "Spanish"),
        ),
        notetypes = NotetypeCatalog.definitions,
        deckNames = setOf("French", "Spanish"),
    )

    @Test
    fun desktopBrowseSearchesSelectsAndShowsDetail() = runComposeUiTest {
        setContent {
            KelmaTheme {
                BrowseScreen(
                    collection = collection,
                    schedules = emptyMap(),
                    nowMillis = 1_704_067_200_000L,
                    syncing = false,
                    onBack = {},
                    onDecks = {},
                    onSync = {},
                    onAdd = {},
                    onStudyDeck = {},
                    onSaveEdit = { null },
                    onDeleteNote = {},
                )
            }
        }

        onAllNodesWithTag("browse-row-1", useUnmergedTree = true).assertCountEquals(1)
        onAllNodesWithTag("browse-row-2", useUnmergedTree = true).assertCountEquals(1)

        onNodeWithTag("browse-search").performTextInput("spanish")
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag("browse-row-1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        onAllNodesWithTag("browse-row-2", useUnmergedTree = true).assertCountEquals(1)

        onNodeWithTag("browse-row-2").performClick()
        onNodeWithTag("browse-detail").assertIsDisplayed()
        onNodeWithText("Note type").assertIsDisplayed()
    }

    @Test
    fun deckGearBrowseEntryStartsWithThatDeckFilter() = runComposeUiTest {
        setContent {
            KelmaTheme {
                BrowseScreen(
                    collection = collection,
                    schedules = emptyMap(),
                    nowMillis = 1_704_067_200_000L,
                    syncing = false,
                    initialQuery = browseQualifier("deck", "French"),
                    onBack = {},
                    onDecks = {},
                    onSync = {},
                    onAdd = {},
                    onStudyDeck = {},
                    onSaveEdit = { null },
                    onDeleteNote = {},
                )
            }
        }

        onNodeWithTag("browse-search").assertTextContains("deck:French")
        onAllNodesWithTag("browse-row-1", useUnmergedTree = true).assertCountEquals(1)
        onAllNodesWithTag("browse-row-2", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun scrollingLoadsTheNextPage() = runComposeUiTest {
        val notes = (1..130).associate { index ->
            "page-$index" to SyncNote(
                "page-$index",
                fields = listOf("front ${index.toString().padStart(3, '0')}", "back $index"),
            )
        }
        val cards = (1..130).associate { index ->
            index.toLong() to SyncCard(index.toLong(), "page-$index", "Deck")
        }
        setContent {
            KelmaTheme {
                BrowseScreen(
                    collection = SyncedCollection(
                        notes = notes,
                        cards = cards,
                        notetypes = NotetypeCatalog.definitions,
                        deckNames = setOf("Deck"),
                    ),
                    schedules = emptyMap(),
                    nowMillis = 1_704_067_200_000L,
                    syncing = false,
                    onBack = {},
                    onDecks = {},
                    onSync = {},
                    onAdd = {},
                    onStudyDeck = {},
                    onSaveEdit = { null },
                    onDeleteNote = {},
                )
            }
        }

        onNodeWithText("50 of 130 cards").assertIsDisplayed()
        onNodeWithTag("browse-list").performScrollToIndex(49)
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("100 of 130 cards").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("browse-list").performScrollToIndex(99)
        onNodeWithTag("browse-row-100").assertIsDisplayed()
    }

    @Test
    fun editShowsDownloadedNoteSourceAndSavesAllFields() = runComposeUiTest {
        val saved = AtomicReference<BrowseNoteEdit?>(null)
        setContent {
            KelmaTheme {
                BrowseScreen(
                    collection = collection,
                    schedules = emptyMap(),
                    nowMillis = 1_704_067_200_000L,
                    syncing = false,
                    onBack = {},
                    onDecks = {},
                    onSync = {},
                    onAdd = {},
                    onStudyDeck = {},
                    onSaveEdit = { edit -> saved.set(edit); null },
                    onDeleteNote = {},
                )
            }
        }

        onNodeWithTag("browse-row-1").performClick()
        onNodeWithText("Edit").performClick()
        onNodeWithText("EDIT NOTE").assertIsDisplayed()
        onNodeWithText("Cancel").assertIsDisplayed()
        val titleBounds = onNodeWithText("EDIT NOTE").fetchSemanticsNode().boundsInRoot
        val saveBounds = onNodeWithTag("browse-edit-save").fetchSemanticsNode().boundsInRoot
        val fieldTop = onNodeWithTag("browse-edit-field-0").fetchSemanticsNode().boundsInRoot.top
        assertTrue(titleBounds.bottom >= saveBounds.top && saveBounds.bottom >= titleBounds.top)
        assertTrue(saveBounds.bottom < fieldTop)
        onNodeWithTag("browse-edit-field-0").assertTextContains("<b>bonjour</b>")
        onNodeWithTag("browse-edit-field-0").performTextClearance()
        onNodeWithTag("browse-edit-field-0").performTextInput("salut")
        onNodeWithTag("browse-edit-save").performClick()

        waitUntil(timeoutMillis = 5_000) { saved.get() != null }
        assertEquals(listOf("salut", "hello"), saved.get()?.fields)
        assertEquals(listOf("french"), saved.get()?.tags)
    }
}
