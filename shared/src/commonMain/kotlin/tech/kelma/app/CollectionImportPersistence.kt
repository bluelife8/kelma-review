package tech.kelma.app

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tech.kelma.db.KelmaDatabase

internal class CollectionImportPersistence(
    private val database: KelmaDatabase,
    private val json: Json,
    private val scheduler: SchedulingEngine,
    private val rebuildSchedules: () -> Unit,
    private val mediaAttachments: MediaAttachmentPersistence,
) {
    private val queries = database.kelmaQueries
    private val studyDayPolicies = StudyDayPolicyPersistence(database, json)
    private val stringList = ListSerializer(String.serializer())

    fun import(plan: CollectionImportPlan, nowMillis: Long): CollectionImportResult {
        var result: CollectionImportResult? = null
        database.transaction {
            val raw = loadDownloadedCollection(queries, json)
            val localBefore = loadLocalContentSnapshot(queries, json)
            val displayedBefore = raw.withLocalContent(localBefore)
            val mediaSave = mediaAttachments.saveImported(plan.media, nowMillis)
            val notetypeIds = importNotetypes(plan.notetypes, displayedBefore, mediaSave.filenames, nowMillis)
            val displayedWithNotetypes = raw.withLocalContent(loadLocalContentSnapshot(queries, json))
            val notes = importNotes(plan.notes, notetypeIds, displayedWithNotetypes, mediaSave.filenames, nowMillis)
            val cards = importCards(
                plan.cards,
                notes.sourceGuids,
                raw.withLocalContent(loadLocalContentSnapshot(queries, json)),
                nowMillis,
            )
            plan.decks.forEach { deck -> deckHierarchyNames(deck).forEach { queries.insertLocalDeck(it, nowMillis) } }
            plan.decks.filter { deck ->
                plan.cards.none { card -> card.deckName.isDeckOrDescendantOf(deck) }
            }.forEach { emptyDeck ->
                if (emptyDeck !in raw.deckNames && emptyDeck !in raw.deckRecords) {
                    queries.upsertLocalDeckSync(emptyDeck, "upsert", null, "", nowMillis)
                }
            }
            val preservedDeckOptions = importDeckOptions(
                plan.deckOptions,
                plan.presets,
                localBefore.deckOptions.keys,
                nowMillis,
            )
            rebuildSchedules()
            val reviewResult = importReviews(plan.reviews, cards, nowMillis)
            rebuildSchedules()
            queries.markBrowseIndexDirty()
            result = CollectionImportResult(
                addedNotes = notes.added,
                reusedNotes = notes.reused,
                copiedConflicts = notes.copiedConflicts,
                addedCards = cards.added,
                addedReviews = reviewResult.added,
                skippedReviewConflicts = reviewResult.conflicts,
                addedMedia = mediaSave.added,
                renamedMedia = mediaSave.renamed,
                warnings = plan.warnings + if (preservedDeckOptions > 0) {
                    listOf("Existing local options were preserved for $preservedDeckOptions deck(s).")
                } else {
                    emptyList()
                },
            )
        }
        return checkNotNull(result)
    }

    private fun importNotetypes(
        imported: List<ImportedNotetype>,
        existingCollection: SyncedCollection,
        mediaNames: Map<String, String>,
        nowMillis: Long,
    ): Map<Long, Long> {
        val existing = existingCollection.notetypes.toMutableMap()
        val usedIds = existing.keys.toMutableSet()
        return buildMap {
            imported.forEach { source ->
                val parsed = parseDefinition(source.definitionJson)
                val definition = renameMedia(parsed, mediaNames) as JsonObject
                val direct = existing[source.sourceId]
                val matching = existing.values.firstOrNull { it.name == source.name && it.definition == definition }
                val id = when {
                    direct?.name == source.name && direct.definition == definition -> source.sourceId
                    matching != null -> matching.notetypeId
                    direct == null && source.sourceId !in usedIds -> source.sourceId
                    else -> allocateImportedNotetypeId(source, definition, usedIds)
                }
                if (id !in existing) {
                    queries.insertLocalNotetype(id, source.name, definition.toString(), nowMillis)
                    existing[id] = SyncNotetype(id, source.name, definition)
                    usedIds += id
                }
                put(source.sourceId, id)
            }
        }
    }

    private fun importNotes(
        imported: List<ImportedNote>,
        notetypeIds: Map<Long, Long>,
        existingCollection: SyncedCollection,
        mediaNames: Map<String, String>,
        nowMillis: Long,
    ): ImportedNotesResult {
        val existing = existingCollection.notes.toMutableMap()
        val sourceGuids = mutableMapOf<Long, String>()
        var added = 0
        var reused = 0
        var conflicts = 0
        imported.forEach { source ->
            val notetypeId = notetypeIds[source.notetypeId] ?: source.notetypeId
            val fields = source.fields.map { renameMedia(it, mediaNames) }
            val tags = source.tags.map(String::trim).filter(String::isNotEmpty).distinct()
            val requestedGuid = source.guid.ifBlank { "anki-${source.sourceId}" }
            val direct = existing[requestedGuid]
            val finalGuid = when {
                direct == null -> requestedGuid
                direct.notetypeId == notetypeId && direct.fields == fields && direct.tags.toSet() == tags.toSet() -> {
                    reused++
                    requestedGuid
                }
                else -> {
                    conflicts++
                    conflictCopyGuid(requestedGuid, notetypeId, fields, tags, existing)
                }
            }
            val finalExisting = existing[finalGuid]
            if (finalExisting == null) {
                queries.insertLocalNote(
                    finalGuid,
                    notetypeId,
                    json.encodeToString(stringList, fields),
                    json.encodeToString(stringList, tags),
                    nowMillis,
                )
                queries.upsertLocalNoteSync(finalGuid, "upsert", "", nowMillis)
                existing[finalGuid] = SyncNote(finalGuid, notetypeId, fields, tags)
                added++
            } else if (finalGuid != requestedGuid) {
                reused++
            }
            sourceGuids[source.sourceId] = finalGuid
        }
        return ImportedNotesResult(sourceGuids, added, reused, conflicts)
    }

    private fun importCards(
        imported: List<ImportedCard>,
        noteGuids: Map<Long, String>,
        existingCollection: SyncedCollection,
        nowMillis: Long,
    ): ImportedCardsResult {
        val byIdentity = existingCollection.cards.values
            .associateBy { portableCardIdentity(it.noteGuid, it.ord) }
            .toMutableMap()
        val byId = existingCollection.cards.toMutableMap()
        val sourceCards = mutableMapOf<Long, SyncCard>()
        var added = 0
        imported.forEach { source ->
            val guid = noteGuids[source.noteSourceId] ?: return@forEach
            val deck = normalizeDeckName(source.deckName)
            val identity = portableCardIdentity(guid, source.ordinal)
            val existing = byIdentity[identity]
            val card = if (existing != null) {
                existing
            } else {
                val cardId = localCardId(guid, source.ordinal)
                require(byId[cardId]?.let { it.noteGuid == guid && it.ord == source.ordinal } != false) {
                    "An imported card identity collided with an existing local card"
                }
                deckHierarchyNames(deck).forEach { queries.insertLocalDeck(it, nowMillis) }
                queries.insertLocalCard(cardId, guid, deck, source.ordinal.toLong(), nowMillis)
                SyncCard(cardId, guid, deck, source.ordinal).also {
                    byIdentity[identity] = it
                    byId[cardId] = it
                    added++
                }
            }
            sourceCards[source.sourceId] = card
        }
        return ImportedCardsResult(sourceCards, added)
    }

    private fun importReviews(
        imported: List<ImportedReview>,
        cards: ImportedCardsResult,
        nowMillis: Long,
    ): ImportedReviewsResult {
        val existing = mutableMapOf<Long, ExistingReview>()
        queries.selectReviews { id, _, noteGuid, ordinal, _, ease, _, _, _, _, _, _, _ ->
            id to ExistingReview(noteGuid, ordinal.toInt(), Rating.entries.getOrNull(ease.toInt() - 1))
        }.executeAsList().forEach { (id, review) -> existing[id] = review }
        queries.selectAllLocalReviewEvents {
                _, _, noteGuid, ordinal, _, rating, reviewedAt, _, _, _, _, _, reviewId, _, _ ->
            val id = reviewId.takeIf { it > 0 } ?: reviewedAt
            id to ExistingReview(noteGuid, ordinal.toInt(), Rating.entries.firstOrNull { it.name == rating })
        }.executeAsList().forEach { (id, review) -> existing[id] = review }
        val schedules = mutableMapOf<Long, LocalCardSchedule?>()
        val studyDayPolicy = studyDayPolicies.load()
        var added = 0
        var conflicts = 0
        imported.sortedBy(ImportedReview::reviewId).forEach { source ->
            val card = cards.sourceCards[source.cardSourceId] ?: return@forEach
            val prior = existing[source.reviewId]
            if (prior != null) {
                if (prior.noteGuid != card.noteGuid || prior.ordinal != card.ord || prior.rating != source.rating) {
                    conflicts++
                }
                return@forEach
            }
            val before = schedules.getOrPut(card.cardId) { loadLocalSchedule(queries, card.cardId) }
            val options = queries.selectLocalDeckOptionsForDeck(card.deckName).executeAsOneOrNull()
                ?.let { json.decodeFromString<DeckOptions>(it).validated() } ?: DeckOptions()
            val after = scheduler.review(
                card.copy(scheduling = JsonObject(emptyMap())),
                before,
                source.rating,
                source.reviewId,
                null,
                options,
            ).alignedToStudyDay(studyDayPolicy)
            queries.insertLocalReviewEvent(
                card.cardId,
                card.noteGuid,
                card.ord.toLong(),
                card.deckName,
                source.rating.name,
                source.reviewId,
                studyDayAt(source.reviewId, studyDayPolicy),
                source.durationMillis.coerceIn(0, options.maximumAnswerSeconds * 1_000L),
                before?.let { json.encodeToString(it) },
                json.encodeToString(after),
                if (before == null) 1 else 0,
                if (before?.phase == ReviewPhase.Review) 1 else 0,
                source.reviewId,
            )
            schedules[card.cardId] = after
            existing[source.reviewId] = ExistingReview(card.noteGuid, card.ord, source.rating)
            added++
        }
        return ImportedReviewsResult(added, conflicts)
    }

    private fun importDeckOptions(
        imported: Map<String, ImportedDeckOptions>,
        unassigned: List<ImportedDeckOptions>,
        protectedDecks: Set<String>,
        nowMillis: Long,
    ): Int {
        if (imported.isEmpty() && unassigned.isEmpty()) return 0
        val state = loadLocalContentSnapshot(queries, json).deckPresets
        val presetsById = state.presets.associateBy(DeckOptionsPreset::id).toMutableMap()
        val names = state.presets.associateBy { it.name.lowercase() }.toMutableMap()
        val mapped = mutableMapOf<Long, String>()
        (imported.values + unassigned).distinctBy(ImportedDeckOptions::sourceId).forEach { source ->
            val options = source.options.validated()
            val matching = state.presets.firstOrNull { it.options == options }
            if (matching != null) {
                mapped[source.sourceId] = matching.id
                return@forEach
            }
            var id = importedPresetId(source)
            while (id in presetsById) id += "-imported"
            val requestedName = runCatching { validatePresetName(source.name) }.getOrDefault("Imported Options")
            var name = requestedName
            var suffix = 2
            while (name.lowercase() in names) name = "$requestedName (${suffix++})"
            queries.insertDeckOptionPreset(
                id,
                name,
                json.encodeToString(options),
                nowMillis,
                nowMillis,
            )
            val preset = DeckOptionsPreset(id, name, options, nowMillis, nowMillis)
            presetsById[id] = preset
            names[name.lowercase()] = preset
            mapped[source.sourceId] = id
        }
        var preserved = 0
        imported.forEach { (deck, source) ->
            mapped[source.sourceId]?.let { presetId ->
                val normalizedDeck = normalizeDeckName(deck)
                if (protectedDecks.any { it.equals(normalizedDeck, ignoreCase = true) }) {
                    preserved++
                } else {
                    queries.upsertDeckPresetAssignment(normalizedDeck, presetId, nowMillis)
                }
            }
        }
        return preserved
    }

    private fun parseDefinition(value: String): JsonObject = try {
        json.parseToJsonElement(value) as? JsonObject ?: JsonObject(emptyMap())
    } catch (failure: Exception) {
        throw IllegalArgumentException("An imported note type is invalid", failure)
    }
}

private data class ImportedNotesResult(
    val sourceGuids: Map<Long, String>,
    val added: Int,
    val reused: Int,
    val copiedConflicts: Int,
)
private data class ImportedCardsResult(val sourceCards: Map<Long, SyncCard>, val added: Int)
private data class ImportedReviewsResult(val added: Int, val conflicts: Int)
private data class ExistingReview(val noteGuid: String, val ordinal: Int, val rating: Rating?)

private fun importedPresetId(source: ImportedDeckOptions): String {
    val preferred = source.preferredId?.trim()
    return preferred?.takeIf { id ->
        id.isNotEmpty() && id.length <= 128 && id.none { it.code < 32 }
    } ?: "anki-${source.sourceId}"
}

private fun allocateImportedNotetypeId(
    source: ImportedNotetype,
    definition: JsonObject,
    used: MutableSet<Long>,
): Long {
    var candidate = -stablePositiveId("${source.name}:$definition")
    if (candidate in setOf(NotetypeCatalog.BasicId, NotetypeCatalog.BasicReversedId)) candidate -= 2
    while (candidate in used) candidate--
    return candidate
}

private fun conflictCopyGuid(
    guid: String,
    notetypeId: Long,
    fields: List<String>,
    tags: List<String>,
    existing: Map<String, SyncNote>,
): String {
    val digest = SchedulerHistorySha256().update(notetypeId.toString()).update("\u0000")
        .update(fields.joinToString("\u001f")).update("\u0000").update(tags.sorted().joinToString(" ")).hexDigest()
    var candidate = "$guid-import-${digest.take(10)}"
    var suffix = 2
    while (
        existing[candidate]?.let {
            it.notetypeId == notetypeId && it.fields == fields && it.tags.toSet() == tags.toSet()
        } == false
    ) {
        candidate = "$guid-import-${digest.take(10)}-${suffix++}"
    }
    return candidate
}

private fun renameMedia(value: String, names: Map<String, String>): String =
    names.entries.sortedByDescending { it.key.length }.fold(value) { current, (old, new) ->
        if (old == new) current else current.replaceMediaFilename(old, new)
    }

private fun String.replaceMediaFilename(old: String, new: String): String {
    val output = StringBuilder(length)
    var cursor = 0
    while (cursor < length) {
        val match = indexOf(old, cursor)
        if (match < 0) {
            output.append(this, cursor, length)
            break
        }
        val end = match + old.length
        val boundedBefore = match == 0 || !this[match - 1].isMediaFilenameCharacter()
        val boundedAfter = end == length || !this[end].isMediaFilenameCharacter()
        if (boundedBefore && boundedAfter) {
            output.append(this, cursor, match).append(new)
            cursor = end
        } else {
            output.append(this, cursor, end)
            cursor = end
        }
    }
    return output.toString()
}

private fun Char.isMediaFilenameCharacter(): Boolean = isLetterOrDigit() || this == '_' || this == '-' || this == '.'

private fun renameMedia(element: JsonElement, names: Map<String, String>): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { renameMedia(it.value, names) })
    is JsonArray -> JsonArray(element.map { renameMedia(it, names) })
    is JsonPrimitive -> if (element.isString) JsonPrimitive(renameMedia(element.content, names)) else element
}

private fun portableCardIdentity(guid: String, ordinal: Int): String = "$guid\u0000$ordinal"
