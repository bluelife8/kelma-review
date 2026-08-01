package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopOptionsScreenUiTest {
    @Test
    fun optionsTabEditsFunctionalFieldsAndGreysUnavailableKelmaDesktopFields() = runComposeUiTest {
        val saved = AtomicReference<Pair<String, DeckOptions>?>(null)
        val savedStudyDay = AtomicReference<Pair<String, Int>?>(null)
        val commandsOpened = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                DesktopOptionsScreen(
                    deckNames = listOf("French", "Spanish"),
                    optionsByDeck = mapOf("French" to DeckOptions(newCardsPerDay = 20)),
                    syncing = false,
                    studyDayPolicy = AccountStudyDayPolicy(
                        version = 2,
                        timezoneId = "America/New_York",
                        dayStartHour = 4,
                    ),
                    signedIn = true,
                    onDecks = {},
                    onAdd = {},
                    onBrowse = {},
                    onSync = {},
                    onCommands = { commandsOpened.set(true) },
                    onSave = { deck, options -> saved.set(deck to options); null },
                    onSaveStudyDayPolicy = { timezone, hour ->
                        savedStudyDay.set(timezone to hour)
                        null
                    },
                )
            }
        }

        onNodeWithText("OPTIONS").assertIsDisplayed()
        onNodeWithTag("desktop-command-palette").performClick()
        assertTrue(commandsOpened.get())
        val browseBounds = onNodeWithText("Browse").fetchSemanticsNode().boundsInRoot
        val optionsBounds = onNodeWithText("Options").fetchSemanticsNode().boundsInRoot
        val statsBounds = onNodeWithText("Stats").fetchSemanticsNode().boundsInRoot
        assertTrue(browseBounds.right <= optionsBounds.left)
        assertTrue(optionsBounds.right <= statsBounds.left)
        onNodeWithText("Automatically play audio").performScrollTo().assertIsDisplayed()
        onNodeWithTag("optimizer-start").assertIsEnabled()
        onNodeWithText("Custom scheduling").assertIsNotEnabled()

        onNodeWithTag("options-study-day-hour").performScrollTo().performTextClearance()
        onNodeWithTag("options-study-day-hour").performTextInput("5")
        onNodeWithTag("options-save-study-day").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { savedStudyDay.get() != null }
        assertEquals("America/New_York" to 5, savedStudyDay.get())

        onNodeWithTag("options-new-limit").performTextClearance()
        onNodeWithTag("options-new-limit").performTextInput("35")
        onNodeWithTag("options-retention").performTextClearance()
        onNodeWithTag("options-retention").performTextInput("92")
        onNodeWithTag("options-new-mix-order").performScrollTo().performClick()
        onNodeWithText("Show before reviews").performClick()
        onNodeWithTag("options-bury-interday").performScrollTo().performClick()
        onNodeWithTag("options-confirm-undo").performScrollTo().performClick()
        onNodeWithTag("options-save").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { saved.get() != null }

        assertEquals("French", saved.get()?.first)
        assertEquals(35, saved.get()?.second?.newCardsPerDay)
        assertEquals(0.92, saved.get()?.second?.desiredRetention)
        assertEquals(QueueMixOrder.BeforeReviews, saved.get()?.second?.newReviewMixOrder)
        assertTrue(saved.get()?.second?.buryInterdayLearningSiblings == true)
        assertEquals(false, saved.get()?.second?.confirmBeforeUndo)
        onNodeWithText("Options saved on this device").assertIsDisplayed()
    }
}
