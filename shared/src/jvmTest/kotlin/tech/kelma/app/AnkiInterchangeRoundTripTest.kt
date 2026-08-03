package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import tech.kelma.db.KelmaDatabase

class AnkiInterchangeRoundTripTest {
    @Test
    fun modernAndLegacyPackagesRoundTripContentAndHistory() {
        val service = CollectionInterchangeService(JvmTemporarySqliteFiles())
        val mediaBytes = "fixture-image".encodeToByteArray()
        val base = sampleCollection()
        val collection = base.copy(
            notes = mapOf(
                "note-1" to base.notes.getValue("note-1").copy(
                    fields = listOf("<img src=\"picture.png\">front", "back"),
                ),
            ),
            media = mapOf("picture.png" to SyncMediaFile("picture.png", "", mediaBytes)),
        )
        val reviewedAt = 1_735_689_600_000L
        val customOptions = DeckOptions(
            newCardsPerDay = 37,
            desiredRetention = 0.93,
            autoplayAudio = false,
            newCardGatherOrder = NewCardGatherOrder.DeckThenRandomNotes,
            reviewSortOrder = ReviewSortOrder.RetrievabilityDescending,
            buryNewSiblings = false,
            buryReviewSiblings = false,
            buryInterdayLearningSiblings = true,
        )
        val schedule = FsrsScheduler.review(
            card = collection.cards.getValue(100),
            previous = null,
            rating = Rating.Good,
            reviewedAtMillis = reviewedAt,
            options = DeckOptions(),
        )

        listOf(false, true).forEach { legacy ->
            val exported = service.export(
                collection = collection,
                options = CollectionExportOptions(
                    format = CollectionExportFormat.AnkiDeckPackage,
                    deckName = "Languages",
                    includeScheduling = true,
                    includeDeckPresets = true,
                    includeMedia = true,
                    supportOlderAnkiVersions = legacy,
                ),
                deckOptions = mapOf("Languages::Arabic" to customOptions),
                presets = DeckPresetState(),
                schedules = mapOf(100L to schedule),
                localReviews = listOf(ImmutableReviewExport(reviewedAt, "note-1", 0, Rating.Good, 2_500)),
                exportedAtMillis = reviewedAt + 10_000,
            )

            assertEquals("Languages.apkg", exported.filename)
            if (!legacy) {
                System.getenv("KELMA_ANKI_EXPORT_FIXTURE")?.let { path -> java.io.File(path).writeBytes(exported.bytes) }
            }
            val imported = service.previewImport(InterchangeDocument(exported.filename, exported.bytes))
            assertEquals(listOf("<img src=\"picture.png\">front", "back"), imported.notes.single().fields)
            assertEquals("Languages::Arabic", imported.cards.single().deckName)
            assertEquals(1_577_934_245_000L, imported.cards.single().createdAtMillis)
            assertEquals(Rating.Good, imported.reviews.single().rating)
            assertTrue(imported.notetypes.single().definitionJson.contains("{{Front}}"))
            assertTrue(imported.media.single().bytes.contentEquals(mediaBytes))
            val importedOptions = imported.deckOptions.getValue("Languages::Arabic").options
            assertEquals(37, importedOptions.newCardsPerDay)
            assertEquals(0.93, importedOptions.desiredRetention)
            assertEquals(false, importedOptions.autoplayAudio)
            assertEquals(NewCardGatherOrder.DeckThenRandomNotes, importedOptions.newCardGatherOrder)
            assertEquals(ReviewSortOrder.RetrievabilityDescending, importedOptions.reviewSortOrder)
            assertEquals(false, importedOptions.buryNewSiblings)
            assertEquals(false, importedOptions.buryReviewSiblings)
            assertEquals(true, importedOptions.buryInterdayLearningSiblings)
        }
    }

    @Test
    fun collectionPackageAlwaysIncludesAllDecksAndCanOmitHistoryAndMedia() {
        val base = sampleCollection()
        val secondNote = SyncNote("note-2", NotetypeCatalog.BasicId, listOf("other", "answer"))
        val secondCard = SyncCard(200, secondNote.guid, "Other", 0, JsonObject(emptyMap()))
        val collection = base.copy(
            notes = base.notes + (secondNote.guid to secondNote),
            cards = base.cards + (secondCard.cardId to secondCard),
            deckNames = base.deckNames + "Other",
            media = mapOf("unused.png" to SyncMediaFile("unused.png", "", byteArrayOf(1, 2, 3))),
        )
        val exported = CollectionInterchangeService(JvmTemporarySqliteFiles()).export(
            collection,
            CollectionExportOptions(
                format = CollectionExportFormat.AnkiCollectionPackage,
                deckName = "Languages",
                includeScheduling = false,
                includeDeckPresets = false,
                includeMedia = false,
            ),
            emptyMap(),
            DeckPresetState(),
            emptyMap(),
            listOf(ImmutableReviewExport(1_735_689_600_000L, "note-1", 0, Rating.Good, 500)),
            1_735_689_610_000L,
        )

        assertEquals("collection.colpkg", exported.filename)
        val imported = CollectionInterchangeService(JvmTemporarySqliteFiles()).previewImport(
            InterchangeDocument(exported.filename, exported.bytes),
        )
        assertEquals(setOf("Languages::Arabic", "Other"), imported.cards.map(ImportedCard::deckName).toSet())
        assertTrue(imported.reviews.isEmpty())
        assertTrue(imported.media.isEmpty())
    }

    @Test
    fun defaultDeckUsesTheReservedAnkiDeckIdWithoutDuplicateMetadata() {
        val note = SyncNote("default-guid", NotetypeCatalog.BasicId, listOf("front", "back"))
        val snapshot = AnkiExportMapper().map(
            SyncedCollection(
                notes = mapOf(note.guid to note),
                cards = mapOf(1L to SyncCard(1, note.guid, "Default", 0, JsonObject(emptyMap()))),
                notetypes = NotetypeCatalog.definitions,
                deckNames = setOf("Default"),
            ),
            CollectionExportOptions(CollectionExportFormat.AnkiDeckPackage, "Default"),
            mapOf("Default" to DeckOptions(newCardsPerDay = 8)),
            DeckPresetState(),
            emptyMap(),
            emptyList(),
            1_735_689_600_000L,
        )

        assertEquals(1L, snapshot.cards.single().deckId)
        assertEquals(1, Json.parseToJsonElement(snapshot.collection.decksJson).jsonObject.size)
    }

    @Test
    fun exportedTextIsSelfDescribingAndCardsImportLossily() {
        val service = CollectionInterchangeService(JvmTemporarySqliteFiles())
        val collection = sampleCollection()
        val notes = service.export(
            collection,
            CollectionExportOptions(CollectionExportFormat.NotesText, "Languages"),
            emptyMap(),
            DeckPresetState(),
            emptyMap(),
            emptyList(),
            1_735_689_600_000L,
        )
        val notePlan = service.previewImport(InterchangeDocument(notes.filename, notes.bytes), TextImportKind.Notes, "Ignored")
        assertEquals("note-1", notePlan.notes.single().guid)
        assertEquals("Languages::Arabic", notePlan.cards.single().deckName)

        val cards = service.export(
            collection,
            CollectionExportOptions(CollectionExportFormat.CardsText, "Languages"),
            emptyMap(),
            DeckPresetState(),
            emptyMap(),
            emptyList(),
            1_735_689_600_000L,
        )
        val cardPlan = service.previewImport(InterchangeDocument(cards.filename, cards.bytes), TextImportKind.Cards, "Imported")
        assertEquals(NotetypeCatalog.BasicId, cardPlan.notes.single().notetypeId)
        assertTrue(cardPlan.warnings.single().contains("template structure"))
        assertEquals(listOf("front", "back"), cardPlan.notes.single().fields)
    }

    @Test
    fun textImportSupportsAnkiHeadersCrLfAndQuotedFields() {
        val text = "#separator:semicolon\r\n#html:true\r\n#deck column:3\r\n" +
            "\"front;value\";\"answer\r\nline two\";Target::Child\r\n"
        val plan = CollectionInterchangeService(JvmTemporarySqliteFiles()).previewImport(
            InterchangeDocument("notes.txt", text.encodeToByteArray()),
            TextImportKind.Notes,
            "Fallback",
        )
        assertEquals(listOf("front;value", "answer\r\nline two"), plan.notes.single().fields)
        assertEquals("Target::Child", plan.cards.single().deckName)
    }

    @Test
    fun plainTextImportEscapesMarkupAndPreservesLineBreaks() {
        val text = "#separator:tab\n#html:false\n\"2 < 3 & 4\nnext\"\tanswer\n"
        val plan = CollectionInterchangeService(JvmTemporarySqliteFiles()).previewImport(
            InterchangeDocument("plain.txt", text.encodeToByteArray()),
            TextImportKind.Notes,
            "Imported",
        )

        assertEquals(listOf("2 &lt; 3 &amp; 4<br>next", "answer"), plan.notes.single().fields)
    }

    @Test
    fun collectionImportIsIdempotentAndRebuildsFromImmutableReviews() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val reviewedAt = 1_735_689_600_000L
        val plan = CollectionImportPlan(
            sourceName = "fixture.apkg",
            decks = setOf("Imported"),
            notetypes = listOf(
                ImportedNotetype(
                    42,
                    "Custom",
                    """{"flds":[{"name":"Front"},{"name":"Back"}],"tmpls":[{"name":"Card 1","ord":0,"qfmt":"{{Front}}","afmt":"{{FrontSide}}<hr id=answer>{{Back}}"}]}""",
                ),
            ),
            notes = listOf(ImportedNote(1, "guid-1", 42, listOf("question", "answer"), listOf("tag"))),
            cards = listOf(ImportedCard(10, 1, "Imported", 0)),
            reviews = listOf(ImportedReview(reviewedAt, 10, Rating.Good, 1_000)),
            media = emptyList(),
        )

        val first = store.importCollection(plan, reviewedAt + 1)
        val second = store.importCollection(plan, reviewedAt + 2)
        val conflicting = store.importCollection(
            plan.copy(
                notes = listOf(ImportedNote(1, "guid-1", 42, listOf("changed", "answer"), listOf("tag"))),
                reviews = listOf(ImportedReview(reviewedAt, 10, Rating.Again, 1_000)),
            ),
            reviewedAt + 3,
        )
        val content = store.loadLocalContent()
        val reviews = store.loadLocalReviews(reviewedAt + 3)

        assertEquals(1, first.addedNotes)
        assertEquals(1, first.addedCards)
        assertEquals(1, first.addedReviews)
        assertEquals(0, second.addedNotes)
        assertEquals(0, second.addedCards)
        assertEquals(0, second.addedReviews)
        assertEquals(1, conflicting.copiedConflicts)
        assertEquals(1, conflicting.skippedReviewConflicts)
        assertNotNull(content.notetypes[42])
        assertEquals(2, content.notes.size)
        assertEquals(1, reviews.schedules.size)
        driver.close()
    }

    @Test
    fun importPreservesExistingLocalDeckOptionsWhileRetainingThePackagePreset() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        store.saveDeckOptions("Imported", DeckOptions(newCardsPerDay = 7), 1L)
        val report = store.importCollection(
            CollectionImportPlan(
                sourceName = "preset.apkg",
                decks = setOf("Imported"),
                notetypes = emptyList(),
                notes = emptyList(),
                cards = emptyList(),
                reviews = emptyList(),
                media = emptyList(),
                deckOptions = mapOf(
                    "Imported" to ImportedDeckOptions(55, "Package preset", DeckOptions(newCardsPerDay = 99)),
                ),
            ),
            2L,
        )

        val content = store.loadLocalContent()
        assertEquals(7, content.deckOptions.getValue("Imported").newCardsPerDay)
        assertTrue(content.deckPresets.presets.any { it.name == "Package preset" && it.options.newCardsPerDay == 99 })
        assertTrue(report.warnings.single().contains("preserved"))
        driver.close()
    }

    @Test
    fun mediaCollisionRenamesOnlyTheReferencedFilenameToken() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        store.saveMediaAttachment("a.png", "image/png", byteArrayOf(1), 1L)
        val plan = CollectionImportPlan(
            sourceName = "media.apkg",
            decks = setOf("Imported"),
            notetypes = emptyList(),
            notes = listOf(
                ImportedNote(
                    1,
                    "media-guid",
                    NotetypeCatalog.BasicId,
                    listOf("<img src=\"a.png\"> data.png", "back"),
                    emptyList(),
                ),
            ),
            cards = listOf(ImportedCard(2, 1, "Imported", 0)),
            reviews = emptyList(),
            media = listOf(ImportedMedia("a.png", byteArrayOf(2))),
        )

        val report = store.importCollection(plan, 2L)
        val repeated = store.importCollection(plan, 3L)
        val importedField = store.loadLocalContent().notes.getValue("media-guid").fields.first()
        assertEquals(1, report.addedMedia)
        assertEquals(1, report.renamedMedia)
        assertEquals(0, repeated.addedMedia)
        assertTrue(importedField.matches(Regex("<img src=\\\"a-[0-9a-f]{8}\\.png\\\"> data\\.png")))
        driver.close()
    }

    @Test
    fun importsOptionalAnkiGeneratedOracle() {
        val path = System.getenv("KELMA_ANKI_IMPORT_FIXTURE") ?: return
        val file = java.io.File(path)
        val plan = CollectionInterchangeService(JvmTemporarySqliteFiles()).previewImport(
            InterchangeDocument(file.name, file.readBytes()),
        )
        assertEquals(listOf("<b>front</b>", "back"), plan.notes.single().fields)
        assertEquals("Interop::Child", plan.cards.single().deckName)
        assertEquals("Basic", plan.notetypes.single().name)
    }

    @Test
    fun normalizedSchemaDeckPresetMapsCurrentAnkiEnumAndFsrsFields() {
        val params = DefaultFsrs6Parameters.map(Double::toFloat)
        val config = concatenateBytes(
            packedFloatField(1, listOf(0.5f, 10f)),
            packedFloatField(2, listOf(5f)),
            packedFloatField(6, params),
            varintField(9, 41),
            varintField(10, 321),
            varintField(16, 12_345),
            varintField(23, 1),
            varintField(24, 90),
            varintField(27, 0),
            varintField(28, 1),
            varintField(29, 1),
            varintField(34, 5),
            varintField(32, 4),
            varintField(30, 2),
            varintField(31, 1),
            varintField(33, 11),
            fixedFloatField(37, 0.95f),
        )
        val snapshot = AnkiDatabaseSnapshot(
            collection = AnkiCollectionRow(1, 0, 0, 0, modelsJson = "{}", decksJson = "{}", configurationJson = "{}", deckConfigurationsJson = "{}"),
            notes = listOf(AnkiNoteRow(1, "modern-guid", 50, 0, tags = "", fields = "front\u001fback", sortField = "front", checksum = 0)),
            cards = listOf(AnkiCardRow(2, 1, 60, 0, 0, type = 0, queue = 0, due = 1, interval = 0, factor = 0, repetitions = 0, lapses = 0, remainingSteps = 0)),
            reviews = emptyList(),
            normalizedNotetypes = listOf(AnkiNormalizedNotetype(50, "Modern", 0, 0, bytesField(3, ".card {}".encodeToByteArray()))),
            normalizedFields = listOf(AnkiNormalizedField(50, 0, "Front", byteArrayOf()), AnkiNormalizedField(50, 1, "Back", byteArrayOf())),
            normalizedTemplates = listOf(
                AnkiNormalizedTemplate(
                    50,
                    0,
                    "Card 1",
                    0,
                    0,
                    concatenateBytes(
                        bytesField(1, "{{Front}}".encodeToByteArray()),
                        bytesField(2, "{{FrontSide}}<hr id=answer>{{Back}}".encodeToByteArray()),
                    ),
                ),
            ),
            normalizedDecks = listOf(AnkiNormalizedDeck(60, "Modern\u001fDeck", 0, 0, varintField(2, 70), byteArrayOf())),
            normalizedDeckConfigs = listOf(AnkiNormalizedDeckConfig(70, "Modern preset", 0, 0, config)),
        )

        val plan = AnkiImportMapper().map("modern.colpkg", snapshot, emptyList())
        val options = plan.deckOptions.getValue("Modern::Deck").options
        assertEquals(41, options.newCardsPerDay)
        assertEquals(321, options.maximumReviewsPerDay)
        assertContentEquals(listOf(30, 600), options.fsrsLearningStepsSeconds)
        assertContentEquals(listOf(300), options.fsrsRelearningStepsSeconds)
        assertEquals(false, options.autoplayAudio)
        assertEquals(12_345, options.maximumIntervalDays)
        assertEquals(NewCardGatherOrder.DeckThenRandomNotes, options.newCardGatherOrder)
        assertEquals(NewCardSortOrder.RandomCard, options.newCardSortOrder)
        assertEquals(QueueMixOrder.BeforeReviews, options.newReviewMixOrder)
        assertEquals(QueueMixOrder.AfterReviews, options.interdayLearningMixOrder)
        assertEquals(ReviewSortOrder.RetrievabilityDescending, options.reviewSortOrder)
        assertEquals(false, options.buryNewSiblings)
        assertEquals(true, options.buryReviewSiblings)
        assertEquals(true, options.buryInterdayLearningSiblings)
        assertEquals(SchedulerAlgorithm.Fsrs6, options.effectiveSchedulerAlgorithm)
        assertEquals(21, options.fsrsParameters.size)
        assertTrue(kotlin.math.abs(options.desiredRetention - 0.95) < 0.000001)
    }

    @Test
    fun wirePrimitivesMatchKnownVectors() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1("abc".encodeToByteArray()).hexString())
        assertTrue(encodePackageMetadata(3).contentEquals(byteArrayOf(0x08, 0x03)))
        val manifest = listOf(AnkiMediaManifestEntry("image.jpg", 12, ByteArray(20) { it.toByte() }, 0))
        val decoded = decodeMediaManifest(encodeMediaManifest(manifest)).single()
        assertEquals("image.jpg", decoded.filename)
        assertEquals(12, decoded.size)
        assertTrue(decoded.sha1.contentEquals(manifest.single().sha1))
    }

    private fun sampleCollection(): SyncedCollection {
        val note = SyncNote("note-1", NotetypeCatalog.BasicId, listOf("front", "back"), listOf("tag"))
        val card = SyncCard(
            100,
            note.guid,
            "Languages::Arabic",
            0,
            JsonObject(emptyMap()),
            createdAt = "2020-01-02T03:04:05.000Z",
        )
        return SyncedCollection(
            notes = mapOf(note.guid to note),
            cards = mapOf(card.cardId to card),
            notetypes = NotetypeCatalog.definitions,
            deckNames = setOf("Languages", "Languages::Arabic"),
        )
    }
}

private fun varintField(number: Int, value: Long): ByteArray =
    concatenateBytes(encodeTestVarint((number.toLong() shl 3)), encodeTestVarint(value))

private fun bytesField(number: Int, value: ByteArray): ByteArray = concatenateBytes(
    encodeTestVarint((number.toLong() shl 3) or 2L),
    encodeTestVarint(value.size.toLong()),
    value,
)

private fun packedFloatField(number: Int, values: List<Float>): ByteArray = bytesField(
    number,
    concatenateBytes(*values.map { value ->
        val bits = value.toBits()
        byteArrayOf(bits.toByte(), (bits ushr 8).toByte(), (bits ushr 16).toByte(), (bits ushr 24).toByte())
    }.toTypedArray()),
)

private fun fixedFloatField(number: Int, value: Float): ByteArray {
    val bits = value.toBits()
    return concatenateBytes(
        encodeTestVarint((number.toLong() shl 3) or 5L),
        byteArrayOf(bits.toByte(), (bits ushr 8).toByte(), (bits ushr 16).toByte(), (bits ushr 24).toByte()),
    )
}

private fun encodeTestVarint(input: Long): ByteArray {
    var value = input
    val output = mutableListOf<Byte>()
    while (true) {
        if (value and -128L == 0L) {
            output += value.toByte()
            return output.toByteArray()
        }
        output += ((value.toInt() and 0x7f) or 0x80).toByte()
        value = value ushr 7
    }
}

private fun concatenateBytes(vararg values: ByteArray): ByteArray {
    val size = values.sumOf(ByteArray::size)
    val output = ByteArray(size)
    var offset = 0
    values.forEach { value ->
        value.copyInto(output, offset)
        offset += value.size
    }
    return output
}
