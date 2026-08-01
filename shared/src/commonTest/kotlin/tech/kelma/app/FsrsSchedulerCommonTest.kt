package tech.kelma.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FsrsSchedulerCommonTest {
    private val card = SyncCard(42, "note-1", "Deck")

    @Test
    fun successfulRatingOutcomesAreOrdered() {
        val previous = reviewSchedule(stability = 10.0, difficulty = 5.0, lastReviewAt = BaseTime)
        val reviewedAt = BaseTime + 30 * MillisPerDay
        val hard = FsrsScheduler.review(card, previous, Rating.Hard, reviewedAt)
        val good = FsrsScheduler.review(card, previous, Rating.Good, reviewedAt)
        val easy = FsrsScheduler.review(card, previous, Rating.Easy, reviewedAt)

        assertTrue(hard.stability < good.stability)
        assertTrue(good.stability < easy.stability)
        assertTrue(hard.dueAtMillis < good.dueAtMillis)
        assertTrue(good.dueAtMillis < easy.dueAtMillis)
        assertTrue(hard.difficulty > good.difficulty)
        assertTrue(good.difficulty > easy.difficulty)
    }

    @Test
    fun anOverdueSuccessGrowsStabilityMoreThanAnEarlySuccess() {
        val previous = reviewSchedule(stability = 10.0, difficulty = 5.0, lastReviewAt = BaseTime)
        val early = FsrsScheduler.review(card, previous, Rating.Good, BaseTime + MillisPerDay)
        val overdue = FsrsScheduler.review(card, previous, Rating.Good, BaseTime + 30 * MillisPerDay)

        assertTrue(overdue.stability > early.stability)
        assertTrue(overdue.scheduledDays > early.scheduledDays)
    }

    @Test
    fun sameDayReviewUsesShortTermStability() {
        val previous = reviewSchedule(stability = 5.0, difficulty = 6.0, lastReviewAt = BaseTime)
        val again = FsrsScheduler.review(card, previous, Rating.Again, BaseTime)
        val good = FsrsScheduler.review(card, previous, Rating.Good, BaseTime)

        assertEquals(1.5968179979869215, again.stability, 0.00001)
        assertEquals(5.0, good.stability, 0.00001)
        assertEquals(ReviewPhase.Relearning, again.phase)
        assertEquals(ReviewPhase.Review, good.phase)
    }

    @Test
    fun importedSchedulingNeverSeedsTheLocalFsrs6Projection() {
        val scheduling = Json.parseToJsonElement(
            """{"queue":2,"ivl":30,"reps":5,"data":"{\"s\":12.5,\"d\":4.2}"}""",
        ).jsonObject
        val imported = card.copy(scheduling = scheduling)
        val reviewedAt = BaseTime + 60 * MillisPerDay
        val withHistory = FsrsScheduler.review(
            imported,
            previous = null,
            rating = Rating.Good,
            reviewedAtMillis = reviewedAt,
            serverLastReviewAtMillis = BaseTime,
        )
        val withoutHistory = FsrsScheduler.review(
            imported,
            previous = null,
            rating = Rating.Good,
            reviewedAtMillis = reviewedAt,
        )

        assertEquals(1, withHistory.repetitions)
        assertEquals(withoutHistory, withHistory)
    }

    @Test
    fun longDeterministicReviewSequenceStaysWithinDomain() {
        var seed = 0x13579BDF
        var now = BaseTime
        var schedule: LocalCardSchedule? = null
        var previousLapses = 0

        repeat(2_000) { index ->
            seed = seed * 1_103_515_245 + 12_345
            val rating = Rating.entries[(seed ushr 16) and 3]
            val current = schedule
            if (current != null) {
                val extraDays = ((seed ushr 8) and 31).toLong()
                now = current.dueAtMillis + extraDays * MillisPerDay
            }
            val next = FsrsScheduler.review(card, current, rating, now)

            assertTrue(next.stability.isFinite(), "stability at review $index")
            assertTrue(next.stability >= 0.01, "stability at review $index")
            assertTrue(next.difficulty.isFinite(), "difficulty at review $index")
            assertTrue(next.difficulty in 1.0..10.0, "difficulty at review $index")
            assertTrue(next.scheduledDays in 0..36_500, "interval at review $index")
            assertTrue(next.dueAtMillis >= now, "due time at review $index")
            assertEquals(index + 1, next.repetitions)
            assertTrue(next.lapses >= previousLapses)

            schedule = next
            previousLapses = next.lapses
        }
    }

    @Test
    fun dueTimestampSaturatesInsteadOfOverflowing() {
        val result = FsrsScheduler.review(
            card,
            previous = null,
            rating = Rating.Easy,
            reviewedAtMillis = Long.MAX_VALUE - 1_000,
        )

        assertEquals(Long.MAX_VALUE, result.dueAtMillis)
    }

    private fun reviewSchedule(
        stability: Double,
        difficulty: Double,
        lastReviewAt: Long,
    ) = LocalCardSchedule(
        cardId = card.cardId,
        phase = ReviewPhase.Review,
        dueAtMillis = lastReviewAt,
        stability = stability,
        difficulty = difficulty,
        scheduledDays = stability.toInt().coerceAtLeast(1),
        repetitions = 1,
        lapses = 0,
        lastReviewAtMillis = lastReviewAt,
    )

    private companion object {
        const val BaseTime = 1_700_000_000_000L
    }
}
