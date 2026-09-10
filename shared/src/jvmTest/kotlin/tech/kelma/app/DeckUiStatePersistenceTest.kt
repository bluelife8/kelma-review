package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import tech.kelma.db.KelmaDatabase

class DeckUiStatePersistenceTest {
    @Test
    fun migrationThirtyThreeAddsCollapsedDeckGroups() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        KelmaDatabase.Schema.migrate(driver, 33, 34)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        store.setDeckCollapsed("Languages", true)

        assertEquals(setOf("Languages"), store.loadCollapsedDeckIds())
        driver.close()
    }

    @Test
    fun collapsedDeckGroupsSurviveStoreRecreation() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)

        store.setDeckCollapsed("Languages", true)
        store.setDeckCollapsed("languages", true)
        store.setDeckCollapsed("Languages::French", true)

        assertEquals(
            setOf("Languages", "Languages::French"),
            PersistentCollectionStore(database).loadCollapsedDeckIds(),
        )

        store.setDeckCollapsed("languages", false)
        assertEquals(setOf("Languages::French"), store.loadCollapsedDeckIds())
        driver.close()
    }
}
