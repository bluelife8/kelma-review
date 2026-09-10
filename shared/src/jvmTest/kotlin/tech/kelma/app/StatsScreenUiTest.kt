package tech.kelma.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StatsScreenUiTest {
    @Test
    fun desktopStatsShowsHistoryAndToolbarNavigationIsFunctional() = runComposeUiTest {
        val opened = AtomicBoolean(false)
        val requestedDeck = AtomicReference<String?>(null)
        val stats = StudyStats(
            totalReviews = 120,
            reviewsToday = 12,
            studiedMillisToday = 600_000,
            recalledReviews = 90,
            forgottenReviews = 10,
            currentStreakDays = 5,
            cards = 50,
            dueCards = 7,
            daily = List(30) { DailyStudyStats(it.toLong(), it % 4, 1_000) },
        )
        setContent {
            CompositionLocalProvider(LocalOpenStats provides { opened.set(true) }) {
                KelmaTheme {
                    StatsScreen(
                        stats = stats,
                        syncing = false,
                        onDecks = {},
                        onAdd = {},
                        onBrowse = {},
                        onOptions = {},
                        onSync = {},
                        deckNames = listOf("Languages::French", "Solo"),
                        loadDeckStats = { deckName ->
                            requestedDeck.set(deckName)
                            StudyStats(totalReviews = 4, reviewsToday = 3, cards = 2)
                        },
                    )
                }
            }
        }

        onNodeWithText("Study history").assertIsDisplayed()
        onNodeWithText("12").assertIsDisplayed()
        onNodeWithText("90%").assertIsDisplayed()
        onNodeWithText("Due now").assertIsDisplayed()
        onNodeWithTag("stats-daily-chart").assertIsDisplayed()

        onNodeWithTag("stats-deck-picker").performClick()
        onNodeWithTag("stats-deck-Languages").assertIsDisplayed()
        onNodeWithTag("stats-deck-Languages::French").performClick()
        waitUntil(timeoutMillis = 5_000) { requestedDeck.get() == "Languages::French" }
        onNodeWithTag("stats-deck-picker").assertTextContains("Languages::French")
        onNodeWithText("4 reviews").assertIsDisplayed()

        onNodeWithText("Stats").performClick()
        assertTrue(opened.get())
    }
}
