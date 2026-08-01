package tech.kelma.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CollectionInterchangeService(
    sqliteFiles: TemporarySqliteFiles,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val sqlite = AnkiSqliteCodec(sqliteFiles)
    private val exportMapper = AnkiExportMapper()
    private val importMapper = AnkiImportMapper()

    fun export(
        collection: SyncedCollection,
        options: CollectionExportOptions,
        deckOptions: Map<String, DeckOptions>,
        presets: DeckPresetState,
        schedules: Map<Long, LocalCardSchedule>,
        localReviews: List<ImmutableReviewExport>,
        exportedAtMillis: Long = currentEpochMillis(),
    ): CollectionExportFile {
        val effectiveOptions = if (options.format == CollectionExportFormat.AnkiCollectionPackage) {
            options.copy(deckName = null)
        } else {
            options
        }
        val baseName = effectiveOptions.deckName?.substringAfterLast("::")?.safeInterchangeFilename()
            ?: "collection"
        return when (effectiveOptions.format) {
            CollectionExportFormat.KelmaJson -> {
                val export = effectiveOptions.deckName?.let { deckName ->
                    collection.exportDeck(
                        deckName,
                        deckOptions,
                        schedules,
                        exportedAtMillis,
                        effectiveOptions.includeScheduling,
                        effectiveOptions.includeDeckPresets,
                        effectiveOptions.includeMedia,
                        presets,
                        localReviews,
                    )
                } ?: collection.exportCollection(
                    deckOptions,
                    schedules,
                    exportedAtMillis,
                    effectiveOptions.includeScheduling,
                    effectiveOptions.includeDeckPresets,
                    effectiveOptions.includeMedia,
                    presets,
                    localReviews,
                )
                CollectionExportFile(export.suggestedName, effectiveOptions.format.mimeType, export.content.encodeToByteArray())
            }
            CollectionExportFormat.NotesText -> textFile(
                "$baseName-notes.txt",
                exportNotesText(collection, effectiveOptions.deckName),
            )
            CollectionExportFormat.CardsText -> textFile(
                "$baseName-cards.txt",
                exportCardsText(collection, effectiveOptions.deckName),
            )
            CollectionExportFormat.AnkiDeckPackage,
            CollectionExportFormat.AnkiCollectionPackage,
            -> {
                val snapshot = exportMapper.map(
                    collection,
                    effectiveOptions,
                    deckOptions,
                    presets,
                    schedules,
                    localReviews,
                    exportedAtMillis,
                )
                val database = sqlite.encode(snapshot)
                val media = if (effectiveOptions.includeMedia) {
                    selectExportMedia(collection, effectiveOptions.deckName)
                } else {
                    emptyList()
                }
                val extension = effectiveOptions.format.extension
                CollectionExportFile(
                    filename = "$baseName.$extension",
                    mimeType = effectiveOptions.format.mimeType,
                    bytes = buildPackage(database, media, effectiveOptions.supportOlderAnkiVersions, exportedAtMillis),
                )
            }
        }
    }

    fun previewImport(
        document: InterchangeDocument,
        textKind: TextImportKind? = null,
        targetDeck: String = "Imported",
    ): CollectionImportPlan {
        val extension = document.filename.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "apkg", "colpkg" -> importPackage(document)
            "json" -> importKelmaJson(document, json)
            "txt", "csv", "tsv" -> importAnkiText(
                document,
                textKind ?: detectTextImportKind(document),
                targetDeck,
            )
            else -> if (document.bytes.size >= 2 && document.bytes[0] == 'P'.code.toByte() &&
                document.bytes[1] == 'K'.code.toByte()) {
                importPackage(document)
            } else if (document.bytes.looksLikeJsonObject()) {
                importKelmaJson(document, json)
            } else {
                importAnkiText(document, textKind ?: TextImportKind.Notes, targetDeck)
            }
        }
    }

    fun detectTextKind(document: InterchangeDocument): TextImportKind = detectTextImportKind(document)

    private fun importPackage(document: InterchangeDocument): CollectionImportPlan =
        AnkiPackageArchive.open(document.bytes).use { archive ->
            val databaseBytes = when {
                archive.contains("collection.anki21b") -> zstdDecompress(
                    archive.read("collection.anki21b"),
                    MaxInterchangeFileBytes,
                )
                archive.contains("collection.anki21") -> archive.read("collection.anki21")
                archive.contains("collection.anki2") -> archive.read("collection.anki2")
                else -> error("The package does not contain an Anki collection")
            }
            val mediaResult = readPackageMedia(archive)
            val plan = importMapper.map(document.filename, sqlite.decode(databaseBytes), mediaResult.media)
            plan.copy(warnings = (plan.warnings + mediaResult.warnings).distinct())
        }

    private fun buildPackage(
        database: ByteArray,
        media: List<SyncMediaFile>,
        legacy: Boolean,
        exportedAtMillis: Long,
    ): ByteArray {
        val stub = sqlite.encode(buildUpgradeStub(exportedAtMillis))
        val entries = mutableListOf<StoredZipEntry>()
        entries += StoredZipEntry("meta", encodePackageMetadata(if (legacy) 2 else 3))
        entries += if (legacy) {
            StoredZipEntry("collection.anki21", database)
        } else {
            StoredZipEntry("collection.anki21b", zstdCompress(database))
        }
        entries += StoredZipEntry("collection.anki2", stub)
        val manifest = media.mapIndexed { index, file ->
            AnkiMediaManifestEntry(file.filename, file.bytes.size, sha1(file.bytes), index)
        }
        entries += if (legacy) {
            val map = JsonObject(
                manifest.mapIndexed { index, entry -> index.toString() to JsonPrimitive(entry.filename) }.toMap(),
            )
            StoredZipEntry("media", map.toString().encodeToByteArray())
        } else {
            StoredZipEntry("media", zstdCompress(encodeMediaManifest(manifest)))
        }
        media.forEachIndexed { index, file ->
            entries += StoredZipEntry(index.toString(), if (legacy) file.bytes else zstdCompress(file.bytes))
        }
        return writeStoredZip(entries)
    }

    private fun buildUpgradeStub(exportedAtMillis: Long): AnkiDatabaseSnapshot {
        val note = SyncNote(
            guid = "kelma-upgrade-${exportedAtMillis.toString(36)}",
            notetypeId = NotetypeCatalog.BasicId,
            fields = listOf("Please update Anki, then import this package again.", ""),
        )
        val card = SyncCard(1, note.guid, "Default", 0)
        return exportMapper.map(
            collection = SyncedCollection(
                notes = mapOf(note.guid to note),
                cards = mapOf(card.cardId to card),
                notetypes = NotetypeCatalog.definitions,
                deckNames = setOf("Default"),
            ),
            options = CollectionExportOptions(
                format = CollectionExportFormat.AnkiDeckPackage,
                deckName = null,
                includeScheduling = false,
                includeDeckPresets = false,
                includeMedia = false,
            ),
            deckOptions = emptyMap(),
            presets = DeckPresetState(),
            schedules = emptyMap(),
            localReviews = emptyList(),
            exportedAtMillis = exportedAtMillis,
        )
    }

    private fun readPackageMedia(archive: AnkiPackageArchive): PackageMediaResult {
        if (!archive.contains("media")) return PackageMediaResult(emptyList(), emptyList())
        val encoded = archive.read("media", 64 * 1024 * 1024)
        val decoded = if (encoded.isZstd()) zstdDecompress(encoded, 64 * 1024 * 1024) else encoded
        val entries = if (decoded.looksLikeJsonObject()) {
            val source = json.parseToJsonElement(decoded.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                ?: error("The Anki media map is invalid")
            source.mapNotNull { (index, value) ->
                index.toIntOrNull()?.let {
                    AnkiMediaManifestEntry((value as JsonPrimitive).content, 0, ByteArray(0), it)
                }
            }.sortedBy(AnkiMediaManifestEntry::archiveIndex)
        } else {
            decodeMediaManifest(decoded)
        }
        val warnings = mutableListOf<String>()
        val media = mutableListOf<ImportedMedia>()
        var totalBytes = 0L
        val usedNames = mutableSetOf<String>()
        entries.forEach { entry ->
            if (entry.filename.isBlank() || !archive.contains(entry.archiveIndex.toString())) {
                warnings += "A package media entry was missing and was skipped."
                return@forEach
            }
            val filename = normalizeMediaFilename(entry.filename)
            if (!usedNames.add(filename)) {
                warnings += "Duplicate package media name $filename was skipped."
                return@forEach
            }
            val raw = archive.read(entry.archiveIndex.toString(), MaxAttachmentBytes + 1024 * 1024)
            val bytes = if (raw.isZstd()) zstdDecompress(raw, MaxAttachmentBytes) else raw
            require(entry.size == 0 || entry.size == bytes.size) { "Media size check failed for $filename" }
            require(entry.sha1.isEmpty() || entry.sha1.contentEquals(sha1(bytes))) {
                "Media checksum check failed for $filename"
            }
            totalBytes += bytes.size
            require(totalBytes <= MaxInterchangeFileBytes) { "Package media exceeds 512 MiB" }
            media += ImportedMedia(filename, bytes)
        }
        return PackageMediaResult(media, warnings.distinct())
    }

    private fun selectExportMedia(collection: SyncedCollection, deckName: String?): List<SyncMediaFile> {
        if (deckName == null) return collection.media.values.sortedBy(SyncMediaFile::filename)
        val selectedCards = collection.cards.values.filter { it.deckName.isDeckOrDescendantOf(deckName) }
        val noteGuids = selectedCards.mapTo(mutableSetOf(), SyncCard::noteGuid)
        val selectedNotes = collection.notes.values.filter { it.guid in noteGuids }
        val notetypeIds = selectedNotes.mapTo(mutableSetOf(), SyncNote::notetypeId)
        val sources = selectedNotes.flatMap(SyncNote::fields) + collection.notetypes.values
            .filter { it.notetypeId in notetypeIds }.map { it.definition.toString() }
        return collection.media.values.filter { file -> sources.any { file.filename in it } }
            .sortedBy(SyncMediaFile::filename)
    }

    private fun textFile(filename: String, content: String): CollectionExportFile = CollectionExportFile(
        filename,
        "text/tab-separated-values",
        content.encodeToByteArray(),
    )
}

private data class PackageMediaResult(val media: List<ImportedMedia>, val warnings: List<String>)

private fun ByteArray.looksLikeJsonObject(): Boolean = firstOrNull { byte ->
    byte.toInt().toChar() !in setOf(' ', '\t', '\r', '\n')
} == '{'.code.toByte()

private fun String.safeInterchangeFilename(): String {
    val safe = map { character -> if (character.isLetterOrDigit() || character in " ._-") character else '_' }
        .joinToString("").trim().trim('.')
    return safe.ifBlank { "collection" }
}
