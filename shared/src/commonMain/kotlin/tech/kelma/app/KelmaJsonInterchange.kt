package tech.kelma.app

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun importKelmaJson(document: InterchangeDocument, json: Json): CollectionImportPlan {
    val text = document.bytes.decodeToString(throwOnInvalidSequence = true)
    val root = runCatching { json.parseToJsonElement(text) as? JsonObject }
        .getOrElse { throw IllegalArgumentException("The Kelma JSON file is invalid", it) }
        ?: error("The Kelma JSON file must contain an object")
    val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
    return when (version) {
        1 -> mapLegacyKelmaJson(document.filename, json.decodeFromString<KelmaDeckExport>(text), json)
        2 -> mapKelmaJsonV2(document.filename, json.decodeFromString<KelmaJsonExport>(text), json)
        else -> error("Kelma JSON version $version is not supported")
    }
}

private fun mapKelmaJsonV2(sourceName: String, export: KelmaJsonExport, json: Json): CollectionImportPlan {
    require(export.format == "kelma-interchange") { "This is not a Kelma interchange file" }
    require(export.scope == "deck" || export.scope == "collection") { "The Kelma JSON scope is invalid" }
    require(export.presets.map(DeckOptionsPreset::id).distinct().size == export.presets.size) {
        "Kelma JSON contains duplicate preset IDs"
    }
    val warnings = mutableListOf<String>()
    val content = mapKelmaContent(
        deckNames = export.deckNames,
        notes = export.notes,
        cards = export.cards,
        notetypes = export.notetypes,
        media = export.media,
        warnings = warnings,
    )
    val noteGuids = content.notes.associate { it.sourceId to it.guid }
    val cardIds = content.cards.associate { card ->
        kelmaCardIdentity(noteGuids.getValue(card.noteSourceId), card.ordinal) to card.sourceId
    }
    val reviews = export.reviews.mapNotNull { review ->
        val rating = Rating.entries.firstOrNull { it.name == review.rating }
        val cardId = cardIds[kelmaCardIdentity(review.noteGuid, review.cardOrdinal)]
        if (review.reviewId <= 0 || rating == null || cardId == null) {
            warnings += "An invalid Kelma review was skipped."
            null
        } else {
            ImportedReview(review.reviewId, cardId, rating, review.durationMillis.coerceAtLeast(0))
        }
    }
    val mappedOptions = mapKelmaDeckOptions(
        export.deckOptions,
        export.presets,
        export.presetAssignments,
        json,
    )
    return CollectionImportPlan(
        sourceName = sourceName,
        decks = content.decks,
        notetypes = content.notetypes,
        notes = content.notes,
        cards = content.cards,
        reviews = reviews,
        media = content.media,
        deckOptions = mappedOptions.assignments,
        presets = mappedOptions.presets,
        warnings = warnings.distinct(),
    )
}

private fun mapLegacyKelmaJson(sourceName: String, export: KelmaDeckExport, json: Json): CollectionImportPlan {
    require(export.format == "kelma-deck" || export.format == "kelma-collection") {
        "This is not a legacy Kelma export"
    }
    val warnings = mutableListOf<String>()
    if (export.schedules.isNotEmpty()) {
        warnings += "Legacy derived schedules were ignored because immutable review history was unavailable."
    }
    val explicitDecks = buildList {
        addAll(export.deckOptions.keys)
        addAll(export.cards.map(SyncCard::deckName))
        if (export.format == "kelma-deck") add(export.deckName)
    }
    val content = mapKelmaContent(
        deckNames = explicitDecks,
        notes = export.notes,
        cards = export.cards,
        notetypes = export.notetypes,
        media = export.media,
        warnings = warnings,
    )
    val mappedOptions = mapKelmaDeckOptions(export.deckOptions, emptyList(), emptyMap(), json)
    return CollectionImportPlan(
        sourceName = sourceName,
        decks = content.decks,
        notetypes = content.notetypes,
        notes = content.notes,
        cards = content.cards,
        reviews = emptyList(),
        media = content.media,
        deckOptions = mappedOptions.assignments,
        presets = mappedOptions.presets,
        warnings = warnings.distinct(),
    )
}

private data class MappedKelmaContent(
    val decks: Set<String>,
    val notetypes: List<ImportedNotetype>,
    val notes: List<ImportedNote>,
    val cards: List<ImportedCard>,
    val media: List<ImportedMedia>,
)

private fun mapKelmaContent(
    deckNames: Collection<String>,
    notes: List<SyncNote>,
    cards: List<SyncCard>,
    notetypes: List<SyncNotetype>,
    media: List<KelmaExportMedia>,
    warnings: MutableList<String>,
): MappedKelmaContent {
    require(notes.map(SyncNote::guid).distinct().size == notes.size) { "Kelma JSON contains duplicate note GUIDs" }
    require(cards.map(SyncCard::cardId).distinct().size == cards.size) { "Kelma JSON contains duplicate card IDs" }
    require(cards.map { kelmaCardIdentity(it.noteGuid, it.ord) }.distinct().size == cards.size) {
        "Kelma JSON contains duplicate card identities"
    }
    require(notetypes.map(SyncNotetype::notetypeId).distinct().size == notetypes.size) {
        "Kelma JSON contains duplicate note-type IDs"
    }
    require(media.map { normalizeMediaFilename(it.filename) }.distinct().size == media.size) {
        "Kelma JSON contains duplicate media names"
    }
    val noteIds = notes.mapIndexed { index, note -> note.guid to index.toLong() + 1 }.toMap()
    val importedNotes = notes.map { note ->
        ImportedNote(
            sourceId = noteIds.getValue(note.guid),
            guid = note.guid,
            notetypeId = note.notetypeId,
            fields = note.fields,
            tags = note.tags,
        )
    }
    val importedCards = cards.mapNotNull { card ->
        val noteId = noteIds[card.noteGuid]
        if (noteId == null) {
            warnings += "A Kelma card whose note was missing was skipped."
            null
        } else {
            ImportedCard(card.cardId, noteId, normalizeDeckName(card.deckName), card.ord)
        }
    }
    val importedMedia = media.mapNotNull { entry ->
        val bytes = decodeKelmaBase64(entry.base64)
        if (bytes.isEmpty()) {
            warnings += "Empty media ${entry.filename} was skipped."
            null
        } else {
            ImportedMedia(entry.filename, bytes)
        }
    }
    require(importedMedia.sumOf { it.bytes.size.toLong() } <= MaxInterchangeFileBytes) {
        "Kelma JSON media exceeds 512 MiB"
    }
    val decks = (deckNames + importedCards.map(ImportedCard::deckName))
        .flatMap { deckHierarchyNames(normalizeDeckName(it)) }
        .toSet()
    return MappedKelmaContent(
        decks = decks,
        notetypes = notetypes.map { ImportedNotetype(it.notetypeId, it.name, it.definition.toString()) },
        notes = importedNotes,
        cards = importedCards,
        media = importedMedia,
    )
}

private data class MappedKelmaOptions(
    val assignments: Map<String, ImportedDeckOptions>,
    val presets: List<ImportedDeckOptions>,
)

private fun mapKelmaDeckOptions(
    optionsByDeck: Map<String, DeckOptions>,
    presets: List<DeckOptionsPreset>,
    assignments: Map<String, String>,
    json: Json,
): MappedKelmaOptions {
    val importedPresets = presets.map { preset ->
        ImportedDeckOptions(
            sourceId = stablePositiveId("kelma-preset:${preset.id}"),
            name = preset.name,
            options = preset.options.validated(),
            preferredId = preset.id,
        )
    }
    val presetsById = importedPresets.associateBy(ImportedDeckOptions::preferredId)
    val mappedAssignments = buildMap {
        assignments.forEach { (deck, presetId) ->
            presetsById[presetId]?.let { put(normalizeDeckName(deck), it) }
        }
        optionsByDeck.forEach { (deck, options) ->
            val normalizedDeck = normalizeDeckName(deck)
            if (keys.none { it.equals(normalizedDeck, ignoreCase = true) }) {
                val sourceId = stablePositiveId("kelma-options:$normalizedDeck:${json.encodeToString(options)}")
                put(
                    normalizedDeck,
                    ImportedDeckOptions(
                        sourceId = sourceId,
                        name = "${normalizedDeck.substringAfterLast("::")} Options",
                        options = options.validated(),
                        preferredId = "kelma-options-${sourceId.toString(36)}",
                    ),
                )
            }
        }
    }
    return MappedKelmaOptions(mappedAssignments, importedPresets)
}

private fun decodeKelmaBase64(input: String): ByteArray {
    if (input.isEmpty()) return ByteArray(0)
    require(input.length % 4 == 0) { "Kelma media contains invalid Base64" }
    val padding = when {
        input.endsWith("==") -> 2
        input.endsWith('=') -> 1
        else -> 0
    }
    val outputSize = input.length / 4 * 3 - padding
    require(outputSize <= MaxAttachmentBytes) { "Kelma media exceeds 100 MiB" }
    val output = ByteArray(outputSize)
    var outputIndex = 0
    for (index in input.indices step 4) {
        val lastGroup = index + 4 == input.length
        val first = base64Value(input[index])
        val second = base64Value(input[index + 1])
        val third = if (input[index + 2] == '=') -1 else base64Value(input[index + 2])
        val fourth = if (input[index + 3] == '=') -1 else base64Value(input[index + 3])
        require(first >= 0 && second >= 0) { "Kelma media contains invalid Base64" }
        require(third >= 0 || lastGroup && input[index + 2] == '=' && input[index + 3] == '=') {
            "Kelma media contains invalid Base64 padding"
        }
        require(fourth >= 0 || lastGroup && input[index + 3] == '=') {
            "Kelma media contains invalid Base64 padding"
        }
        if (outputIndex < output.size) output[outputIndex++] = ((first shl 2) or (second ushr 4)).toByte()
        if (outputIndex < output.size) output[outputIndex++] = ((second shl 4) or (third ushr 2)).toByte()
        if (outputIndex < output.size) output[outputIndex++] = ((third shl 6) or fourth).toByte()
    }
    return output
}

private fun base64Value(character: Char): Int = when (character) {
    in 'A'..'Z' -> character.code - 'A'.code
    in 'a'..'z' -> character.code - 'a'.code + 26
    in '0'..'9' -> character.code - '0'.code + 52
    '+' -> 62
    '/' -> 63
    else -> -1
}

private fun kelmaCardIdentity(guid: String, ordinal: Int): String = "$guid\u0000$ordinal"
