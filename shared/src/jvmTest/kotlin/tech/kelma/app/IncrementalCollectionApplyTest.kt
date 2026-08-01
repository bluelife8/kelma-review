package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncrementalCollectionApplyTest {
    @Test
    fun incrementalApplyPersistsChangedResourcesAndRemovesDeletedCardSchedule() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val removedCard = SyncCard(1L, "removed-note", "Removed")
        val retainedCard = SyncCard(2L, "retained-note", "Retained")
        val removedReview = review(1_000L, removedCard)
        val retainedReview = review(2_000L, retainedCard)
        val initial = SyncedCollection(
            notes = mapOf(
                removedCard.noteGuid to SyncNote(removedCard.noteGuid, notetypeId = 1L),
                retainedCard.noteGuid to SyncNote(retainedCard.noteGuid, notetypeId = 2L),
            ),
            cards = listOf(removedCard, retainedCard).associateBy(SyncCard::cardId),
            reviews = listOf(removedReview, retainedReview).associateBy(SyncReview::reviewId),
            studyDays = mapOf(
                "removed" to SyncStudyDay(1L, "Removed", newStudied = 1),
                "retained" to SyncStudyDay(1L, "Retained", reviewStudied = 1),
            ),
            notetypes = mapOf(
                1L to SyncNotetype(1L, "Removed type"),
                2L to SyncNotetype(2L, "Retained type"),
            ),
            deckRecords = mapOf(
                "Removed" to SyncDeck("Removed"),
                "Retained" to SyncDeck("Retained", checksum = "before"),
            ),
            media = mapOf(
                "removed.mp3" to SyncMediaFile("removed.mp3", "v1", byteArrayOf(1)),
                "retained.mp3" to SyncMediaFile("retained.mp3", "v1", byteArrayOf(2)),
            ),
            deckNames = setOf("Removed", "Retained"),
            serverTime = "before",
        )
        store.replaceCollection(initial, nowMillis = 2_000L)
        val latest = initial.copy(
            notes = mapOf(
                retainedCard.noteGuid to SyncNote(
                    retainedCard.noteGuid,
                    notetypeId = 2L,
                    fields = listOf("updated"),
                ),
            ),
            cards = mapOf(retainedCard.cardId to retainedCard),
            studyDays = mapOf("retained" to SyncStudyDay(1L, "Retained", reviewStudied = 2)),
            notetypes = mapOf(2L to SyncNotetype(2L, "Updated type")),
            deckRecords = mapOf("Retained" to SyncDeck("Retained", checksum = "after")),
            media = mapOf(
                "retained.mp3" to SyncMediaFile("retained.mp3", "v2", byteArrayOf(3, 4)),
            ),
            deckNames = setOf("Retained"),
            serverTime = "after",
        )

        val incrementalReviews = store.replaceCollectionIncrementally(
            previous = initial,
            collection = latest,
            nowMillis = 3_000L,
        )
        val persisted = store.load(nowMillis = 3_000L).collection

        assertEquals(setOf(retainedCard.cardId), incrementalReviews.schedules.keys)
        assertEquals(latest.notes, persisted.notes)
        assertEquals(latest.cards, persisted.cards)
        assertEquals(latest.reviews, persisted.reviews)
        assertEquals(latest.studyDays.values.toSet(), persisted.studyDays.values.toSet())
        assertEquals(latest.notetypes, persisted.notetypes)
        assertEquals(latest.deckRecords, persisted.deckRecords)
        assertEquals(latest.deckNames, persisted.deckNames)
        assertEquals(setOf("retained.mp3"), persisted.media.keys)
        assertEquals("v2", persisted.media.getValue("retained.mp3").modifiedAt)
        assertEquals(2L, persisted.media.getValue("retained.mp3").sizeBytes)
        assertEquals("after", persisted.serverTime)
        driver.close()
    }

    @Test
    fun reviewFreeLoadSkipsRevlogButKeepsContentAndUploadPlanning() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val card = SyncCard(1L, "note-1", "Deck")
        val collection = SyncedCollection(
            notes = mapOf(card.noteGuid to SyncNote(card.noteGuid, fields = listOf("front", "back"))),
            cards = mapOf(card.cardId to card),
            reviews = (1..25).associate { index ->
                val reviewId = index * 1_000L
                reviewId to review(reviewId, card)
            },
            deckRecords = mapOf("Deck" to SyncDeck("Deck")),
            deckNames = setOf("Deck"),
            serverTime = "before",
        )
        store.replaceCollection(collection, nowMillis = 30_000L)

        val full = loadDownloadedCollection(database.kelmaQueries, Json { ignoreUnknownKeys = true })
        val lite = loadDownloadedCollection(
            database.kelmaQueries,
            Json { ignoreUnknownKeys = true },
            includeReviews = false,
        )

        // The heavy revlog is skipped, but every other content/scheduling map is intact.
        assertEquals(25, full.reviews.size)
        assertTrue(lite.reviews.isEmpty())
        assertEquals(full.notes, lite.notes)
        assertEquals(full.cards, lite.cards)
        assertEquals(full.deckRecords, lite.deckRecords)
        assertEquals(full.notetypes, lite.notetypes)

        // Upload planning still works even though the outbox loader skips the revlog.
        store.recordReview(card, Rating.Good, reviewedAtMillis = 40_000L)
        val plan = store.prepareSyncUpload()
        assertEquals(1, plan.reviews.size)
        assertEquals(card.noteGuid, plan.reviews.single().noteGuid)
        driver.close()
    }

    private fun review(reviewId: Long, card: SyncCard): SyncReview = SyncReview(
        reviewId = reviewId,
        sourceCardId = card.cardId,
        noteGuid = card.noteGuid,
        cardOrd = card.ord,
        deckName = card.deckName,
        ease = Rating.Good.ordinal + 1,
    )
}
