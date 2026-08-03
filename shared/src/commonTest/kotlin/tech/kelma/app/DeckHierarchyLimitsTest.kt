package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DeckHierarchyLimitsTest {
    @Test
    fun parentDeckAggregatesEveryDescendantQueue() {
        val now = 10L * MillisPerDay
        val cards = listOf(
            SyncCard(1, "new", "Languages::German::New"),
            SyncCard(2, "learn", "Languages::German::Learn"),
            SyncCard(3, "review", "Languages::German::Review"),
        )
        val collection = collection(cards)
        val schedules = mapOf(
            2L to schedule(2, ReviewPhase.Learning, now),
            3L to schedule(3, ReviewPhase.Review, now),
        )

        val decks = collection.asDecks(
            localSchedules = schedules,
            nowMillis = now,
            deckOptions = mapOf(
                "Languages" to DeckOptions(newCardsPerDay = 10, maximumReviewsPerDay = 10),
            ),
        )

        assertCounts(decks.deck("Languages"), new = 1, learning = 1, due = 1)
        assertCounts(decks.deck("Languages::German"), new = 1, learning = 1, due = 1)
        assertCounts(decks.deck("Languages::German::New"), new = 1, learning = 0, due = 0)
    }

    @Test
    fun ancestorConsumptionAndLimitsAreSharedByChildDecks() {
        val now = 10L * MillisPerDay
        val cards = listOf(
            SyncCard(1, "new-a", "Parent::Child A"),
            SyncCard(2, "new-b", "Parent::Child B"),
            SyncCard(3, "review-a", "Parent::Child B"),
            SyncCard(4, "review-b", "Parent::Child B"),
        )
        val collection = collection(cards)
        val schedules = mapOf(
            3L to schedule(3, ReviewPhase.Review, now),
            4L to schedule(4, ReviewPhase.Review, now),
        )
        val projected = collection.asDeck(
            name = "Parent::Child B",
            localSchedules = schedules,
            nowMillis = now,
            deckOptions = mapOf(
                "Parent" to DeckOptions(newCardsPerDay = 2, maximumReviewsPerDay = 3),
            ),
            studiedTodayByDeck = mapOf(
                "Parent::Child A" to DeckStudyCounts(newCards = 1, reviews = 1),
            ),
        ) ?: error("Missing child deck")

        assertCounts(projected, new = 1, learning = 0, due = 0)
    }

    @Test
    fun newCardsHavePriorityThenReviewsFillSharedReviewCapacity() {
        val now = 10L * MillisPerDay
        val cards = listOf(
            SyncCard(1, "new-a", "Deck"),
            SyncCard(2, "new-b", "Deck"),
            SyncCard(3, "review-a", "Deck"),
            SyncCard(4, "review-b", "Deck"),
        )
        val schedules = mapOf(
            3L to schedule(3, ReviewPhase.Review, now),
            4L to schedule(4, ReviewPhase.Review, now),
        )
        val collection = collection(cards)

        val combined = collection.asDecks(
            localSchedules = schedules,
            nowMillis = now,
            deckOptions = mapOf(
                "Deck" to DeckOptions(
                    newCardsPerDay = 2,
                    maximumReviewsPerDay = 3,
                    newReviewMixOrder = QueueMixOrder.BeforeReviews,
                ),
            ),
        ).single()
        val independent = collection.asDecks(
            localSchedules = schedules,
            nowMillis = now,
            deckOptions = mapOf(
                "Deck" to DeckOptions(
                    newCardsPerDay = 2,
                    maximumReviewsPerDay = 3,
                    newCardsIgnoreReviewLimit = true,
                    newReviewMixOrder = QueueMixOrder.BeforeReviews,
                ),
            ),
        ).single()

        assertCounts(combined, new = 2, learning = 0, due = 1)
        assertCounts(independent, new = 2, learning = 0, due = 2)
    }

    @Test
    fun buriedReviewSiblingDoesNotReserveUnusedDailyCapacity() {
        val now = 10L * MillisPerDay
        val cards = listOf(
            SyncCard(1, "review-siblings", "Deck", ord = 0),
            SyncCard(2, "review-siblings", "Deck", ord = 1),
            SyncCard(3, "new", "Deck"),
        )
        val projected = collection(cards).asDecks(
            localSchedules = mapOf(
                1L to schedule(1, ReviewPhase.Review, now),
                2L to schedule(2, ReviewPhase.Review, now),
            ),
            nowMillis = now,
            deckOptions = mapOf(
                "Deck" to DeckOptions(newCardsPerDay = 1, maximumReviewsPerDay = 2),
            ),
        ).single()

        assertCounts(projected, new = 1, learning = 0, due = 1)
    }

    private fun collection(cards: List<SyncCard>): SyncedCollection = SyncedCollection(
        notes = cards.associate { card ->
            card.noteGuid to SyncNote(card.noteGuid, fields = listOf(card.noteGuid, "back"))
        },
        cards = cards.associateBy(SyncCard::cardId),
        deckNames = cards.flatMap { deckHierarchyNames(it.deckName) }.toSet(),
    )

    private fun schedule(cardId: Long, phase: ReviewPhase, dueAtMillis: Long): LocalCardSchedule =
        LocalCardSchedule(
            cardId = cardId,
            phase = phase,
            dueAtMillis = dueAtMillis,
            stability = 2.0,
            difficulty = 5.0,
            scheduledDays = 2,
            repetitions = 2,
            lapses = 0,
            lastReviewAtMillis = dueAtMillis - MillisPerDay,
        )

    private fun List<DeckSummary>.deck(name: String): DeckSummary = first { it.name == name }

    private fun assertCounts(deck: DeckSummary, new: Int, learning: Int, due: Int) {
        assertEquals(new, deck.newCount, "${deck.name} New")
        assertEquals(learning, deck.learningCount, "${deck.name} Learn")
        assertEquals(due, deck.dueCount, "${deck.name} Due")
    }
}
