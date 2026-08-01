package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncScreenUiTest {
    @Test
    fun detailedPersistentEntriesAndIndependentSyncActionAreVisible() = runComposeUiTest {
        val synced = AtomicBoolean(false)
        val redownloaded = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                SyncScreen(
                    entries = listOf(
                        SyncLogEntry(2, 3_723_000, SyncLogLevel.Warning, "DECK",
                            "delete: german34 · 0 cards · delete 2 cards/2 notes"),
                        SyncLogEntry(1, 3_720_000, SyncLogLevel.Info, "PREFLIGHT",
                            "Applied 3 changes and 0 removals"),
                    ),
                    signedIn = true,
                    syncing = false,
                    onDecks = {},
                    onAdd = {},
                    onBrowse = {},
                    onOptions = {},
                    onSync = { synced.set(true) },
                    onClear = {},
                    onRedownloadCollection = { redownloaded.set(true) },
                )
            }
        }

        onNodeWithText("KelmaSync activity").assertIsDisplayed()
        onNodeWithText("Sync").assertIsSelected()
        onNodeWithText("delete: german34 · 0 cards · delete 2 cards/2 notes").assertIsDisplayed()
        onNodeWithText("Sync now").performClick()
        assertTrue(synced.get())
        onNodeWithText("Redownload").performClick()
        onNodeWithText("Redownload collection?").assertIsDisplayed()
        onNodeWithText("Confirm redownload").performClick()
        assertTrue(redownloaded.get())
    }

    @Test
    fun activeProgressCannotBeClearedOutFromUnderItsLiveRow() = runComposeUiTest {
        setContent {
            KelmaTheme {
                SyncScreen(
                    entries = listOf(
                        SyncLogEntry(1, 1_000, SyncLogLevel.Info, "CARDS", "Uploading cards · 500 / 1,200"),
                    ),
                    signedIn = true,
                    syncing = true,
                    onDecks = {},
                    onAdd = {},
                    onBrowse = {},
                    onOptions = {},
                    onSync = {},
                    onClear = {},
                )
            }
        }

        onNodeWithText("Uploading cards · 500 / 1,200").assertIsDisplayed()
        onNodeWithText("Clear").assertIsNotEnabled()
    }
}
