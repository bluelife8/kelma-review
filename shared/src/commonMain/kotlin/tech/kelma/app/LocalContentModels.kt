package tech.kelma.app

data class LocalNoteOverride(
    val fields: List<String>,
    val tags: List<String>,
)

data class PendingDeckChanges(
    val addedCardIds: Set<Long> = emptySet(),
    val changedCardIds: Set<Long> = emptySet(),
) {
    val added: Int get() = addedCardIds.size
    val changed: Int get() = changedCardIds.size

    fun mergedWith(other: PendingDeckChanges): PendingDeckChanges = PendingDeckChanges(
        addedCardIds = addedCardIds + other.addedCardIds,
        changedCardIds = changedCardIds + other.changedCardIds,
    )
}

data class LocalMediaAttachment(
    val filename: String,
    val mimeType: String,
    val checksum: String,
    val bytes: ByteArray,
    val modifiedAtMillis: Long,
    val uploadState: String,
)

data class LocalContentSnapshot(
    val notes: Map<String, SyncNote> = emptyMap(),
    val cards: Map<Long, SyncCard> = emptyMap(),
    val notetypes: Map<Long, SyncNotetype> = emptyMap(),
    val media: Map<String, LocalMediaAttachment> = emptyMap(),
    val overrides: Map<String, LocalNoteOverride> = emptyMap(),
    val deckNames: Set<String> = emptySet(),
    val deckOptions: Map<String, DeckOptions> = emptyMap(),
    val deckPresets: DeckPresetState = DeckPresetState(),
    val deckOverrides: Map<String, String?> = emptyMap(),
    val cardFlags: Map<Long, Int> = emptyMap(),
    val cardStudyStates: Map<String, CardStudyState> = emptyMap(),
    val deletedNoteGuids: Set<String> = emptySet(),
    val pendingSyncByDeck: Map<String, PendingDeckChanges> = emptyMap(),
) {
    val cardCount: Int
        get() = cards.size
}

data class AddNoteDraft(
    val deckName: String,
    val front: String,
    val back: String,
    val tags: List<String> = emptyList(),
    val notetypeId: Long = NotetypeCatalog.BasicId,
    val cardOrds: List<Int> = listOf(0),
)

/** Identifies a locally authored note being edited, with its current values for prefilling the editor. */
data class EditNoteTarget(
    val noteGuid: String,
    val deckName: String,
    val front: String,
    val back: String,
    val tags: List<String>,
    val notetypeId: Long,
)

data class AddedLocalNote(
    val cardId: Long,
    val noteGuid: String,
    val content: LocalContentSnapshot,
)

fun SyncedCollection.withLocalContent(local: LocalContentSnapshot): SyncedCollection {
    val downloaded = withDeckOverrides(local.deckOverrides)
    val visibleNotes = downloaded.notes
        .filterKeys { it !in local.deletedNoteGuids }
        .mapValues { (guid, note) ->
            local.overrides[guid]?.let { override ->
                note.copy(fields = override.fields, tags = override.tags)
            } ?: note
        } + local.notes
    return downloaded.copy(
        notes = visibleNotes,
        cards = (downloaded.cards + local.cards)
            .filterValues { it.noteGuid !in local.deletedNoteGuids }
            .mapValues { (_, card) ->
                local.cardStudyStates[cardStudyKey(card.noteGuid, card.ord)]?.let { state ->
                    card.copy(studyState = state)
                } ?: card
            },
        media = downloaded.media + local.media.mapValues { (_, attachment) ->
            SyncMediaFile(attachment.filename, attachment.modifiedAtMillis.toString(), attachment.bytes)
        },
        notetypes = NotetypeCatalog.definitions + downloaded.notetypes + local.notetypes,
        deckNames = downloaded.deckNames + local.deckNames + local.cards.values.map(SyncCard::deckName),
    )
}

internal fun SyncedCollection.withDeckOverrides(overrides: Map<String, String?>): SyncedCollection {
    if (overrides.isEmpty()) return this
    val remappedCards = cards.values.mapNotNull { card ->
        card.deckName.remapDownloadedDeckName(overrides)?.let { card.copy(deckName = it) }
    }.associateBy(SyncCard::cardId)
    val remappedReviews = reviews.values.mapNotNull { review ->
        review.deckName.remapDownloadedDeckName(overrides)?.let { review.copy(deckName = it) }
    }.associateBy(SyncReview::reviewId)
    val remappedStudyDays = studyDays.values.mapNotNull { day ->
        day.deckName.remapDownloadedDeckName(overrides)?.let { day.copy(deckName = it) }
    }.associateBy { "${it.day}\u0000${it.deckName}" }
    val remappedDecks = deckRecords.values.mapNotNull { deck ->
        deck.name.remapDownloadedDeckName(overrides)?.let { deck.copy(name = it) }
    }.associateBy(SyncDeck::name)
    return copy(
        cards = remappedCards,
        reviews = remappedReviews,
        studyDays = remappedStudyDays,
        deckRecords = remappedDecks,
        deckNames = deckNames.mapNotNull { it.remapDownloadedDeckName(overrides) }
            .flatMap(::deckHierarchyNames)
            .toSet(),
    )
}

internal fun String.remapDownloadedDeckName(overrides: Map<String, String?>): String? {
    val closest = overrides.entries
        .filter { isDeckOrDescendantOf(it.key) }
        .maxByOrNull { it.key.length }
        ?: return this
    return closest.value?.plus(substring(closest.key.length))
}

internal fun normalizeMediaFilename(input: String): String {
    val filename = input.substringAfterLast('/').substringAfterLast('\\').trim()
    require(filename.isNotEmpty()) { "Attachment filename is empty" }
    require(filename.length <= 255) { "Attachment filename is too long" }
    require(filename.none { it.code < 32 }) { "Attachment filename contains control characters" }
    require(filename !in setOf(".", "..")) { "Attachment filename is invalid" }
    return filename
}

internal fun normalizeDeckName(input: String): String {
    require('\n' !in input && '\r' !in input) { "Deck names cannot contain line breaks" }
    val levels = input.trim().split("::").map(String::trim)
    require(levels.any(String::isNotEmpty)) { "Enter a deck name" }
    require(levels.all(String::isNotEmpty)) { "Deck levels cannot be empty" }
    return levels.joinToString("::")
}

internal fun deckHierarchyNames(name: String): List<String> {
    val levels = normalizeDeckName(name).split("::")
    return levels.indices.map { lastIndex -> levels.take(lastIndex + 1).joinToString("::") }
}

internal fun String.isDeckOrDescendantOf(parentName: String): Boolean =
    equals(parentName, ignoreCase = true) || startsWith("$parentName::", ignoreCase = true)

internal fun localCardId(noteGuid: String, ord: Int = 0): Long =
    if (ord == 0) fnvCardId(noteGuid) else fnvCardId("$noteGuid#card$ord")

private fun fnvCardId(seed: String): Long {
    var hash = -3_750_763_034_362_895_579L
    seed.forEach { character ->
        hash = (hash xor character.code.toLong()) * 1_099_511_628_211L
    }
    val positive = hash and Long.MAX_VALUE
    return if (positive == 0L) -1L else -positive
}

expect fun randomUuidString(): String
