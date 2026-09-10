package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MobileBrowseDeckPickerUiTest {
    @Test
    fun prominentPickerOffersRegularDecksAndHierarchicalSuperdecks() = runComposeUiTest {
        val selected = AtomicReference<String?>(null)
        setContent {
            KelmaTheme {
                MobileBrowseDeckPicker(
                    selectedDeck = null,
                    decks = listOf("Solo" to 1, "x" to 3, "x::y" to 2),
                    onSelectDeck = selected::set,
                )
            }
        }

        onNodeWithTag("mobile-browse-deck-picker").assertIsDisplayed().performClick()
        onNodeWithTag("mobile-browse-deck-Solo").assertIsDisplayed()
        onNodeWithTag("mobile-browse-deck-x").assertIsDisplayed()
        onNodeWithTag("mobile-browse-deck-x::y").assertIsDisplayed().performClick()

        assertEquals("x::y", selected.get())
    }
}
