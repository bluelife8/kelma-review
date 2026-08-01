package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MobileOptionsScreenUiTest {
    @Test
    fun mobileOptionsTabEditsAndPersistsFunctionalSettings() = runComposeUiTest {
        val saved = AtomicReference<Pair<String, DeckOptions>?>(null)
        val savedStudyDay = AtomicReference<Pair<String, Int>?>(null)
        setContent {
            KelmaTheme {
                MobileOptionsScreen(
                    deckNames = listOf("French"),
                    optionsByDeck = mapOf("French" to DeckOptions()),
                    syncing = false,
                    studyDayPolicy = AccountStudyDayPolicy(
                        version = 2,
                        timezoneId = "America/New_York",
                        dayStartHour = 4,
                    ),
                    signedIn = true,
                    initialDeckName = null,
                    onDecks = {},
                    onBrowse = {},
                    onAdd = {},
                    onSyncLog = {},
                    onSyncNow = {},
                    onSave = { deck, options -> saved.set(deck to options); null },
                    onSaveStudyDayPolicy = { timezone, hour ->
                        savedStudyDay.set(timezone to hour)
                        null
                    },
                )
            }
        }

        onNodeWithText("Deck options").assertIsDisplayed()
        onNodeWithTag("mobile-options-study-day-hour").performTextClearance()
        onNodeWithTag("mobile-options-study-day-hour").performTextInput("5")
        onNodeWithTag("mobile-options-save-study-day").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { savedStudyDay.get() != null }
        assertEquals("America/New_York" to 5, savedStudyDay.get())

        onNodeWithTag("mobile-options-new-limit").performTextClearance()
        onNodeWithTag("mobile-options-new-limit").performTextInput("30")
        onNodeWithTag("mobile-options-review-sort-order").performScrollTo().performClick()
        onNodeWithText("Latest added first").performClick()
        onNodeWithTag("mobile-options-confirm-undo").performScrollTo().performClick()
        onNodeWithTag("mobile-options-save").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { saved.get() != null }
        assertEquals("French", saved.get()?.first)
        assertEquals(30, saved.get()?.second?.newCardsPerDay)
        assertEquals(ReviewSortOrder.LatestAddedFirst, saved.get()?.second?.reviewSortOrder)
        assertEquals(false, saved.get()?.second?.confirmBeforeUndo)
    }
}
