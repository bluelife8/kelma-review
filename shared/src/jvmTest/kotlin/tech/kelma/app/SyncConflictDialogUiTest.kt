package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncConflictDialogUiTest {
    @Test
    fun noteConflictRequiresAnExplicitLocalOrServerChoice() = runComposeUiTest {
        var keptLocal = false
        var usedServer = false
        setContent {
            KelmaTheme {
                SyncConflictDialog(
                    conflict = SyncUploadConflict("note", "note-guid", "{}"),
                    onKeepLocal = { keptLocal = true },
                    onUseServer = { usedServer = true },
                )
            }
        }

        onNodeWithText("Sync conflict").assertIsDisplayed()
        onNodeWithText("Keep this device").performClick()
        assertTrue(keptLocal)
        assertTrue(!usedServer)
    }

    @Test
    fun immutableReviewConflictOnlyOffersTheServerCopy() = runComposeUiTest {
        var usedServer = false
        setContent {
            KelmaTheme {
                SyncConflictDialog(
                    conflict = SyncUploadConflict("review", "123", "{}"),
                    onKeepLocal = null,
                    onUseServer = { usedServer = true },
                )
            }
        }

        onNodeWithText("Keep this device").assertDoesNotExist()
        onNodeWithText("Use KelmaSync").performClick()
        assertTrue(usedServer)
    }
}
