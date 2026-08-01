package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals

class StudyStatsTest {
    private val today = 20_000L
    private val now = today * MillisPerDay + 12_000L

    @Test
    fun immutableReviewsProduceRecallStreakTimeAndCardState() {
        val reviews = listOf(
            review(today, 1, 10_000),
            review(today, 3, 20_000),
            review(today - 1, 4, 30_000),
            review(today - 3, 2, 40_000),
        )
        val cards = listOf(
            SyncCard(1, "a", "Deck"),
            SyncCard(2, "b", "Deck"),
            SyncCard(3, "c", "Deck"),
            SyncCard(4, "d", "Deck"),
        )
        val schedules = mapOf(
            1L to schedule(1, ReviewPhase.Learning, 0, due = now - 1),
            2L to schedule(2, ReviewPhase.Review, 10, due = now + 1),
            3L to schedule(3, ReviewPhase.Review, 30, due = now - 1),
        )

        val stats = calculateStudyStats(reviews, cards, schedules, now)

        assertEquals(4, stats.totalReviews)
        assertEquals(2, stats.reviewsToday)
        assertEquals(30_000, stats.studiedMillisToday)
        assertEquals(3, stats.recalledReviews)
        assertEquals(1, stats.forgottenReviews)
        assertEquals(0.75, stats.recallRate)
        assertEquals(2, stats.currentStreakDays)
        assertEquals(1, stats.newCards)
        assertEquals(1, stats.learningCards)
        assertEquals(2, stats.reviewCards)
        assertEquals(2, stats.dueCards)
        assertEquals(1, stats.matureCards)
        assertEquals(30, stats.daily.size)
    }

    private fun review(day: Long, rating: Int, duration: Long) =
        StudyStatsReview(day * MillisPerDay + rating, rating, duration, day)

    private fun schedule(id: Long, phase: ReviewPhase, days: Int, due: Long) = LocalCardSchedule(
        id, phase, due, 5.0, 5.0, days, 2, 0, now - MillisPerDay,
    )
}
