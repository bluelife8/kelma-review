package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import tech.kelma.db.KelmaDatabase

class PluginRendererAssignmentTest {
    @Test
    fun deckAssignmentOverridesNoteTypeAndSurvivesRestart() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        store.createLocalDeck("Japanese")
        store.setPluginRendererAssignment(
            PluginRendererScope.NoteType,
            "100",
            "tech.kelma.render.note",
        )
        store.setPluginRendererAssignment(
            PluginRendererScope.Deck,
            "Japanese",
            "tech.kelma.render.deck",
        )
        store.setPluginRendererAssignment(
            PluginRendererScope.NoteType,
            NotetypeCatalog.BasicId.toString(),
            "tech.kelma.render.basic",
        )

        val restored = PersistentCollectionStore(database).loadPluginRendererAssignments()
        val card = SyncCard(1, "note", "JAPANESE::Alphabet")
        val note = SyncNote("note", notetypeId = 100)
        assertEquals("tech.kelma.render.deck", restored.rendererFor(card, note))
        assertEquals(
            "tech.kelma.render.basic",
            restored.rendererFor(
                card.copy(deckName = "Other"),
                SyncNote("basic", notetypeId = NotetypeCatalog.BasicId),
            ),
        )

        store.renameLocalDeck("Japanese", "Languages::Japanese")
        val renamedCard = card.copy(deckName = "Languages::Japanese::Alphabet")
        val renamed = store.loadPluginRendererAssignments()
        assertEquals("tech.kelma.render.deck", renamed.rendererFor(renamedCard, note))

        val withoutDeck = store.setPluginRendererAssignment(
            PluginRendererScope.Deck,
            "Languages::Japanese",
            null,
        )
        assertEquals("tech.kelma.render.note", withoutDeck.rendererFor(renamedCard, note))

        store.setPluginRendererAssignment(
            PluginRendererScope.Deck,
            "Languages::Japanese::Alphabet",
            "tech.kelma.render.child",
        )
        store.deleteLocalDeck("Languages::Japanese")
        assertEquals(
            "tech.kelma.render.note",
            store.loadPluginRendererAssignments().rendererFor(renamedCard, note),
        )

        store.clearAll()
        assertNull(store.loadPluginRendererAssignments().rendererFor(card, note))
        driver.close()
    }
}
