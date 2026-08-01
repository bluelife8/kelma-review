package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class CollectionInterchangeDialogsUiTest {
    @Test
    fun exportDialogDefaultsToKelmaJsonAndOffersAllFormats() = runComposeUiTest {
        var exported: CollectionExportOptions? = null
        setContent {
            KelmaTheme {
                CollectionExportDialog(
                    deckNames = listOf("Deck"),
                    initialDeckName = "Deck",
                    onDismiss = {},
                    onExport = {
                        exported = it
                        null
                    },
                )
            }
        }

        onNodeWithText("Kelma JSON (.kelma.json)").assertIsDisplayed()
        onNodeWithText("Include scheduling information").assertIsDisplayed()
        onNodeWithText("Include deck presets").assertIsDisplayed()
        onNodeWithText("Include media").assertIsDisplayed()
        onNodeWithText("Support older Anki versions (slower/larger files)").assertDoesNotExist()
        onNodeWithTag("confirm-export").performClick()
        waitForIdle()

        val options = assertNotNull(exported)
        assertEquals(CollectionExportFormat.KelmaJson, options.format)
        assertEquals("Deck", options.deckName)
    }

    @Test
    fun importDialogPreviewsAndConfirmsConflictSafePlan() = runComposeUiTest {
        val plan = CollectionImportPlan(
            sourceName = "deck.apkg",
            decks = setOf("Deck"),
            notetypes = emptyList(),
            notes = listOf(
                ImportedNote(1, "guid", NotetypeCatalog.BasicId, listOf("front", "back"), emptyList()),
            ),
            cards = listOf(ImportedCard(2, 1, "Deck", 0)),
            reviews = emptyList(),
            media = emptyList(),
        )
        var imported: CollectionImportPlan? = null
        setContent {
            KelmaTheme {
                CollectionImportDialog(
                    document = InterchangeDocument("deck.apkg", byteArrayOf(1, 2, 3)),
                    deckNames = listOf("Deck"),
                    initialTextKind = TextImportKind.Notes,
                    onDismiss = {},
                    onPreview = { _, _ -> plan },
                    onImport = {
                        imported = it
                        null
                    },
                )
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("1 notes · 1 cards · 1 decks").fetchSemanticsNode() }.isSuccess
        }
        onNodeWithText("Existing GUIDs are merged only when content matches; conflicts are retained as copies.")
            .assertIsDisplayed()
        onNodeWithTag("confirm-import").performClick()
        waitForIdle()
        assertEquals(plan, imported)
    }
}
