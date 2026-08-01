package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GetSharedDecksDialogUiTest {
    @Test
    fun offersKelmaAndAnkiWebLinks() = runComposeUiTest {
        val opened = AtomicReference<String?>(null)
        setContent {
            KelmaTheme {
                GetSharedDecksDialog(onOpenUri = opened::set, onDismiss = {})
            }
        }

        onNodeWithText("Kelma").assertExists()
        onNodeWithText("AnkiWeb").assertExists()
        onNodeWithTag("shared-decks-kelma").performClick()
        assertEquals(KelmaSharedDecksUrl, opened.get())
        onNodeWithTag("shared-decks-ankiweb").performClick()
        assertEquals(AnkiWebSharedDecksUrl, opened.get())
    }
}
