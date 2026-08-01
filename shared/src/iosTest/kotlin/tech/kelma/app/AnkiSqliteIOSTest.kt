package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject

class AnkiSqliteIOSTest {
    @Test
    fun temporaryNativeDatabaseRoundTripsLegacyAnkiRows() {
        val expected = AnkiDatabaseSnapshot(
            collection = AnkiCollectionRow(
                createdAtSeconds = 1_700_000_000,
                modifiedAtMillis = 1_700_000_000_000,
                schemaModifiedAtMillis = 1_700_000_000_000,
                configurationJson = "{}",
                modelsJson = "{}",
                decksJson = "{}",
                deckConfigurationsJson = "{}",
            ),
            notes = listOf(
                AnkiNoteRow(1, "guid", 10, 1_700_000_000, fields = "front\u001fback", tags = "", sortField = "front", checksum = 1),
            ),
            cards = listOf(
                AnkiCardRow(2, 1, 20, 0, 1_700_000_000, type = 0, queue = 0, due = 1, interval = 0, factor = 0, repetitions = 0, lapses = 0, remainingSteps = 0),
            ),
            reviews = emptyList(),
        )

        val decoded = AnkiSqliteCodec(IosTemporarySqliteFiles()).decode(
            AnkiSqliteCodec(IosTemporarySqliteFiles()).encode(expected),
        )

        assertEquals("guid", decoded.notes.single().guid)
        assertEquals("front\u001fback", decoded.notes.single().fields)
        assertEquals(2, decoded.cards.single().id)
    }

    @Test
    fun currentPackageRoundTripsThroughNativeZipZstdAndSqlite() {
        val media = byteArrayOf(1, 2, 3, 4)
        val note = SyncNote("ios-guid", NotetypeCatalog.BasicId, listOf("<img src=\"ios.png\">front", "back"))
        val card = SyncCard(10, note.guid, "iOS", 0, JsonObject(emptyMap()))
        val collection = SyncedCollection(
            notes = mapOf(note.guid to note),
            cards = mapOf(card.cardId to card),
            notetypes = NotetypeCatalog.definitions,
            media = mapOf("ios.png" to SyncMediaFile("ios.png", "", media)),
            deckNames = setOf("iOS"),
        )
        val service = CollectionInterchangeService(IosTemporarySqliteFiles())
        val exported = service.export(
            collection,
            CollectionExportOptions(CollectionExportFormat.AnkiDeckPackage, "iOS"),
            mapOf("iOS" to DeckOptions()),
            DeckPresetState(),
            emptyMap(),
            emptyList(),
            1_735_689_600_000L,
        )

        val imported = service.previewImport(InterchangeDocument(exported.filename, exported.bytes))
        assertEquals(listOf("<img src=\"ios.png\">front", "back"), imported.notes.single().fields)
        assertContentEquals(media, imported.media.single().bytes)
    }
}
