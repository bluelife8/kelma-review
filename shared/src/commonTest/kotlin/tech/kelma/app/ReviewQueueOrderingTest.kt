package tech.kelma.app

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReviewQueueOrderingTest {
    private val now = 1_700_000_000_000L

    @Test
    fun newGatherSelectsBeforeCardSortIsApplied() {
        val cards = listOf(
            card(1, queue = 0, note = "a", ord = 1, due = 1),
            card(2, queue = 0, note = "b", ord = 0, due = 2),
            card(3, queue = 0, note = "a", ord = 0, due = 3),
        )
        val ascending = queue(
            cards,
            DeckOptions(
                newCardGatherOrder = NewCardGatherOrder.LowestPosition,
                newCardSortOrder = NewCardSortOrder.TemplateThenGather,
                buryNewSiblings = false,
            ),
        )
        val descendingSelection = queue(
            cards,
            DeckOptions(
                newCardGatherOrder = NewCardGatherOrder.HighestPosition,
                newCardSortOrder = NewCardSortOrder.GatherOrder,
                buryNewSiblings = false,
            ),
            newLimit = 2,
        )

        assertEquals(listOf(2L, 3L, 1L), ascending)
        assertEquals(listOf(3L, 2L), descendingSelection)
    }

    @Test
    fun synchronizedNewPositionOverridesSourceCardIdWithoutTrustingReviewScheduling() {
        val importedNewCards = listOf(
            card(1, queue = 0, due = 100),
            card(1_000, queue = 0, due = 10),
            card(2_000, queue = 0, due = 20),
        )
        val options = DeckOptions(
            newCardGatherOrder = NewCardGatherOrder.LowestPosition,
            newCardSortOrder = NewCardSortOrder.GatherOrder,
            buryNewSiblings = false,
        )

        assertEquals(listOf(1_000L, 2_000L), queue(importedNewCards, options, newLimit = 2))

        val opaqueReviewPayload = card(100, queue = 2, due = 0)
        assertEquals(
            listOf(10L, 100L),
            queue(listOf(card(10, queue = 0, due = 50), opaqueReviewPayload), options),
        )
    }

    @Test
    fun randomNewOrdersAreDeterministicForAStudyDayAndKeepNoteSiblingsTogether() {
        val cards = (1L..8L).map { id -> card(id, 0, note = "n${(id + 1) / 2}", ord = ((id - 1) % 2).toInt()) }
        val options = DeckOptions(
            newCardGatherOrder = NewCardGatherOrder.RandomNotes,
            newCardSortOrder = NewCardSortOrder.RandomNoteThenTemplate,
            buryNewSiblings = false,
        )

        val first = queue(cards, options)
        val second = queue(cards, options)

        assertEquals(first, second)
        first.chunked(2).forEach { pair -> assertEquals(1L, kotlin.math.abs(pair[0] - pair[1])) }
        assertNotEquals(cards.map(SyncCard::cardId), first)
    }

    @Test
    fun newCardsCanAppearBeforeAfterOrMixedWithReviews() {
        val cards = listOf(card(1, 2), card(2, 2), card(3, 0), card(4, 0))
        val schedules = mapOf(1L to reviewSchedule(1, 3), 2L to reviewSchedule(2, 4))

        fun ordered(mix: QueueMixOrder) = queue(
            cards,
            DeckOptions(newReviewMixOrder = mix, reviewSortOrder = ReviewSortOrder.Added),
            schedules,
        )

        assertEquals(listOf(3L, 4L, 1L, 2L), ordered(QueueMixOrder.BeforeReviews))
        assertEquals(listOf(1L, 2L, 3L, 4L), ordered(QueueMixOrder.AfterReviews))
        assertEquals(listOf(1L, 3L, 2L, 4L), ordered(QueueMixOrder.MixWithReviews))
    }

    @Test
    fun futureIntradayLearningUsesTheTwentyMinuteLearnAheadWindow() {
        val cards = listOf(card(1, 2), card(2, 2), card(3, 2))
        val schedules = mapOf(
            1L to learningSchedule(1, lastReview = now).copy(dueAtMillis = now + 60_000L),
            2L to learningSchedule(2, lastReview = now).copy(
                dueAtMillis = now + DefaultLearnAheadMillis + 1L,
            ),
            3L to reviewSchedule(3, 2),
        )

        assertEquals(listOf(3L, 1L), queue(cards, DeckOptions(), schedules))
    }

    @Test
    fun futureInterdayLearningDoesNotEnterTheLearnAheadWindow() {
        val card = card(1, 2)
        val schedule = learningSchedule(1, lastReview = now - MillisPerDay).copy(
            dueAtMillis = now + 60_000L,
        )

        assertEquals(emptyList<Long>(), queue(listOf(card), DeckOptions(), mapOf(1L to schedule)))
    }

    @Test
    fun intradayLearningStaysFirstWhileInterdayPlacementIsConfigurable() {
        val cards = listOf(card(1, 1), card(2, 3), card(3, 2))
        val schedules = mapOf(
            1L to learningSchedule(1, lastReview = now - 10 * 60_000L),
            2L to learningSchedule(2, lastReview = now - 2 * MillisPerDay),
            3L to reviewSchedule(3, 3),
        )
        fun ordered(mix: QueueMixOrder) = queue(
            cards,
            DeckOptions(interdayLearningMixOrder = mix, newReviewMixOrder = QueueMixOrder.AfterReviews),
            schedules,
        )

        assertEquals(listOf(1L, 2L, 3L), ordered(QueueMixOrder.BeforeReviews))
        assertEquals(listOf(1L, 3L, 2L), ordered(QueueMixOrder.AfterReviews))
        assertEquals(listOf(1L, 3L, 2L), ordered(QueueMixOrder.MixWithReviews))
    }

    @Test
    fun reviewSortChoicesUseSchedulingStateWithoutChangingFsrs() {
        val cards = listOf(
            card(10, 2, createdAt = "2020-01-02T00:00:00Z"),
            card(20, 2, createdAt = "2020-01-01T00:00:00Z"),
            card(30, 2),
        )
        val schedules = mapOf(
            10L to reviewSchedule(10, interval = 10, difficulty = 7.0, stability = 2.0, dueOffset = -3_000),
            20L to reviewSchedule(20, interval = 2, difficulty = 3.0, stability = 20.0, dueOffset = -1_000),
            30L to reviewSchedule(30, interval = 5, difficulty = 5.0, stability = 5.0, dueOffset = -2_000),
        )
        fun ordered(order: ReviewSortOrder) = queue(cards, DeckOptions(reviewSortOrder = order), schedules)

        assertEquals(listOf(20L, 30L, 10L), ordered(ReviewSortOrder.IntervalAscending))
        assertEquals(listOf(10L, 30L, 20L), ordered(ReviewSortOrder.IntervalDescending))
        assertEquals(listOf(20L, 30L, 10L), ordered(ReviewSortOrder.DifficultyAscending))
        assertEquals(listOf(10L, 30L, 20L), ordered(ReviewSortOrder.DifficultyDescending))
        assertEquals(listOf(10L, 30L, 20L), ordered(ReviewSortOrder.DueDateThenRandom))
        assertEquals(listOf(10L, 30L, 20L), ordered(ReviewSortOrder.DueDateThenDeck))
        assertEquals(listOf(20L, 10L, 30L), ordered(ReviewSortOrder.Added))
        assertEquals(listOf(10L, 20L, 30L), ordered(ReviewSortOrder.LatestAddedFirst))
        assertEquals(listOf(10L, 30L, 20L), ordered(ReviewSortOrder.RetrievabilityAscending))
        assertEquals(listOf(20L, 30L, 10L), ordered(ReviewSortOrder.RetrievabilityDescending))
        assertEquals(listOf(20L, 30L, 10L), ordered(ReviewSortOrder.RelativeOverdueness))
        assertEquals(ordered(ReviewSortOrder.Random), ordered(ReviewSortOrder.Random))
    }

    @Test
    fun siblingBuryingFiltersNewAndReviewCardsAndRefillsDailyLimits() {
        val newCards = listOf(
            card(1, 0, note = "same", ord = 0, deck = "Deck"),
            card(2, 0, note = "same", ord = 1, deck = "Deck"),
            card(3, 0, note = "other", deck = "Deck"),
        )
        val gatherOrder = DeckOptions(newCardSortOrder = NewCardSortOrder.GatherOrder)
        assertEquals(listOf(1L, 3L), queue(newCards, gatherOrder, newLimit = 2))
        assertEquals(
            listOf(1L, 2L),
            queue(newCards, gatherOrder.copy(buryNewSiblings = false), newLimit = 2),
        )

        val reviews = listOf(
            card(10, 2, note = "review-note", ord = 0),
            card(11, 2, note = "review-note", ord = 1),
        )
        val schedules = reviews.associate { it.cardId to reviewSchedule(it.cardId, 3) }
        assertEquals(
            listOf(10L),
            queue(reviews, DeckOptions(reviewSortOrder = ReviewSortOrder.Added), schedules),
        )
        assertEquals(
            listOf(10L, 11L),
            queue(
                reviews,
                DeckOptions(buryReviewSiblings = false, reviewSortOrder = ReviewSortOrder.Added),
                schedules,
            ),
        )
    }

    @Test
    fun siblingBuryingSurvivesQueueRebuildButDoesNotBuryTheReviewedCardItself() {
        val cards = listOf(
            card(1, 0, note = "same", ord = 0, deck = "Deck"),
            card(2, 0, note = "same", ord = 1, deck = "Deck"),
            card(3, 0, note = "other", deck = "Deck"),
        )
        assertEquals(
            listOf(1L, 3L),
            queue(
                cards,
                DeckOptions(newCardSortOrder = NewCardSortOrder.GatherOrder),
                studied = mapOf("same" to setOf(0)),
            ),
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            queue(
                cards,
                DeckOptions(
                    newCardSortOrder = NewCardSortOrder.GatherOrder,
                    buryNewSiblings = false,
                ),
                studied = mapOf("same" to setOf(0)),
            ),
        )
    }

    @Test
    fun interdayLearningSiblingBuryingIsIndependent() {
        val cards = listOf(card(1, 1, note = "same", ord = 0), card(2, 1, note = "same", ord = 1))
        val schedules = mapOf(
            1L to learningSchedule(1, now - 2 * MillisPerDay),
            2L to learningSchedule(2, now - 2 * MillisPerDay),
        )
        assertEquals(listOf(1L, 2L), queue(cards, DeckOptions(), schedules))
        assertEquals(
            listOf(1L),
            queue(cards, DeckOptions(buryInterdayLearningSiblings = true), schedules),
        )
    }

    @Test
    fun manualDueDatesDeferNewAndReviewCardsWithoutChangingTheirPhase() {
        val cards = listOf(card(1, 0), card(2, 0), card(3, 2))
        val schedules = mapOf(3L to reviewSchedule(3, interval = 3))
        val tomorrow = now + MillisPerDay

        assertEquals(
            listOf(2L),
            queue(
                cards,
                DeckOptions(),
                schedules,
                dueDates = mapOf(1L to tomorrow, 2L to now, 3L to tomorrow),
            ),
        )
    }

    @Test
    fun suspendedCardsAreExcludedFromTheReviewQueue() {
        val cards = listOf(
            card(1, 0),
            card(2, 2).copy(studyState = CardStudyState.Suspended),
            card(3, 0),
        )
        val schedules = mapOf(2L to reviewSchedule(2, interval = 3))

        assertEquals(listOf(1L, 3L), queue(cards, DeckOptions(), schedules))
    }

    @Test
    fun deckAndDueReviewOrdersUseTheirAdvertisedPrimaryKey() {
        val cards = listOf(card(1, 2, deck = "Z"), card(2, 2, deck = "A"))
        val schedules = mapOf(
            1L to reviewSchedule(1, interval = 3, dueOffset = -2_000),
            2L to reviewSchedule(2, interval = 3, dueOffset = -1_000),
        )

        assertEquals(
            listOf(1L, 2L),
            queue(cards, DeckOptions(reviewSortOrder = ReviewSortOrder.DueDateThenDeck), schedules),
        )
        assertEquals(
            listOf(2L, 1L),
            queue(cards, DeckOptions(reviewSortOrder = ReviewSortOrder.DeckThenDueDate), schedules),
        )
    }

    private fun queue(
        cards: List<SyncCard>,
        options: DeckOptions,
        schedules: Map<Long, LocalCardSchedule> = emptyMap(),
        newLimit: Int = 100,
        studied: Map<String, Set<Int>> = emptyMap(),
        dueDates: Map<Long, Long> = emptyMap(),
    ): List<Long> = buildDeckQueue(
        cards = cards,
        localSchedules = schedules,
        options = options,
        remainingNew = newLimit,
        remainingReviews = 100,
        nowMillis = now,
        studiedCardOrdsByNoteToday = studied,
        dueDateOverrides = dueDates,
    ).map(SyncCard::cardId)

    private fun card(
        id: Long,
        queue: Int,
        note: String = "n$id",
        ord: Int = 0,
        due: Long = id,
        deck: String = if (id % 2L == 0L) "Deck::B" else "Deck::A",
        createdAt: String? = null,
    ) = SyncCard(
        cardId = id,
        noteGuid = note,
        deckName = deck,
        ord = ord,
        scheduling = buildJsonObject {
            put("type", if (queue == 0) 0 else 2)
            put("queue", queue)
            put("due", due)
        },
        createdAt = createdAt,
    )

    private fun reviewSchedule(
        id: Long,
        interval: Int,
        difficulty: Double = 5.0,
        stability: Double = 5.0,
        dueOffset: Long = 0,
    ) = LocalCardSchedule(
        id, ReviewPhase.Review, now + dueOffset, stability, difficulty, interval,
        repetitions = 3, lapses = 0, lastReviewAtMillis = now - 5 * MillisPerDay,
    )

    private fun learningSchedule(id: Long, lastReview: Long) = LocalCardSchedule(
        id, ReviewPhase.Learning, now, 1.0, 5.0, 0,
        repetitions = 1, lapses = 0, lastReviewAtMillis = lastReview,
    )
}
