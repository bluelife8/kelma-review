package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReviewSessionQueueTest {
    @Test
    fun dueLearningPreemptsA193CardRegularQueueAtSixtySeconds() {
        val clock = FakeReviewClock(1_000_000L)
        val cards = (1L..193L).map { ReviewCard(it, "front $it", "back $it") }
        var session = ReviewSession(cards)
            .revealAnswer()
            .answer(Rating.Again, learningSchedule(1L, clock.now + 60_000L, clock.now), clock.now)

        assertEquals(2L, session.currentCard?.id)
        assertEquals(191, session.regularQueue.size)
        assertEquals(listOf(1L), session.learningQueue.map { it.card.id })

        clock.advance(59_000L)
        session = session.advanceTime(clock.now)
        assertEquals(2L, session.currentCard?.id)
        session = session.revealAnswer().answer(
            Rating.Good,
            reviewSchedule(2L, clock.now + MillisPerDay, clock.now),
            clock.now,
        )
        assertEquals(3L, session.currentCard?.id)

        clock.advance(1_000L)
        session = session.advanceTime(clock.now)
        assertEquals(3L, session.currentCard?.id)
        session = session.revealAnswer().answer(
            Rating.Good,
            reviewSchedule(3L, clock.now + MillisPerDay, clock.now),
            clock.now,
        )

        assertEquals(1L, session.currentCard?.id)
        assertTrue(session.learningQueue.none { it.card.id == 1L })
    }

    @Test
    fun repeatedHardKeepsOneTimedEntryAndNeverGraduatesByAttemptCount() {
        val clock = FakeReviewClock(2_000_000L)
        val cards = (1L..12L).map { ReviewCard(it, "front $it", "back $it") }
        val schedulingCard = SyncCard(1L, "note-1", "Deck")
        var schedule = FsrsScheduler.review(schedulingCard, null, Rating.Again, clock.now)
        var session = ReviewSession(cards)
            .revealAnswer()
            .answer(Rating.Again, schedule, clock.now)

        repeat(10) {
            assertEquals(1, session.learningQueue.size)
            clock.now = session.nextLearningDueAtMillis ?: error("Missing learning due time")
            session = session.buryCurrentCard(clock.now)
            assertEquals(1L, session.currentCard?.id)
            schedule = FsrsScheduler.review(schedulingCard, schedule, Rating.Hard, clock.now)
            session = session.revealAnswer().answer(Rating.Hard, schedule, clock.now)
            assertEquals(ReviewPhase.Learning, schedule.phase)
            assertEquals(0, schedule.step)
            assertEquals(clock.now + 330_000L, schedule.dueAtMillis)
            assertEquals(listOf(1L), session.learningQueue.map { it.card.id })
        }
    }

    @Test
    fun learnAheadSelectsLearningWhenTheRegularQueueBecomesEmpty() {
        val clock = FakeReviewClock(3_000_000L)
        val cards = listOf(
            ReviewCard(1L, "one", "one"),
            ReviewCard(2L, "two", "two"),
        )
        var session = ReviewSession(cards)
            .revealAnswer()
            .answer(Rating.Again, learningSchedule(1L, clock.now + 10 * 60_000L, clock.now), clock.now)

        assertEquals(2L, session.currentCard?.id)
        clock.advance(60_000L)
        session = session.revealAnswer().answer(
            Rating.Good,
            reviewSchedule(2L, clock.now + MillisPerDay, clock.now),
            clock.now,
        )

        assertEquals(1L, session.currentCard?.id)
        assertFalse(session.isWaiting)
    }

    @Test
    fun learningOutsideLearnAheadWaitsUntilItEntersTheCutoff() {
        val clock = FakeReviewClock(3_500_000L)
        var session = ReviewSession(listOf(ReviewCard(1L, "one", "one")))
            .revealAnswer()
            .answer(
                Rating.Again,
                learningSchedule(1L, clock.now + DefaultLearnAheadMillis + 1_000L, clock.now),
                clock.now,
            )

        assertTrue(session.isWaiting)
        assertFalse(session.isComplete)
        clock.advance(999L)
        session = session.advanceTime(clock.now)
        assertTrue(session.isWaiting)
        clock.advance(1L)
        session = session.advanceTime(clock.now)
        assertEquals(1L, session.currentCard?.id)
    }

    @Test
    fun learningStepAcrossTheStudyDayBoundaryDoesNotHoldTheSessionOpen() {
        val clock = FakeReviewClock(MillisPerDay - 30_000L)
        val session = ReviewSession(listOf(ReviewCard(1L, "front", "back")))
            .revealAnswer()
            .answer(Rating.Again, learningSchedule(1L, clock.now + 60_000L, clock.now), clock.now)

        assertTrue(session.isComplete)
        assertTrue(session.learningQueue.isEmpty())
    }

    @Test
    fun learningStepAtSynchronizedLocalRolloverWaitsForTheNextSession() {
        val policy = AccountStudyDayPolicy(
            version = 1,
            timezoneId = "America/New_York",
            dayStartHour = 4,
        )
        val reviewedAt = Instant.parse("2025-03-09T07:59:00Z").toEpochMilliseconds()
        val cutoff = Instant.parse("2025-03-09T08:00:00Z").toEpochMilliseconds()
        val session = ReviewSession.start(
            cards = listOf(ReviewCard(1L, "front", "back")),
            schedules = emptyMap(),
            nowMillis = reviewedAt,
            studyDayPolicy = policy,
        ).revealAnswer()

        val answered = session.answer(
            Rating.Again,
            learningSchedule(1L, cutoff, reviewedAt),
            reviewedAt,
        )

        assertTrue(answered.isComplete)
        assertTrue(answered.learningQueue.isEmpty())
    }

    @Test
    fun initialDueLearningUsesTheTimedQueueBeforeRegularCards() {
        val clock = FakeReviewClock(4_000_000L)
        val regular = ReviewCard(1L, "regular", "regular")
        val learning = ReviewCard(2L, "learning", "learning")
        val session = ReviewSession.start(
            cards = listOf(regular, learning),
            schedules = mapOf(2L to learningSchedule(2L, clock.now, clock.now - 60_000L)),
            nowMillis = clock.now,
        )

        assertEquals(2L, session.currentCard?.id)
        assertEquals(listOf(1L), session.regularQueue.map(ReviewCard::id))
    }

    @Test
    fun initialTimedQueueUsesTheEffectiveManualDueDate() {
        val clock = FakeReviewClock(4_500_000L)
        val card = ReviewCard(1L, "learning", "learning")
        val schedule = learningSchedule(1L, clock.now + MillisPerDay, clock.now - 60_000L)
        val session = ReviewSession.start(
            cards = listOf(card),
            schedules = mapOf(1L to schedule),
            nowMillis = clock.now,
            dueDateOverrides = mapOf(1L to clock.now),
        )

        assertEquals(1L, session.currentCard?.id)
    }

    @Test
    fun deckReconciliationRefreshesButDoesNotDuplicateOrAddRegularCards() {
        val clock = FakeReviewClock(5_000_000L)
        val cards = listOf(
            ReviewCard(1L, "one", "one"),
            ReviewCard(2L, "two", "two"),
        )
        val repeat = learningSchedule(1L, clock.now + 60_000L, clock.now)
        val unseen = ReviewCard(99L, "unseen", "unseen")
        val refreshed = cards.first().copy(front = "updated")
        val session = ReviewSession(cards)
            .revealAnswer()
            .answer(Rating.Again, repeat, clock.now)
            .reconcileLearningQueue(
                latestCards = listOf(refreshed, cards[1], unseen),
                schedules = mapOf(1L to repeat),
                nowMillis = clock.now,
            )

        assertEquals(listOf(1L), session.learningQueue.map { it.card.id })
        assertEquals("updated", session.learningQueue.single().card.front)
        assertTrue(session.cards.none { it.id == unseen.id })
    }
}

private class FakeReviewClock(var now: Long) {
    fun advance(millis: Long) {
        now += millis
    }
}

private fun learningSchedule(
    cardId: Long,
    dueAtMillis: Long,
    reviewedAtMillis: Long,
    phase: ReviewPhase = ReviewPhase.Learning,
    step: Int = 0,
): LocalCardSchedule = LocalCardSchedule(
    cardId = cardId,
    phase = phase,
    dueAtMillis = dueAtMillis,
    stability = 1.0,
    difficulty = 5.0,
    scheduledDays = 0,
    repetitions = 1,
    lapses = if (phase == ReviewPhase.Relearning) 1 else 0,
    lastReviewAtMillis = reviewedAtMillis,
    step = step,
)

private fun reviewSchedule(
    cardId: Long,
    dueAtMillis: Long,
    reviewedAtMillis: Long,
): LocalCardSchedule = LocalCardSchedule(
    cardId = cardId,
    phase = ReviewPhase.Review,
    dueAtMillis = dueAtMillis,
    stability = 2.0,
    difficulty = 5.0,
    scheduledDays = 1,
    repetitions = 1,
    lapses = 0,
    lastReviewAtMillis = reviewedAtMillis,
    step = null,
)
