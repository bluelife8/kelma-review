package tech.kelma.app

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class StudyDayPolicyTest {
    private val newYork = AccountStudyDayPolicy(
        version = 1,
        timezoneId = "America/New_York",
        dayStartHour = 4,
    )

    @Test
    fun eventsBeforeFourAmBelongToThePreviousCivilDay() {
        val before = Instant.parse("2025-03-09T07:59:59Z").toEpochMilliseconds()
        val atBoundary = Instant.parse("2025-03-09T08:00:00Z").toEpochMilliseconds()

        assertEquals(LocalDate(2025, 3, 8).toEpochDays(), studyDayAt(before, newYork))
        assertEquals(LocalDate(2025, 3, 9).toEpochDays(), studyDayAt(atBoundary, newYork))
    }

    @Test
    fun nextBoundaryUsesDstRulesInsteadOfAFixedUtcOffset() {
        val saturdayBoundary = Instant.parse("2025-03-08T09:00:00Z")
        val nextBoundary = nextStudyDayStart(saturdayBoundary.toEpochMilliseconds(), newYork)

        assertEquals(
            saturdayBoundary + 23.hours,
            Instant.fromEpochMilliseconds(nextBoundary),
        )
    }

    @Test
    fun fallDstBoundaryCanBeTwentyFiveHoursLater() {
        val saturdayBoundary = Instant.parse("2025-11-01T08:00:00Z")
        val nextBoundary = nextStudyDayStart(saturdayBoundary.toEpochMilliseconds(), newYork)

        assertEquals(
            saturdayBoundary + 25.hours,
            Instant.fromEpochMilliseconds(nextBoundary),
        )
    }

    @Test
    fun oneDayReviewIsDueAtTheNextStudyDayBoundary() {
        val reviewedAt = Instant.parse("2026-07-30T23:55:58Z").toEpochMilliseconds()
        val schedule = schedule(
            phase = ReviewPhase.Review,
            reviewedAt = reviewedAt,
            dueAt = reviewedAt + 24.hours.inWholeMilliseconds,
            scheduledDays = 1,
        )

        assertEquals(
            Instant.parse("2026-07-31T08:00:00Z").toEpochMilliseconds(),
            schedule.alignedToStudyDay(newYork).dueAtMillis,
        )
    }

    @Test
    fun dayBasedReviewAlignmentUsesDstCalendarDays() {
        val springReview = Instant.parse("2025-03-08T15:00:00Z").toEpochMilliseconds()
        val fallReview = Instant.parse("2025-11-01T14:00:00Z").toEpochMilliseconds()

        assertEquals(
            Instant.parse("2025-03-09T08:00:00Z").toEpochMilliseconds(),
            schedule(ReviewPhase.Review, springReview, springReview + 24.hours.inWholeMilliseconds, 1)
                .alignedToStudyDay(newYork).dueAtMillis,
        )
        assertEquals(
            Instant.parse("2025-11-02T09:00:00Z").toEpochMilliseconds(),
            schedule(ReviewPhase.Review, fallReview, fallReview + 24.hours.inWholeMilliseconds, 1)
                .alignedToStudyDay(newYork).dueAtMillis,
        )
    }

    @Test
    fun onlyInterdayRelearningIsAligned() {
        val reviewedAt = Instant.parse("2025-03-08T23:50:00Z").toEpochMilliseconds()
        val intradayDue = Instant.parse("2025-03-09T07:59:00Z").toEpochMilliseconds()
        val interdayDue = Instant.parse("2025-03-09T08:10:00Z").toEpochMilliseconds()

        assertEquals(
            intradayDue,
            schedule(ReviewPhase.Relearning, reviewedAt, intradayDue, 0)
                .alignedToStudyDay(newYork).dueAtMillis,
        )
        assertEquals(
            Instant.parse("2025-03-09T08:00:00Z").toEpochMilliseconds(),
            schedule(ReviewPhase.Relearning, reviewedAt, interdayDue, 0)
                .alignedToStudyDay(newYork).dueAtMillis,
        )
    }

    private fun schedule(
        phase: ReviewPhase,
        reviewedAt: Long,
        dueAt: Long,
        scheduledDays: Int,
    ) = LocalCardSchedule(
        cardId = 1,
        phase = phase,
        dueAtMillis = dueAt,
        stability = 1.0,
        difficulty = 5.0,
        scheduledDays = scheduledDays,
        repetitions = 1,
        lapses = 0,
        lastReviewAtMillis = reviewedAt,
    )
}
