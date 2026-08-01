package tech.kelma.app

internal data class NoteUploadOutcome(
    val uploadedGuids: Set<String> = emptySet(),
    val cards: List<Pair<Long, CardPushBody>> = emptyList(),
)

internal data class DeckUploadOutcome(
    val decks: List<PendingDeckUpload> = emptyList(),
    val cards: List<Pair<Long, CardPushBody>> = emptyList(),
    val conflictCount: Int = 0,
)

internal data class DeckDependency(
    val name: String,
    val body: DeckPushBody,
    val noteGuids: Set<String>,
    val force: Boolean,
) {
    constructor(name: String, body: DeckPushBody, noteGuid: String, force: Boolean) :
        this(name, body, setOf(noteGuid), force)
}

internal data class NotetypeDependency(
    val id: Long,
    val body: NotetypePushBody,
    val noteGuids: Set<String>,
    val force: Boolean,
) {
    constructor(id: Long, body: NotetypePushBody, noteGuid: String, force: Boolean) :
        this(id, body, setOf(noteGuid), force)
}

internal data class DeckMutationRecord(
    val source: String,
    val name: String,
    val body: DeckPushBody,
    val force: Boolean,
)

internal fun mergeDeckDependencies(group: List<DeckDependency>): DeckDependency = DeckDependency(
    name = group.first().name,
    body = group.first().body,
    noteGuids = group.flatMapTo(mutableSetOf(), DeckDependency::noteGuids),
    force = group.any(DeckDependency::force),
)

internal fun mergeNotetypeDependencies(group: List<NotetypeDependency>): NotetypeDependency = NotetypeDependency(
    id = group.first().id,
    body = group.first().body,
    noteGuids = group.flatMapTo(mutableSetOf(), NotetypeDependency::noteGuids),
    force = group.any(NotetypeDependency::force),
)

internal fun NotePushBody.toBatchItem(guid: String) = BatchNotePushItem(
    guid,
    notetypeId,
    fields,
    tags,
    clientModifiedAt,
    baseChecksum,
)

internal fun CardPushBody.toBatchItem(cardId: Long) = BatchCardPushItem(
    cardId,
    noteGuid,
    deckName,
    ord,
    scheduling,
    clientModifiedAt,
    studyState,
    studyStateClientModifiedAt,
    scheduleResetThroughReviewId,
    scheduleResetClientModifiedAt,
    dueDateOverrideMillis,
    dueDateOverrideClientModifiedAt,
)

internal fun NotetypePushBody.toBatchItem(notetypeId: Long) = BatchNotetypePushItem(
    notetypeId,
    name,
    definition,
    clientModifiedAt,
    baseChecksum,
)

internal fun DeckPushBody.toBatchItem(name: String) = BatchDeckPushItem(
    name,
    config,
    clientModifiedAt,
    baseChecksum,
)

internal fun BatchDeleteRequest.chunked(limit: Int): List<BatchDeleteRequest> {
    val resources = notes.map { "note" to it } +
        cards.map { "card" to it.toString() } +
        notetypes.map { "notetype" to it.toString() } +
        decks.map { "deck" to it }
    return resources.chunked(limit).map { chunk ->
        BatchDeleteRequest(
            notes = chunk.filter { it.first == "note" }.map(Pair<String, String>::second),
            cards = chunk.filter { it.first == "card" }.map { it.second.toLong() },
            notetypes = chunk.filter { it.first == "notetype" }.map { it.second.toLong() },
            decks = chunk.filter { it.first == "deck" }.map(Pair<String, String>::second),
        )
    }
}

internal const val MaximumBatchPushRecords = 500
internal const val MaximumBatchDeleteRecords = 3_000
internal const val MaximumParallelMediaRequests = 4
