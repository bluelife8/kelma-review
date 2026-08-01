package tech.kelma.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StatsScreenUiTest {
    @Test
    fun desktopStatsShowsHistoryAndToolbarNavigationIsFunctional() = runComposeUiTest {
        val opened = AtomicBoolean(false)
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
                    StatsScreen(stats, false, {}, {}, {}, {}, {})
                }
            }
        }

        onNodeWithText("Study history").assertIsDisplayed()
        onNodeWithText("12").assertIsDisplayed()
        onNodeWithText("90%").assertIsDisplayed()
        onNodeWithText("Due now").assertIsDisplayed()
        onNodeWithTag("stats-daily-chart").assertIsDisplayed()
        onNodeWithText("Stats").performClick()
        assertTrue(opened.get())
    }
}
