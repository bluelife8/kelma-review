package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import tech.kelma.db.KelmaDatabase

class KelmaJsonInterchangeTest {
    @Test
    fun versionTwoCollectionRoundTripsHistoryPresetsEmptyDecksAndMediaIdempotently() {
        val reviewedAt = 1_735_689_600_000L
        val mediaBytes = byteArrayOf(1, 3, 5, 7)
        val note = SyncNote(
            guid = "kelma-json-guid",
            notetypeId = NotetypeCatalog.BasicId,
            fields = listOf("<img src=\"native.png\">front", "back"),
            tags = listOf("native"),
        )
        val card = SyncCard(42, note.guid, "Study", 0, JsonObject(emptyMap()))
        val options = DeckOptions(newCardsPerDay = 17, desiredRetention = 0.94)
        val preset = DeckOptionsPreset("native-preset", "Native preset", options, 10, 20)
        val unassignedPreset = DeckOptionsPreset(
            "unassigned-preset",
            "Unassigned preset",
            DeckOptions(newCardsPerDay = 23),
            11,
            21,
        )
        val collection = SyncedCollection(
            notes = mapOf(note.guid to note),
            cards = mapOf(card.cardId to card),
            notetypes = NotetypeCatalog.definitions,
            media = mapOf("native.png" to SyncMediaFile("native.png", "now", mediaBytes)),
            deckNames = setOf("Study", "Empty"),
        )
        val service = CollectionInterchangeService(JvmTemporarySqliteFiles())
        val file = service.export(
            collection = collection,
            options = CollectionExportOptions(CollectionExportFormat.KelmaJson, null),
            deckOptions = mapOf("Study" to options, "Empty" to DeckOptions()),
            presets = DeckPresetState(listOf(preset, unassignedPreset), mapOf("Study" to preset.id)),
            schedules = emptyMap(),
            localReviews = listOf(ImmutableReviewExport(reviewedAt, note.guid, 0, Rating.Good, 750)),
            exportedAtMillis = reviewedAt + 1,
        )

        val encoded = Json.decodeFromString<KelmaJsonExport>(file.bytes.decodeToString())
        assertEquals(2, encoded.version)
        assertEquals("collection", encoded.scope)
        assertEquals(setOf("Study", "Empty"), encoded.deckNames.toSet())
        assertEquals(listOf(reviewedAt), encoded.reviews.map(KelmaJsonReview::reviewId))
        assertEquals(setOf("native-preset", "unassigned-preset"), encoded.presets.map(DeckOptionsPreset::id).toSet())
        assertTrue(encoded.cards.single().scheduling.isEmpty())

        val plan = service.previewImport(InterchangeDocument(file.filename, file.bytes))
        assertEquals(setOf("Study", "Empty"), plan.decks)
        assertEquals(Rating.Good, plan.reviews.single().rating)
        assertTrue(plan.media.single().bytes.contentEquals(mediaBytes))
        assertEquals("native-preset", plan.deckOptions.getValue("Study").preferredId)
        assertTrue(plan.presets.any { it.preferredId == "unassigned-preset" })

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val first = store.importCollection(plan, reviewedAt + 2)
        val repeated = store.importCollection(plan, reviewedAt + 3)
        val content = store.loadLocalContent()
        val reviews = store.loadLocalReviews(reviewedAt + 3)

        assertEquals(1, first.addedNotes)
        assertEquals(1, first.addedCards)
        assertEquals(1, first.addedReviews)
        assertEquals(1, first.addedMedia)
        assertEquals(0, repeated.addedNotes)
        assertEquals(0, repeated.addedCards)
        assertEquals(0, repeated.addedReviews)
        assertEquals(0, repeated.addedMedia)
        assertTrue("Empty" in content.deckNames)
        assertNotNull(content.deckPresets.presets.firstOrNull { it.id == "native-preset" })
        assertNotNull(content.deckPresets.presets.firstOrNull { it.id == "unassigned-preset" })
        assertEquals(17, content.deckOptions.getValue("Study").newCardsPerDay)
        assertNotNull(reviews.schedules[localCardId(note.guid, 0)])
        driver.close()
    }

    @Test
    fun legacyVersionOneImportsContentButNeverTrustsDerivedSchedules() {
        val legacy = KelmaDeckExport(
            deckName = "Legacy",
            exportedAtMillis = 100,
            deckOptions = mapOf("Legacy" to DeckOptions(newCardsPerDay = 9)),
            notes = listOf(SyncNote("legacy-guid", NotetypeCatalog.BasicId, listOf("front", "back"))),
            cards = listOf(SyncCard(7, "legacy-guid", "Legacy", 0, JsonObject(emptyMap()))),
            notetypes = NotetypeCatalog.definitions.values.toList(),
            schedules = listOf(LocalCardSchedule(7, ReviewPhase.Review, 200, 3.0, 5.0, 4, 1, 0, 1)),
            media = emptyList(),
        )
        val bytes = Json.encodeToString(legacy).encodeToByteArray()

        val plan = CollectionInterchangeService(JvmTemporarySqliteFiles()).previewImport(
            InterchangeDocument("Legacy.kelma.json", bytes),
        )

        assertEquals("legacy-guid", plan.notes.single().guid)
        assertEquals("Legacy", plan.cards.single().deckName)
        assertTrue(plan.reviews.isEmpty())
        assertTrue(plan.warnings.single().contains("schedules were ignored"))
    }
}
