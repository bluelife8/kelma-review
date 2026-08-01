package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import tech.kelma.db.KelmaDatabase

class DeckPresetPersistenceTest {
    @Test
    fun sharedPresetPersistsPropagatesRenamesAndPreservesOptionsWhenDeleted() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        var store = PersistentCollectionStore(database)
        store.createLocalDeck("A", 1L)
        store.createLocalDeck("B", 2L)
        val initial = DeckOptions(
            newCardsPerDay = 40,
            buryInterdayLearningSiblings = true,
            confirmBeforeUndo = false,
        )
        store.saveDeckOptions("A", initial, 3L)

        var content = store.createDeckOptionsPreset("A", "Focused", initial, 4L)
        val preset = content.deckPresets.presets.single()
        content = store.assignDeckOptionsPreset("B", preset.id, 5L)
        assertEquals(initial.validated(), content.deckOptions.getValue("A"))
        assertEquals(initial.validated(), content.deckOptions.getValue("B"))

        val changed = initial.copy(newCardsPerDay = 70, buryNewSiblings = false)
        content = store.saveDeckOptions("B", changed, 6L)
        assertEquals(changed.validated(), content.deckOptions.getValue("A"))
        assertEquals(changed.validated(), content.deckOptions.getValue("B"))

        store = PersistentCollectionStore(database)
        content = store.loadLocalContent()
        assertEquals(setOf("A", "B"), content.deckPresets.assignments.keys)
        assertEquals(changed.validated(), content.deckOptions.getValue("A"))

        content = store.renameLocalDeck("B", "C", 7L)
        assertEquals(preset.id, content.deckPresets.assignments.getValue("C"))
        content = store.renameDeckOptionsPreset(preset.id, "Focused 2", 8L)
        assertEquals("Focused 2", content.deckPresets.presets.single().name)

        content = store.deleteDeckOptionsPreset(preset.id, 9L)
        assertTrue(content.deckPresets.presets.isEmpty())
        assertTrue(content.deckPresets.assignments.isEmpty())
        assertEquals(changed.validated(), content.deckOptions.getValue("A"))
        assertEquals(changed.validated(), content.deckOptions.getValue("C"))
        driver.close()
    }

    @Test
    fun presetNamesAreUniqueAndAccountChangesClearPresetState() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        store.createLocalDeck("Deck", 1L)
        store.createDeckOptionsPreset("Deck", "Shared", DeckOptions(), 2L)

        assertFailsWith<IllegalArgumentException> {
            store.createDeckOptionsPreset("Deck", " shared ", DeckOptions(), 3L)
        }
        store.saveSignedInState(
            StoredSyncAuth("one", "client-one", DefaultKelmaSyncEndpoint, "one@example.com"),
            SyncedCollection(deckNames = setOf("Deck")),
            4L,
        )
        store.saveSignedInState(
            StoredSyncAuth("two", "client-two", DefaultKelmaSyncEndpoint, "two@example.com"),
            SyncedCollection(deckNames = setOf("Other")),
            5L,
        )

        assertTrue(store.loadLocalContent().deckPresets.presets.isEmpty())
        driver.close()
    }
}
