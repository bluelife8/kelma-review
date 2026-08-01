package tech.kelma.app

import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeckOptionsCommonTest {
    @Test
    fun fsrs5ProfilesMigrateWithoutRelabelingCustomParameters() {
        val fresh = DeckOptions().validated()
        assertEquals(SchedulerAlgorithm.Fsrs6, fresh.schedulerAlgorithm)
        assertEquals(21, fresh.fsrsParameters.size)

        val untouchedLegacyDefault = DeckOptions(fsrsParameters = DefaultFsrs5Parameters).validated()
        assertEquals(SchedulerAlgorithm.Fsrs6, untouchedLegacyDefault.schedulerAlgorithm)
        assertEquals(DefaultFsrs6Parameters, untouchedLegacyDefault.fsrsParameters)

        val customLegacy = DeckOptions(
            fsrsParameters = DefaultFsrs5Parameters.toMutableList().apply { this[0] += 0.01 },
        ).validated()
        assertEquals(SchedulerAlgorithm.Fsrs5, customLegacy.schedulerAlgorithm)
        assertEquals(19, customLegacy.fsrsParameters.size)
    }

    @Test
    fun minuteStepsAcceptMinutesHoursAndDays() {
        assertEquals(listOf(1, 10, 120, 1_440), parseMinuteSteps("1m 10 2h 1d"))
        assertEquals(emptyList(), parseMinuteSteps("  "))
        assertFailsWith<IllegalArgumentException> { parseMinuteSteps("soon") }
    }

    @Test
    fun customLearningStepsRetentionAndMaximumIntervalAffectScheduling() {
        val card = SyncCard(1, "note", "Deck")
        val now = 1_700_000_000_000L
        val customSteps = DeckOptions(learningStepsMinutes = listOf(3, 20))
        val goodLearning = FsrsScheduler.review(card, null, Rating.Good, now, options = customSteps)
        assertEquals(now + 20 * 60_000L, goodLearning.dueAtMillis)
        val customParameters = DefaultFsrs5Parameters.toMutableList().apply { this[Rating.Good.ordinal] = 9.0 }
        val customStability = FsrsScheduler.review(
            card, null, Rating.Good, now,
            options = DeckOptions(fsrsParameters = customParameters),
        )
        assertEquals(9.0, customStability.stability)

        val previous = LocalCardSchedule(
            cardId = card.cardId,
            phase = ReviewPhase.Review,
            dueAtMillis = now,
            stability = 100.0,
            difficulty = 5.0,
            scheduledDays = 100,
            repetitions = 10,
            lapses = 0,
            lastReviewAtMillis = now - 100 * MillisPerDay,
        )
        val lowRetention = FsrsScheduler.review(
            card, previous, Rating.Good, now,
            options = DeckOptions(desiredRetention = 0.80),
        )
        val highRetention = FsrsScheduler.review(
            card, previous, Rating.Good, now,
            options = DeckOptions(desiredRetention = 0.95),
        )
        assertTrue(highRetention.scheduledDays < lowRetention.scheduledDays)

        val capped = FsrsScheduler.review(
            card, previous, Rating.Easy, now,
            options = DeckOptions(maximumIntervalDays = 7),
        )
        assertTrue(capped.scheduledDays <= 7)
    }

    @Test
    fun deckDailyLimitsRestrictNewAndReviewQueuesButNotLearning() {
        val notes = (1..8).associate { index -> "n$index" to SyncNote("n$index", fields = listOf("$index", "back")) }
        val cards = (1L..8L).associateWith { id ->
            val queue = when {
                id <= 4 -> 0
                id <= 7 -> 2
                else -> 1
            }
            SyncCard(id, "n$id", "Deck", scheduling = kotlinx.serialization.json.buildJsonObject {
                put("queue", queue)
            })
        }
        val collection = SyncedCollection(notes = notes, cards = cards, deckNames = setOf("Deck"))
        val schedules = (5L..7L).associateWith { id ->
            LocalCardSchedule(id, ReviewPhase.Review, 1L, 5.0, 5.0, 1, 1, 0, 0L)
        } + (8L to LocalCardSchedule(8, ReviewPhase.Learning, 1L, 1.0, 5.0, 0, 1, 0, 0L))

        val deck = collection.asDecks(
            localSchedules = schedules,
            nowMillis = 1_700_000_000_000L,
            deckOptions = mapOf("Deck" to DeckOptions(newCardsPerDay = 2, maximumReviewsPerDay = 1)),
            studiedTodayByDeck = mapOf("Deck" to DeckStudyCounts(newCards = 1, reviews = 1)),
        ).single()

        assertEquals(0, deck.newCount)
        assertEquals(0, deck.dueCount)
        assertEquals(1, deck.learningCount)
        assertEquals(1, deck.cards.size)
    }
}
