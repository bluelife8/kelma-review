package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DailyReviewLimitTest {
    @Test
    fun learningAndRelearningRepetitionsDoNotConsumeReviewCapacity() {
        val now = 10L * MillisPerDay
        val initial = LocalReviewSnapshot(studyDay = epochDayAt(now))

        val afterNew = initial.applying(
            delta(
                phase = ReviewPhase.Learning,
                reviewedAtMillis = now,
                wasNew = true,
            ),
        )
        val afterLearningRepeat = afterNew.applying(
            delta(
                phase = ReviewPhase.Review,
                reviewedAtMillis = now + 60_000L,
            ),
        )
        val afterReviewAdmission = afterLearningRepeat.applying(
            delta(
                phase = ReviewPhase.Relearning,
                reviewedAtMillis = now + 120_000L,
                consumedReviewLimit = true,
            ),
        )
        val afterRelearningRepeat = afterReviewAdmission.applying(
            delta(
                phase = ReviewPhase.Review,
                reviewedAtMillis = now + 180_000L,
            ),
        )
        val afterDuplicateReviewAdmission = afterRelearningRepeat.applying(
            delta(
                phase = ReviewPhase.Review,
                reviewedAtMillis = now + 240_000L,
                consumedReviewLimit = true,
            ),
        )

        assertEquals(5, afterDuplicateReviewAdmission.reviewedToday)
        assertEquals(
            DeckStudyCounts(newCards = 1, reviews = 1),
            afterDuplicateReviewAdmission.studiedTodayByDeck.getValue("Deck"),
        )
    }

    private fun delta(
        phase: ReviewPhase,
        reviewedAtMillis: Long,
        wasNew: Boolean = false,
        consumedReviewLimit: Boolean = false,
    ): RecordedReviewDelta = RecordedReviewDelta(
        schedule = LocalCardSchedule(
            cardId = 1,
            phase = phase,
            dueAtMillis = reviewedAtMillis + 60_000L,
            stability = 1.0,
            difficulty = 5.0,
            scheduledDays = 1,
            repetitions = 1,
            lapses = 0,
            lastReviewAtMillis = reviewedAtMillis,
        ),
        noteGuid = "note",
        cardOrd = 0,
        deckName = "Deck",
        reviewedAtMillis = reviewedAtMillis,
        wasNew = wasNew,
        consumedReviewLimit = consumedReviewLimit,
        clearedDueDateOverride = false,
        pendingDownloadedCardId = null,
    )
}
