package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckProjectionCommonTest {
    @Test
    fun focusedProjectionMatchesFullCollectionProjection() {
        val collection = SyncedCollection(
            notes = mapOf(
                "a" to SyncNote("a", fields = listOf("front a", "back a")),
                "b" to SyncNote("b", fields = listOf("front b", "back b")),
            ),
            cards = mapOf(
                1L to SyncCard(1, "a", "First"),
                2L to SyncCard(2, "b", "Second"),
            ),
            deckNames = setOf("First", "Second"),
        )
        val now = 5L * MillisPerDay
        val schedules = mapOf(
            1L to LocalCardSchedule(
                cardId = 1,
                phase = ReviewPhase.Review,
                dueAtMillis = now,
                stability = 2.0,
                difficulty = 5.0,
                scheduledDays = 2,
                repetitions = 2,
                lapses = 0,
                lastReviewAtMillis = now - MillisPerDay,
            ),
        )

        val full = collection.asDecks(localSchedules = schedules, nowMillis = now)
            .first { it.id == "First" }
        val focused = collection.asDeck("First", localSchedules = schedules, nowMillis = now)

        assertEquals(full, focused)
    }

    @Test
    fun deckListProjectionKeepsCountsWithoutRenderingReviewQueues() {
        val collection = SyncedCollection(
            notes = mapOf("note" to SyncNote("note", fields = listOf("front", "back"))),
            cards = mapOf(1L to SyncCard(1, "note", "Deck")),
            deckNames = setOf("Deck"),
        )

        val listed = collection.asDeckList(nowMillis = 0).single()
        val review = requireNotNull(collection.asDeck("Deck", nowMillis = 0))

        assertFalse(listed.queueLoaded)
        assertTrue(listed.cards.isEmpty())
        assertEquals(1, listed.newCount)
        assertEquals(review.newCount, listed.newCount)
        assertTrue(review.queueLoaded)
        assertEquals(1, review.cards.size)
    }
}
