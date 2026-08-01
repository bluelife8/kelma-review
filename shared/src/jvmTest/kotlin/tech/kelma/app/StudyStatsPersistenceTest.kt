package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import tech.kelma.db.KelmaDatabase

class StudyStatsPersistenceTest {
    @Test
    fun confirmedAndPendingReviewsAreCountedOnceWithoutReadingCardContent() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val now = 20_000L * MillisPerDay + 1_000L
        val card = SyncCard(1, "note", "Deck")
        store.saveSignedInState(
            StoredSyncAuth("token", "client", DefaultKelmaSyncEndpoint, "user"),
            SyncedCollection(
                notes = mapOf("note" to SyncNote("note")),
                cards = mapOf(1L to card),
                reviews = mapOf(
                    (now - 500) to SyncReview(
                        reviewId = now - 500,
                        sourceCardId = 1,
                        noteGuid = "note",
                        ease = 1,
                        takenMillis = 2_000,
                    ),
                ),
                deckNames = setOf("Deck"),
            ),
            now,
        )
        store.recordReview(card, Rating.Good, now, 3_000)

        val stats = store.loadStudyStats(now)

        assertEquals(2, stats.totalReviews)
        assertEquals(2, stats.reviewsToday)
        assertEquals(1, stats.recalledReviews)
        assertEquals(1, stats.forgottenReviews)
        assertEquals(5_000, stats.studiedMillisToday)
        driver.close()
    }
}
