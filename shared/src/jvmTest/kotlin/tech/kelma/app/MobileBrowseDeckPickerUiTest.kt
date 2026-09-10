package tech.kelma.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.input.TextFieldValue
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MobileBrowseDeckPickerUiTest {
    @Test
    fun primaryPickerExcludesSubdecksAndSelectedDeckRevealsThem() = runComposeUiTest {
        val selected = AtomicReference<String?>(null)
        setContent {
            KelmaTheme {
                var selectedName by remember { mutableStateOf<String?>(null) }
                MobileDeckFilterPicker(
                    options = deckPickerOptions(
                        deckNames = listOf("Solo", "x::y", "x::z"),
                        directCardCounts = listOf("Solo" to 1, "x::y" to 2, "x::z" to 1),
                    ),
                    selectedName = selectedName,
                    testTagPrefix = "mobile-browse",
                    onSelectName = { name ->
                        selectedName = name
                        selected.set(name)
                    },
                )
            }
        }

        onNodeWithTag("mobile-browse-deck-picker").assertIsDisplayed().performClick()
        onNodeWithTag("mobile-browse-deck-Solo").assertIsDisplayed().performClick()
        assertEquals("Solo", selected.get())
        onNodeWithTag("mobile-browse-subdeck-picker").assertDoesNotExist()

        onNodeWithTag("mobile-browse-deck-picker").performClick()
        onNodeWithTag("mobile-browse-deck-x").assertIsDisplayed()
        onNodeWithTag("mobile-browse-deck-x::y").assertDoesNotExist()
        onNodeWithTag("mobile-browse-deck-x").performClick()
        assertEquals("x", selected.get())

        onNodeWithTag("mobile-browse-subdeck-picker").assertIsDisplayed().performClick()
        onNodeWithTag("mobile-browse-subdeck-all").assertIsDisplayed()
        onNodeWithTag("mobile-browse-subdeck-x::y").assertIsDisplayed().performClick()
        assertEquals("x::y", selected.get())

        onNodeWithTag("mobile-browse-subdeck-picker").performClick()
        onNodeWithTag("mobile-browse-subdeck-all").performClick()
        assertEquals("x", selected.get())
    }

    @Test
    fun deckPickerAppearsAboveSearch() = runComposeUiTest {
        setContent {
            KelmaTheme {
                MobileBrowseScreen(
                    state = BrowseUiState(
                        rows = emptyList(),
                        totalCount = 0,
                        query = TextFieldValue(),
                        sorting = BrowseSorting(),
                        selected = null,
                        selectedCard = null,
                        selectedEdit = null,
                        decks = listOf("Solo" to 1),
                        tags = emptyList(),
                        nowMillis = 0,
                    ),
                    actions = emptyBrowseActions(),
                )
            }
        }

        val pickerBottom = onNodeWithTag("mobile-browse-deck-picker").fetchSemanticsNode().boundsInRoot.bottom
        val searchTop = onNodeWithTag("browse-search").fetchSemanticsNode().boundsInRoot.top
        assertTrue(pickerBottom <= searchTop)
    }
}

private fun emptyBrowseActions() = BrowseActions(
    onQueryChange = {},
    onApplyTerm = {},
    onSelectDeck = {},
    onSort = {},
    onSelect = {},
    onLoadMore = {},
    onBack = {},
    onDecks = {},
    onSync = {},
    onOpenSync = {},
    onAdd = {},
    onOptions = {},
    onStudyDeck = {},
    onAttach = { "" },
    onSaveEdit = { null },
    onDelete = {},
)
