package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MobileNavigationUiTest {
    @Test
    fun bottomNavigationKeepsFourPrimaryDestinationsReadableAndActionable() = runComposeUiTest {
        val opened = AtomicReference<MobileCollectionTab?>(null)
        setContent {
            KelmaTheme {
                MobileBottomNavigation(
                    selected = MobileCollectionTab.Decks,
                    onDecks = { opened.set(MobileCollectionTab.Decks) },
                    onBrowse = { opened.set(MobileCollectionTab.Browse) },
                    onAdd = { opened.set(MobileCollectionTab.Add) },
                    onOptions = { opened.set(MobileCollectionTab.Options) },
                    onSyncLog = { opened.set(MobileCollectionTab.Sync) },
                )
            }
        }

        listOf("Decks", "Browse", "Add", "Options", "Sync").forEach {
            onNodeWithText(it).assertIsDisplayed()
            onNodeWithContentDescription(it, useUnmergedTree = true).assertIsDisplayed()
        }
        onNodeWithText("Sync").performClick()
        assertEquals(MobileCollectionTab.Sync, opened.get())
    }
}
