package tech.kelma.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnkiInterchangeAndroidDeviceTest {
    @Test
    fun sqliteAndZstandardRoundTripOnAndroidRuntime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val codec = AnkiSqliteCodec(AndroidTemporarySqliteFiles(context))
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
                AnkiNoteRow(
                    id = 1,
                    guid = "guid",
                    notetypeId = 10,
                    modifiedAtSeconds = 1_700_000_000,
                    tags = "",
                    fields = "front\u001fback",
                    sortField = "front",
                    checksum = 1,
                ),
            ),
            cards = listOf(
                AnkiCardRow(
                    id = 2,
                    noteId = 1,
                    deckId = 20,
                    ordinal = 0,
                    modifiedAtSeconds = 1_700_000_000,
                    type = 0,
                    queue = 0,
                    due = 1,
                    interval = 0,
                    factor = 0,
                    repetitions = 0,
                    lapses = 0,
                    remainingSteps = 0,
                ),
            ),
            reviews = emptyList(),
        )
        val decoded = codec.decode(codec.encode(expected))
        val payload = "Android Zstandard".repeat(100).encodeToByteArray()

        assertEquals("guid", decoded.notes.single().guid)
        assertEquals(2, decoded.cards.single().id)
        assertTrue(zstdDecompress(zstdCompress(payload)).contentEquals(payload))

        val media = byteArrayOf(5, 6, 7)
        val note = SyncNote("android-guid", NotetypeCatalog.BasicId, listOf("<img src=\"android.png\">front", "back"))
        val card = SyncCard(10, note.guid, "Android", 0, JsonObject(emptyMap()))
        val collection = SyncedCollection(
            notes = mapOf(note.guid to note),
            cards = mapOf(card.cardId to card),
            notetypes = NotetypeCatalog.definitions,
            media = mapOf("android.png" to SyncMediaFile("android.png", "", media)),
            deckNames = setOf("Android"),
        )
        val service = CollectionInterchangeService(AndroidTemporarySqliteFiles(context))
        val exported = service.export(
            collection,
            CollectionExportOptions(CollectionExportFormat.AnkiDeckPackage, "Android"),
            mapOf("Android" to DeckOptions()),
            DeckPresetState(),
            emptyMap(),
            emptyList(),
            1_735_689_600_000L,
        )
        val imported = service.previewImport(InterchangeDocument(exported.filename, exported.bytes))
        assertEquals(listOf("<img src=\"android.png\">front", "back"), imported.notes.single().fields)
        assertContentEquals(media, imported.media.single().bytes)
    }
}
