package tech.kelma.app

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DeckHierarchyProjectionCacheTest {
    @Test
    fun childReviewInvalidatesAncestorsButNotAnotherTree() = runTest {
        val collection = collectionOf(
            SyncCard(1, "child-note", "Parent::Child"),
            SyncCard(2, "other-note", "Other"),
        )
        val initial = LocalReviewSnapshot(studyDay = 0)
        val cache = DeckCountProjectionCache()
        val first = cache.project(collection, LocalContentSnapshot(), initial, 1_000L, emptyMap(), null)
        val reviewed = initial.applying(
            RecordedReviewDelta(
                schedule = schedule(1),
                noteGuid = "child-note",
                cardOrd = 0,
                deckName = "Parent::Child",
                reviewedAtMillis = 2_000L,
                wasNew = true,
                clearedDueDateOverride = false,
                pendingDownloadedCardId = null,
            ),
        )

        val second = cache.project(collection, LocalContentSnapshot(), reviewed, 2_000L, emptyMap(), null)

        assertEquals(0, second.deck("Parent").newCount)
        assertEquals(0, second.deck("Parent::Child").newCount)
        assertNotSame(first.deck("Parent"), second.deck("Parent"))
        assertNotSame(first.deck("Parent::Child"), second.deck("Parent::Child"))
        assertSame(first.deck("Other"), second.deck("Other"))
    }

    private fun collectionOf(vararg cards: SyncCard): SyncedCollection = SyncedCollection(
        notes = cards.associate { card ->
            card.noteGuid to SyncNote(card.noteGuid, fields = listOf("front", "back"))
        },
        cards = cards.associateBy(SyncCard::cardId),
        deckNames = cards.flatMap { deckHierarchyNames(it.deckName) }.toSet(),
    )

    private fun schedule(cardId: Long): LocalCardSchedule = LocalCardSchedule(
        cardId = cardId,
        phase = ReviewPhase.Learning,
        dueAtMillis = 62_000L,
        stability = 1.0,
        difficulty = 5.0,
        scheduledDays = 0,
        repetitions = 1,
        lapses = 0,
        lastReviewAtMillis = 2_000L,
        step = 0,
    )

    private fun List<DeckSummary>.deck(name: String): DeckSummary = first { it.name == name }
}
