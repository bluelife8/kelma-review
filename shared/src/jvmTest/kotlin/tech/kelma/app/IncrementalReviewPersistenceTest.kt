package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.collections.immutable.PersistentMap
import tech.kelma.db.KelmaDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class IncrementalReviewPersistenceTest {
    @Test
    fun downloadedReviewDeltaMatchesFullReloadAcrossRepeatedReviews() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val card = SyncCard(20, "note-1", "Deck")
        val collection = SyncedCollection(
            notes = mapOf("note-1" to SyncNote("note-1", fields = listOf("front", "back"))),
            cards = mapOf(card.cardId to card),
            deckNames = setOf(card.deckName),
        )
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            collection,
            IncrementalReviewNow,
        )

        val initial = store.loadLocalReviews(IncrementalReviewNow)
        val first = store.recordReviewIncrementally(
            card,
            Rating.Good,
            currentSnapshot = initial,
            options = DeckOptions(),
            reviewedAtMillis = IncrementalReviewNow,
        )
        assertEquals(store.loadLocalReviews(IncrementalReviewNow), first.snapshot)
        assertTrue(first.snapshot.schedules is PersistentMap<*, *>)

        val secondTime = first.schedule.dueAtMillis
        val second = store.recordReviewIncrementally(
            card,
            Rating.Again,
            currentSnapshot = first.snapshot,
            options = DeckOptions(),
            reviewedAtMillis = secondTime,
        )
        assertEquals(store.loadLocalReviews(secondTime), second.snapshot)
        driver.close()
    }

    @Test
    fun futureMutationIdsDoNotMoveShortLearningStepsOutOfLearnAhead() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val cards = listOf(
            SyncCard(30, "future-note", "Deck"),
            SyncCard(31, "current-note", "Deck"),
        )
        val collection = SyncedCollection(
            notes = cards.associate { it.noteGuid to SyncNote(it.noteGuid, fields = listOf("front", "back")) },
            cards = cards.associateBy(SyncCard::cardId),
            deckNames = setOf("Deck"),
        )
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            collection,
            IncrementalReviewNow,
        )
        val futureMutationTime = IncrementalReviewNow + MillisPerDay
        store.recordReview(
            cards.first(),
            Rating.Good,
            reviewedAtMillis = futureMutationTime,
        )

        val reviewed = store.recordReviewIncrementally(
            cards.last(),
            Rating.Again,
            currentSnapshot = store.loadLocalReviews(IncrementalReviewNow),
            options = DeckOptions(),
            reviewedAtMillis = IncrementalReviewNow,
        )

        assertEquals(ReviewPhase.Learning, reviewed.schedule.phase)
        assertEquals(IncrementalReviewNow + 60_000L, reviewed.schedule.dueAtMillis)
        store.load(IncrementalReviewNow)
        assertEquals(
            IncrementalReviewNow + 60_000L,
            store.loadLocalReviews(IncrementalReviewNow).schedules.getValue(cards.last().cardId).dueAtMillis,
        )
        driver.close()
    }

    @Test
    fun localReviewAndClearedDueDateDeltaMatchFullReload() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val added = store.addLocalNote(
            AddNoteDraft("Local", "front", "back"),
            nowMillis = IncrementalReviewNow,
            noteGuid = "local-note",
        )
        val card = added.content.cards.values.single()
        val initial = store.loadLocalReviews(IncrementalReviewNow)
        val dueAt = IncrementalReviewNow + MillisPerDay
        val withDueDate = store.setCardDueDate(card.cardId, dueAt, IncrementalReviewNow + 1)

        val reviewedAt = IncrementalReviewNow + 2
        val reviewed = store.recordReviewIncrementally(
            card,
            Rating.Good,
            currentSnapshot = withDueDate,
            options = DeckOptions(),
            reviewedAtMillis = reviewedAt,
        )

        assertEquals(store.loadLocalReviews(reviewedAt), reviewed.snapshot)
        assertEquals(initial.pendingSyncByDeck, reviewed.snapshot.pendingSyncByDeck)
        driver.close()
    }

    @Test
    fun syncedRolloverReclassifiesImmutableReviews() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val cards = listOf(
            SyncCard(40, "before-boundary", "Deck"),
            SyncCard(41, "after-boundary", "Deck"),
        )
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            SyncedCollection(
                notes = cards.associate { card ->
                    card.noteGuid to SyncNote(card.noteGuid, fields = listOf("front", "back"))
                },
                cards = cards.associateBy(SyncCard::cardId),
                deckNames = setOf("Deck"),
            ),
        )
        store.observeCloudStudyDayPolicy(
            AccountStudyDayPolicy(
                version = 1,
                timezoneId = "America/New_York",
                dayStartHour = 4,
            ),
        )
        val beforeBoundary = Instant.parse("2025-03-09T07:30:00Z").toEpochMilliseconds()
        val afterBoundary = Instant.parse("2025-03-09T08:30:00Z").toEpochMilliseconds()
        store.recordReview(cards[0], Rating.Good, reviewedAtMillis = beforeBoundary)
        store.recordReview(cards[1], Rating.Good, reviewedAtMillis = afterBoundary)

        assertEquals(1, store.loadLocalReviews(beforeBoundary + 10 * 60_000L).reviewedToday)
        assertEquals(1, store.loadLocalReviews(afterBoundary + 10 * 60_000L).reviewedToday)

        store.observeCloudStudyDayPolicy(
            AccountStudyDayPolicy(version = 2, timezoneId = "UTC", dayStartHour = 0),
        )
        assertEquals(2, store.loadLocalReviews(afterBoundary + 10 * 60_000L).reviewedToday)
        driver.close()
    }

    @Test
    fun remoteOneDayReviewIsDueFromTheNextStudyDayRollover() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val policy = AccountStudyDayPolicy(
            version = 1,
            timezoneId = "America/New_York",
            dayStartHour = 4,
        )
        store.observeCloudStudyDayPolicy(policy)
        val card = SyncCard(45, "rollover-note", "Arabic Letters")
        val reviewedAt = Instant.parse("2026-07-30T23:55:58Z").toEpochMilliseconds()
        val reviews = listOf(
            reviewedAt - 30 * 60_000L to Rating.Good,
            reviewedAt - 20 * 60_000L to Rating.Good,
            reviewedAt - 10 * 60_000L to Rating.Again,
            reviewedAt to Rating.Good,
        )
        val now = Instant.parse("2026-07-31T21:00:00Z").toEpochMilliseconds()
        store.replaceCollection(
            SyncedCollection(
                notes = mapOf(card.noteGuid to SyncNote(card.noteGuid, fields = listOf("front", "back"))),
                cards = mapOf(card.cardId to card),
                reviews = reviews.associate { (reviewId, rating) ->
                    reviewId to SyncReview(
                        reviewId = reviewId,
                        sourceCardId = card.cardId,
                        noteGuid = card.noteGuid,
                        cardOrd = card.ord,
                        deckName = card.deckName,
                        ease = rating.ordinal + 1,
                    )
                },
                deckNames = setOf(card.deckName),
            ),
            nowMillis = now,
        )

        val state = store.load(now)
        val schedule = state.localReviews.schedules.getValue(card.cardId)
        assertEquals(ReviewPhase.Review, schedule.phase)
        assertEquals(1, schedule.scheduledDays)
        assertEquals(
            Instant.parse("2026-07-31T08:00:00Z").toEpochMilliseconds(),
            schedule.dueAtMillis,
        )
        assertEquals(
            1,
            state.collection.asDeck(
                name = card.deckName,
                localSchedules = state.localReviews.schedules,
                nowMillis = now,
                studyDayPolicy = policy,
            )?.dueCount,
        )
        driver.close()
    }

    @Test
    fun persistedLearningAndRelearningAnswersDoNotExhaustReviewLimit() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val card = SyncCard(50, "quota-note", "Deck")
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            SyncedCollection(
                notes = mapOf(card.noteGuid to SyncNote(card.noteGuid, fields = listOf("front", "back"))),
                cards = mapOf(card.cardId to card),
                deckNames = setOf(card.deckName),
            ),
            IncrementalReviewNow,
        )
        val options = DeckOptions()
        val first = store.recordReviewIncrementally(
            card,
            Rating.Good,
            store.loadLocalReviews(IncrementalReviewNow),
            options,
            IncrementalReviewNow,
        )
        val graduated = store.recordReviewIncrementally(
            card,
            Rating.Good,
            first.snapshot,
            options,
            first.schedule.dueAtMillis,
        )
        assertEquals(ReviewPhase.Review, graduated.schedule.phase)
        val failedReview = store.recordReviewIncrementally(
            card,
            Rating.Again,
            graduated.snapshot,
            options,
            graduated.schedule.lastReviewAtMillis + 60_000L,
        )
        val relearningRepeat = store.recordReviewIncrementally(
            card,
            Rating.Again,
            failedReview.snapshot,
            options,
            failedReview.schedule.dueAtMillis,
        )

        assertEquals(
            DeckStudyCounts(newCards = 1, reviews = 1),
            relearningRepeat.snapshot.studiedTodayByDeck.getValue("Deck"),
        )
        assertEquals(
            relearningRepeat.snapshot.studiedTodayByDeck,
            store.loadLocalReviews(relearningRepeat.schedule.lastReviewAtMillis).studiedTodayByDeck,
        )
        driver.close()
    }

    @Test
    fun synchronizedDailyCounterDefinesRemoteReviewCapacityConsumption() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val now = IncrementalReviewNow + 1_000L
        val policy = AccountStudyDayPolicy(version = 1, timezoneId = "UTC", dayStartHour = 0)
        val cards = listOf(
            SyncCard(60, "remote-one", "Deck"),
            SyncCard(61, "remote-two", "Deck"),
        )
        fun review(id: Long, card: SyncCard, kind: Int) = SyncReview(
            reviewId = id,
            sourceCardId = card.cardId,
            noteGuid = card.noteGuid,
            cardOrd = card.ord,
            deckName = card.deckName,
            ease = 3,
            reviewKind = kind,
        )
        val history = listOf(
            review(now - 2 * MillisPerDay, cards[0], 0),
            review(now - 2 * MillisPerDay + 1, cards[1], 0),
            review(now, cards[0], 1),
            review(now + 1, cards[0], 2),
            review(now + 2, cards[1], 1),
        )
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            SyncedCollection(
                notes = cards.associate { card ->
                    card.noteGuid to SyncNote(card.noteGuid, fields = listOf("front", "back"))
                },
                cards = cards.associateBy(SyncCard::cardId),
                reviews = history.associateBy(SyncReview::reviewId),
                studyDays = mapOf(
                    "day" to SyncStudyDay(
                        day = studyDayAt(now, policy),
                        deckName = "Deck",
                        reviewStudied = 1,
                    ),
                ),
                deckNames = setOf("Deck"),
            ),
            nowMillis = now,
        )
        store.observeCloudStudyDayPolicy(policy)

        val restored = store.loadLocalReviews(now + 2)

        assertEquals(3, restored.reviewedToday)
        assertEquals(DeckStudyCounts(reviews = 1), restored.studiedTodayByDeck.getValue("Deck"))
        driver.close()
    }

    @Test
    fun snapshotFromPreviousDayFallsBackToFullReload() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val card = SyncCard(7, "day-note", "Deck")
        val collection = SyncedCollection(
            notes = mapOf("day-note" to SyncNote("day-note", fields = listOf("front", "back"))),
            cards = mapOf(card.cardId to card),
            deckNames = setOf(card.deckName),
        )
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            collection,
            IncrementalReviewNow,
        )
        val previousDay = store.loadLocalReviews(IncrementalReviewNow)
        val reviewedAt = IncrementalReviewNow + MillisPerDay

        val reviewed = store.recordReviewIncrementally(
            card,
            Rating.Good,
            currentSnapshot = previousDay,
            options = DeckOptions(),
            reviewedAtMillis = reviewedAt,
        )

        assertEquals(store.loadLocalReviews(reviewedAt), reviewed.snapshot)
        driver.close()
    }
}

private const val IncrementalReviewNow = 10L * MillisPerDay
