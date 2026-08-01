package tech.kelma.app

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeckCountProjectionCacheTest {
    @Test
    fun incrementalReviewReprojectsOnlyAffectedDecks() = runTest {
        val collection = collectionOf(
            SyncCard(1, "note-a", "Deck A"),
            SyncCard(2, "note-b", "Deck B"),
        )
        val initialReviews = LocalReviewSnapshot(studyDay = 0)
        val cache = DeckCountProjectionCache()
        val first = cache.project(collection, LocalContentSnapshot(), initialReviews, 1_000, emptyMap(), null)

        val nextReviews = initialReviews.applying(
            RecordedReviewDelta(
                schedule = schedule(1, dueAtMillis = MillisPerDay),
                noteGuid = "note-a",
                cardOrd = 0,
                deckName = "Deck A",
                reviewedAtMillis = 2_000,
                wasNew = true,
                clearedDueDateOverride = false,
                pendingDownloadedCardId = null,
            ),
        )
        val second = cache.project(collection, LocalContentSnapshot(), nextReviews, 2_000, emptyMap(), null)

        assertEquals(0, second.deck("Deck A").newCount)
        assertEquals(1, second.deck("Deck B").newCount)
        assertNotSame(first.deck("Deck A"), second.deck("Deck A"))
        assertSame(first.deck("Deck B"), second.deck("Deck B"))
        assertDeckCountsEqual(collection.expected(nextReviews, 2_000), second)
    }

    @Test
    fun againKeepsIntradayLearningCardInTheSelectedDeckQueue() = runTest {
        val collection = collectionOf(SyncCard(1, "note-a", "Deck A"))
        val initialReviews = LocalReviewSnapshot(studyDay = 0)
        val cache = DeckCountProjectionCache()
        cache.project(collection, LocalContentSnapshot(), initialReviews, 1_000, emptyMap(), "Deck A")
        val nextReviews = initialReviews.applying(
            RecordedReviewDelta(
                schedule = schedule(1, dueAtMillis = 62_000, phase = ReviewPhase.Learning),
                noteGuid = "note-a",
                cardOrd = 0,
                deckName = "Deck A",
                reviewedAtMillis = 2_000,
                wasNew = true,
                clearedDueDateOverride = false,
                pendingDownloadedCardId = 1,
            ),
        )

        val projected = cache.project(
            collection,
            LocalContentSnapshot(),
            nextReviews,
            2_000,
            emptyMap(),
            "Deck A",
        ).deck("Deck A")

        assertTrue(projected.queueLoaded)
        assertEquals(1, projected.learningCount)
        assertEquals(listOf(1L), projected.cards.map(ReviewCard::id))
    }

    @Test
    fun reviewReprojectsSiblingCardsInOtherDecks() = runTest {
        val collection = SyncedCollection(
            notes = mapOf("note" to SyncNote("note", fields = listOf("front", "back"))),
            cards = mapOf(
                1L to SyncCard(1, "note", "Deck A", ord = 0),
                2L to SyncCard(2, "note", "Deck B", ord = 1),
            ),
            deckNames = setOf("Deck A", "Deck B"),
        )
        val initialReviews = LocalReviewSnapshot(studyDay = 0)
        val cache = DeckCountProjectionCache()
        val first = cache.project(collection, LocalContentSnapshot(), initialReviews, 1_000, emptyMap(), null)
        assertEquals(1, first.deck("Deck B").newCount)

        val nextReviews = initialReviews.applying(
            RecordedReviewDelta(
                schedule = schedule(1, dueAtMillis = MillisPerDay),
                noteGuid = "note",
                cardOrd = 0,
                deckName = "Deck A",
                reviewedAtMillis = 2_000,
                wasNew = true,
                clearedDueDateOverride = false,
                pendingDownloadedCardId = null,
            ),
        )
        val second = cache.project(collection, LocalContentSnapshot(), nextReviews, 2_000, emptyMap(), null)

        assertEquals(0, second.deck("Deck B").newCount)
        assertNotSame(first.deck("Deck B"), second.deck("Deck B"))
        assertDeckCountsEqual(collection.expected(nextReviews, 2_000), second)
    }

    @Test
    fun contentAndSyncChangesInvalidateOnlyChangedDeckSources() = runTest {
        val original = collectionOf(
            SyncCard(1, "note-a", "Deck A"),
            SyncCard(2, "note-b", "Deck B"),
        )
        val reviews = LocalReviewSnapshot(studyDay = 0)
        val cache = DeckCountProjectionCache()
        val first = cache.project(original, LocalContentSnapshot(), reviews, 1_000, emptyMap(), null)

        val editedNote = original.copy(
            notes = original.notes + ("note-a" to original.notes.getValue("note-a").copy(fields = listOf("edited"))),
        )
        val afterTextEdit = cache.project(editedNote, LocalContentSnapshot(), reviews, 1_000, emptyMap(), null)
        assertSame(first.deck("Deck A"), afterTextEdit.deck("Deck A"))
        assertSame(first.deck("Deck B"), afterTextEdit.deck("Deck B"))

        val added = editedNote.copy(
            notes = editedNote.notes + ("note-c" to SyncNote("note-c", fields = listOf("c"))),
            cards = editedNote.cards + (3L to SyncCard(3, "note-c", "Deck B")),
        )
        val afterAdd = cache.project(added, LocalContentSnapshot(), reviews, 1_000, emptyMap(), null)
        assertSame(afterTextEdit.deck("Deck A"), afterAdd.deck("Deck A"))
        assertNotSame(afterTextEdit.deck("Deck B"), afterAdd.deck("Deck B"))
        assertEquals(2, afterAdd.deck("Deck B").newCount)

        val moved = added.copy(cards = added.cards + (3L to added.cards.getValue(3).copy(deckName = "Deck A")))
        val afterMove = cache.project(moved, LocalContentSnapshot(), reviews, 1_000, emptyMap(), null)
        assertEquals(2, afterMove.deck("Deck A").newCount)
        assertEquals(1, afterMove.deck("Deck B").newCount)
        assertDeckCountsEqual(moved.expected(reviews, 1_000), afterMove)
    }

    @Test
    fun freshReviewSnapshotFallsBackToExactPerDeckDiff() = runTest {
        val collection = collectionOf(
            SyncCard(1, "note-a", "Deck A"),
            SyncCard(2, "note-b", "Deck B"),
        )
        val initial = LocalReviewSnapshot(studyDay = 0)
        val cache = DeckCountProjectionCache()
        val first = cache.project(collection, LocalContentSnapshot(), initial, 1_000, emptyMap(), null)
        val reloaded = LocalReviewSnapshot(
            studyDay = 0,
            schedules = mapOf(2L to schedule(2, dueAtMillis = MillisPerDay)),
        )

        val second = cache.project(collection, LocalContentSnapshot(), reloaded, 1_000, emptyMap(), null)

        assertSame(first.deck("Deck A"), second.deck("Deck A"))
        assertNotSame(first.deck("Deck B"), second.deck("Deck B"))
        assertDeckCountsEqual(collection.expected(reloaded, 1_000), second)
    }

    @Test
    fun optionsAndPendingMetadataDoNotInvalidateUnrelatedCounts() = runTest {
        val collection = collectionOf(
            SyncCard(1, "note-a", "Deck A"),
            SyncCard(2, "note-b", "Deck B"),
        )
        val reviews = LocalReviewSnapshot(studyDay = 0)
        val cache = DeckCountProjectionCache()
        val first = cache.project(collection, LocalContentSnapshot(), reviews, 1_000, emptyMap(), null)
        val content = LocalContentSnapshot(deckOptions = mapOf("Deck A" to DeckOptions(newCardsPerDay = 0)))
        val pending = mapOf("Deck B" to PendingDeckChanges(changedCardIds = setOf(2)))

        val second = cache.project(collection, content, reviews, 1_000, pending, null)

        assertEquals(0, second.deck("Deck A").newCount)
        assertNotSame(first.deck("Deck A"), second.deck("Deck A"))
        assertEquals(PendingDeckChanges(changedCardIds = setOf(2)), second.deck("Deck B").pendingChanges)
        assertDeckCountsEqual(collection.expected(reviews, 1_000, content), second)
    }

    @Test
    fun syncedDeckLimitChangeInvalidatesTheAffectedDeck() = runTest {
        val original = collectionOf(
            SyncCard(1, "note-a", "Deck A"),
            SyncCard(2, "note-b", "Deck A"),
            SyncCard(3, "note-c", "Deck B"),
        )
        val reviews = LocalReviewSnapshot(studyDay = 0)
        val cache = DeckCountProjectionCache()
        val first = cache.project(original, LocalContentSnapshot(), reviews, 1_000, emptyMap(), null)
        val changed = original.copy(
            deckRecords = mapOf(
                "Deck A" to SyncDeck(
                    name = "Deck A",
                    config = buildJsonObject { put("newLimit", 1) },
                ),
            ),
        )

        val second = cache.project(changed, LocalContentSnapshot(), reviews, 1_000, emptyMap(), null)

        assertEquals(1, second.deck("Deck A").newCount)
        assertNotSame(first.deck("Deck A"), second.deck("Deck A"))
        assertSame(first.deck("Deck B"), second.deck("Deck B"))
    }

    @Test
    fun timeInvalidationOccursAtLearnAheadBoundary() = runTest {
        val dueAt = 30L * 60L * 1_000L
        val collection = collectionOf(SyncCard(1, "note-a", "Deck A"))
        val reviews = LocalReviewSnapshot(
            studyDay = 0,
            schedules = mapOf(1L to schedule(1, dueAt, ReviewPhase.Learning)),
        )
        val cache = DeckCountProjectionCache()
        val initial = cache.project(collection, LocalContentSnapshot(), reviews, 0, emptyMap(), null)
        val beforeBoundary = cache.project(
            collection,
            LocalContentSnapshot(),
            reviews,
            9L * 60L * 1_000L,
            emptyMap(),
            null,
        )
        val atBoundary = cache.project(
            collection,
            LocalContentSnapshot(),
            reviews,
            10L * 60L * 1_000L,
            emptyMap(),
            null,
        )

        assertEquals(0, initial.deck("Deck A").learningCount)
        assertSame(initial.deck("Deck A"), beforeBoundary.deck("Deck A"))
        assertEquals(1, atBoundary.deck("Deck A").learningCount)
        assertNotSame(beforeBoundary.deck("Deck A"), atBoundary.deck("Deck A"))
    }

    @Test
    fun mixedIncrementalChangesAlwaysMatchCanonicalProjection() = runTest {
        val cards = (1L..24L).map { cardId ->
            SyncCard(cardId, "note-$cardId", "Deck ${(cardId % 4L) + 1L}")
        }
        var collection = collectionOf(*cards.toTypedArray())
        var reviews = LocalReviewSnapshot(studyDay = 0)
        var content = LocalContentSnapshot()
        var nowMillis = 1_000L
        val cache = DeckCountProjectionCache()

        repeat(150) { step ->
            val cardId = (step % cards.size + 1).toLong()
            val card = collection.cards.getValue(cardId)
            when (step % 10) {
                0 -> reviews = reviews.copy(
                    schedules = reviews.schedules + (
                        cardId to schedule(
                            cardId,
                            dueAtMillis = nowMillis + ((step % 3) - 1) * 600_000L,
                            phase = ReviewPhase.entries[step % ReviewPhase.entries.size],
                        )
                    ),
                )
                1 -> reviews = reviews.copy(
                    dueDateOverrides = if (cardId in reviews.dueDateOverrides) {
                        reviews.dueDateOverrides - cardId
                    } else {
                        reviews.dueDateOverrides + (cardId to (nowMillis + 300_000L))
                    },
                )
                2 -> reviews = reviews.copy(
                    buriedCardIds = reviews.buriedCardIds.toggle(cardId),
                )
                3 -> reviews = reviews.copy(
                    buriedNoteGuids = reviews.buriedNoteGuids.toggle(card.noteGuid),
                )
                4 -> reviews = reviews.copy(
                    studiedTodayByDeck = reviews.studiedTodayByDeck + (
                        card.deckName to DeckStudyCounts(step % 5, step % 7)
                    ),
                )
                5 -> reviews = reviews.copy(
                    studiedCardOrdsByNoteToday = reviews.studiedCardOrdsByNoteToday +
                        (card.noteGuid to setOf(1)),
                )
                6 -> collection = collection.copy(
                    cards = collection.cards + (
                        cardId to card.copy(
                            studyState = if (card.studyState == CardStudyState.Active) {
                                CardStudyState.Suspended
                            } else {
                                CardStudyState.Active
                            },
                        )
                    ),
                )
                7 -> collection = collection.copy(
                    cards = collection.cards + (cardId to card.copy(deckName = "Deck ${(step % 4) + 1}")),
                )
                8 -> content = content.copy(
                    deckOptions = content.deckOptions + (
                        card.deckName to DeckOptions(
                            newCardsPerDay = step % 6,
                            maximumReviewsPerDay = step % 8,
                            reviewSortOrder = ReviewSortOrder.entries[step % ReviewSortOrder.entries.size],
                        )
                    ),
                )
                9 -> nowMillis += 600_000L
            }
            val actual = cache.project(collection, content, reviews, nowMillis, emptyMap(), null)
            assertDeckCountsEqual(collection.expected(reviews, nowMillis, content), actual)
        }
    }

    @Test
    fun onlySelectedDeckHydratesAndRetainsAReviewQueue() = runTest {
        val collection = collectionOf(
            SyncCard(1, "note-a", "Deck A"),
            SyncCard(2, "note-b", "Deck B"),
        )
        val reviews = LocalReviewSnapshot(studyDay = 0)
        val cache = DeckCountProjectionCache()
        val listed = cache.project(collection, LocalContentSnapshot(), reviews, 1_000, emptyMap(), null)
        val focused = cache.project(collection, LocalContentSnapshot(), reviews, 1_000, emptyMap(), "Deck A")
        val relisted = cache.project(collection, LocalContentSnapshot(), reviews, 1_000, emptyMap(), null)

        assertFalse(listed.deck("Deck A").queueLoaded)
        assertTrue(focused.deck("Deck A").queueLoaded)
        assertEquals(1, focused.deck("Deck A").cards.size)
        assertFalse(focused.deck("Deck B").queueLoaded)
        assertFalse(relisted.deck("Deck A").queueLoaded)
        assertTrue(relisted.deck("Deck A").cards.isEmpty())
    }

    private fun collectionOf(vararg cards: SyncCard): SyncedCollection {
        val notes = cards.associate { card ->
            card.noteGuid to SyncNote(card.noteGuid, fields = listOf("front ${card.noteGuid}", "back"))
        }
        return SyncedCollection(
            notes = notes,
            cards = cards.associateBy(SyncCard::cardId),
            deckNames = cards.mapTo(mutableSetOf(), SyncCard::deckName),
        )
    }

    private fun schedule(
        cardId: Long,
        dueAtMillis: Long,
        phase: ReviewPhase = ReviewPhase.Review,
    ): LocalCardSchedule = LocalCardSchedule(
        cardId = cardId,
        phase = phase,
        dueAtMillis = dueAtMillis,
        stability = 2.0,
        difficulty = 5.0,
        scheduledDays = 2,
        repetitions = 2,
        lapses = 0,
        lastReviewAtMillis = 0,
    )

    private fun SyncedCollection.expected(
        reviews: LocalReviewSnapshot,
        nowMillis: Long,
        content: LocalContentSnapshot = LocalContentSnapshot(),
    ): List<DeckSummary> = asDeckList(
        localSchedules = reviews.schedules,
        nowMillis = nowMillis,
        deckOptions = content.deckOptions,
        studiedTodayByDeck = reviews.studiedTodayByDeck,
        studiedCardOrdsByNoteToday = reviews.studiedCardOrdsByNoteToday,
        buriedCardIds = reviews.buriedCardIds,
        buriedNoteGuids = reviews.buriedNoteGuids,
        dueDateOverrides = reviews.dueDateOverrides,
    )

    private fun <Value> Set<Value>.toggle(value: Value): Set<Value> =
        if (value in this) this - value else this + value

    private fun List<DeckSummary>.deck(name: String): DeckSummary = first { it.name == name }

    private fun assertDeckCountsEqual(expected: List<DeckSummary>, actual: List<DeckSummary>) {
        assertEquals(
            expected.map { listOf(it.name, it.newCount, it.learningCount, it.dueCount) },
            actual.map { listOf(it.name, it.newCount, it.learningCount, it.dueCount) },
        )
    }
}
