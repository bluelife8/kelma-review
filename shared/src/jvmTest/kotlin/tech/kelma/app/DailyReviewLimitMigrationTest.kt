package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import tech.kelma.db.KelmaDatabase

class DailyReviewLimitMigrationTest {
    @Test
    fun migrationThirtyOneMarksExistingReviewLimitConsumptionForFallback() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            """CREATE TABLE local_review_events (
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                card_id INTEGER NOT NULL,
                note_guid TEXT NOT NULL,
                card_ord INTEGER NOT NULL,
                deck_name TEXT NOT NULL,
                rating TEXT NOT NULL,
                reviewed_at_ms INTEGER NOT NULL,
                study_day INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                before_json TEXT,
                after_json TEXT NOT NULL,
                was_new INTEGER NOT NULL,
                review_id INTEGER NOT NULL,
                upload_state TEXT NOT NULL DEFAULT 'pending'
            )""".trimIndent(),
            0,
        )
        driver.execute(
            null,
            """INSERT INTO local_review_events(
                card_id,note_guid,card_ord,deck_name,rating,reviewed_at_ms,study_day,duration_ms,
                before_json,after_json,was_new,review_id
            ) VALUES (1,'note',0,'Deck','Good',1000,0,0,'{"phase":"Review"}','{}',0,1000)""".trimIndent(),
            0,
        )

        KelmaDatabase.Schema.migrate(driver, 31, 32)
        val consumed = KelmaDatabase(driver).kelmaQueries.selectAllLocalReviewEvents {
                _, _, _, _, _, _, _, _, _, _, _, _, _, _, consumedReviewLimit ->
            consumedReviewLimit
        }.executeAsOne()

        assertEquals(-1L, consumed)
        driver.close()
    }
}
