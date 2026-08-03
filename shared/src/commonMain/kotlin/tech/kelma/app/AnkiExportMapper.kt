package tech.kelma.app

import kotlin.math.roundToInt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal class AnkiExportMapper(
    private val scheduler: SchedulingEngine = FsrsScheduler,
    private val json: Json = Json,
) {
    fun map(
        collection: SyncedCollection,
        options: CollectionExportOptions,
        deckOptions: Map<String, DeckOptions>,
        presets: DeckPresetState,
        schedules: Map<Long, LocalCardSchedule>,
        localReviews: List<ImmutableReviewExport>,
        exportedAtMillis: Long,
    ): AnkiDatabaseSnapshot {
        val selectedCards = collection.cards.values
            .filter { options.deckName == null || it.deckName.isDeckOrDescendantOf(options.deckName) }
            .sortedBy(SyncCard::cardId)
        require(
            selectedCards.isNotEmpty() || options.deckName == null ||
                collection.deckNames.any { it.isDeckOrDescendantOf(options.deckName) },
        ) { "The selected deck no longer exists" }
        val selectedNoteGuids = selectedCards.mapTo(mutableSetOf(), SyncCard::noteGuid)
        val selectedNotes = collection.notes.values.filter { it.guid in selectedNoteGuids }.sortedBy(SyncNote::guid)
        val selectedNotetypeIds = selectedNotes.mapTo(mutableSetOf(), SyncNote::notetypeId)
        val selectedDeckNames = (
            selectedCards.map(SyncCard::deckName) +
                collection.deckNames.filter { name ->
                    options.deckName == null || name.isDeckOrDescendantOf(options.deckName)
                }
            ).flatMap(::deckHierarchyNames).distinct().sorted().toSet()
        val usedIds = mutableSetOf(1L)
        val notetypeIds = selectedNotetypeIds.sorted().associateWith { sourceId ->
            allocateId("notetype:$sourceId:${collection.notetypes[sourceId]?.name}", usedIds)
        }
        val deckIds = selectedDeckNames.associateWith { name ->
            if (name.equals("Default", ignoreCase = true)) 1L else allocateId("deck:$name", usedIds)
        }
        val noteIds = selectedNotes.associate { note -> note.guid to allocateId("note:${note.guid}", usedIds) }
        val cardIds = selectedCards.associate { card ->
            val preferredId = card.createdAtMillis(exportedAtMillis) ?: exportedAtMillis
            card.cardId to allocateTimestampId(preferredId, usedIds)
        }
        val createdAtSeconds = exportedAtMillis / MillisPerDay * (MillisPerDay / 1_000L)
        val modifiedAtSeconds = exportedAtMillis / 1_000L
        val presetMapping = buildPresetMapping(
            selectedDeckNames,
            deckOptions,
            presets,
            options.includeDeckPresets,
            usedIds,
        )
        val reviewRows = if (options.includeScheduling) {
            buildReviewRows(collection, selectedCards, cardIds, deckOptions, localReviews)
        } else {
            emptyList()
        }
        val schedulesFromReviews = reviewRows.associateBy(AnkiMappedReview::sourceCardId)
        val noteRows = selectedNotes.map { note ->
            AnkiNoteRow(
                id = noteIds.getValue(note.guid),
                guid = note.guid,
                notetypeId = notetypeIds.getValue(note.notetypeId),
                modifiedAtSeconds = modifiedAtSeconds,
                tags = note.tags.sorted()
                    .joinToString(" ", prefix = " ", postfix = " ")
                    .takeIf { note.tags.isNotEmpty() }
                    .orEmpty(),
                fields = note.fields.joinToString("\u001f"),
                sortField = note.fields.firstOrNull().orEmpty(),
                checksum = noteChecksum(note.fields.firstOrNull().orEmpty()),
            )
        }
        val cardRows = selectedCards.mapIndexed { index, card ->
            val schedule = schedules[card.cardId] ?: schedulesFromReviews[card.cardId]?.after
            mapCard(
                card = card,
                ankiCardId = cardIds.getValue(card.cardId),
                noteId = noteIds.getValue(card.noteGuid),
                deckId = deckIds.getValue(card.deckName),
                schedule = schedule.takeIf { options.includeScheduling },
                position = index + 1,
                collectionCreatedAtSeconds = createdAtSeconds,
                modifiedAtSeconds = modifiedAtSeconds,
            )
        }
        val models = selectedNotetypeIds.sorted().associate { sourceId ->
            val notetype = collection.notetypes[sourceId] ?: NotetypeCatalog.definitions[sourceId]
                ?: error("Missing note type $sourceId")
            val id = notetypeIds.getValue(sourceId)
            id.toString() to ankiModel(id, notetype, modifiedAtSeconds)
        }
        val defaultDeckName = selectedDeckNames.firstOrNull { it.equals("Default", ignoreCase = true) }
        val decks = buildMap<String, JsonElement> {
            val defaultConfigId = defaultDeckName?.let(presetMapping.deckConfigIds::get) ?: 1L
            put("1", ankiDeck(1, "Default", defaultConfigId, modifiedAtSeconds))
            selectedDeckNames.filterNot { it.equals("Default", ignoreCase = true) }.forEach { name ->
                val configId = presetMapping.deckConfigIds[name] ?: 1L
                val id = deckIds.getValue(name)
                put(id.toString(), ankiDeck(id, name, configId, modifiedAtSeconds))
            }
        }
        val configurations = buildMap<String, JsonElement> {
            put("1", ankiDeckConfiguration(1, "Default", DeckOptions(), modifiedAtSeconds))
            presetMapping.configurations.forEach { config ->
                put(
                    config.id.toString(),
                    ankiDeckConfiguration(config.id, config.name, config.options, modifiedAtSeconds),
                )
            }
        }
        return AnkiDatabaseSnapshot(
            collection = AnkiCollectionRow(
                createdAtSeconds = createdAtSeconds,
                modifiedAtMillis = exportedAtMillis,
                schemaModifiedAtMillis = exportedAtMillis,
                configurationJson = ankiCollectionConfiguration().toString(),
                modelsJson = JsonObject(models).toString(),
                decksJson = JsonObject(decks).toString(),
                deckConfigurationsJson = JsonObject(configurations).toString(),
            ),
            notes = noteRows,
            cards = cardRows,
            reviews = reviewRows.map(AnkiMappedReview::row),
        )
    }

    private fun buildReviewRows(
        collection: SyncedCollection,
        cards: List<SyncCard>,
        cardIds: Map<Long, Long>,
        deckOptions: Map<String, DeckOptions>,
        localReviews: List<ImmutableReviewExport>,
    ): List<AnkiMappedReview> {
        val identities = cards.associateBy { portableCardIdentity(it.noteGuid, it.ord) }
        val confirmed = collection.reviews.values.mapNotNull { review ->
            val rating = Rating.entries.getOrNull(review.ease - 1) ?: return@mapNotNull null
            val card = identities[portableCardIdentity(review.noteGuid, review.cardOrd)]
                ?: collection.cards[review.sourceCardId]
                ?: return@mapNotNull null
            ImmutableReviewExport(review.reviewId, card.noteGuid, card.ord, rating, review.takenMillis.toLong())
        }
        val reviews = (confirmed + localReviews)
            .distinctBy(ImmutableReviewExport::reviewId)
            .filter { portableCardIdentity(it.noteGuid, it.cardOrdinal) in identities }
            .groupBy { portableCardIdentity(it.noteGuid, it.cardOrdinal) }
        return buildList {
            reviews.forEach { (identity, cardReviews) ->
                val card = identities.getValue(identity)
                var schedule: LocalCardSchedule? = null
                cardReviews.sortedBy(ImmutableReviewExport::reviewId).forEach { review ->
                    val before = schedule
                    val after = scheduler.review(
                        card.copy(scheduling = JsonObject(emptyMap())),
                        before,
                        review.rating,
                        review.reviewId,
                        null,
                        deckOptions[card.deckName] ?: DeckOptions(),
                    )
                    schedule = after
                    add(
                        AnkiMappedReview(
                            sourceCardId = card.cardId,
                            after = after,
                            row = AnkiReviewRow(
                                id = review.reviewId,
                                cardId = cardIds.getValue(card.cardId),
                                ease = review.rating.ordinal + 1,
                                interval = ankiReviewInterval(after, review.reviewId),
                                previousInterval = before?.let { ankiReviewInterval(it, review.reviewId) } ?: 0,
                                factor = (after.difficulty * 100.0).roundToInt().coerceIn(100, 1_100),
                                durationMillis = review.durationMillis.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                                type = when {
                                    before == null || before.phase == ReviewPhase.Learning -> 0
                                    before.phase == ReviewPhase.Relearning -> 2
                                    else -> 1
                                },
                            ),
                        ),
                    )
                }
            }
        }.sortedBy { it.row.id }
    }

    private fun mapCard(
        card: SyncCard,
        ankiCardId: Long,
        noteId: Long,
        deckId: Long,
        schedule: LocalCardSchedule?,
        position: Int,
        collectionCreatedAtSeconds: Long,
        modifiedAtSeconds: Long,
    ): AnkiCardRow {
        val state = schedule?.phase
        val intraday = schedule != null && state != ReviewPhase.Review && schedule.dueAtMillis -
            schedule.lastReviewAtMillis < MillisPerDay
        val queue = when {
            card.studyState == CardStudyState.Suspended -> -1
            schedule == null -> 0
            intraday -> 1
            state == ReviewPhase.Review -> 2
            else -> 3
        }
        val type = when (state) {
            null -> 0
            ReviewPhase.Learning -> 1
            ReviewPhase.Review -> 2
            ReviewPhase.Relearning -> 3
        }
        val due = when {
            schedule == null -> position.toLong()
            intraday -> schedule.dueAtMillis / 1_000L
            else -> ((schedule.dueAtMillis / 1_000L - collectionCreatedAtSeconds) / 86_400L).coerceAtLeast(1L)
        }
        return AnkiCardRow(
            id = ankiCardId,
            noteId = noteId,
            deckId = deckId,
            ordinal = card.ord,
            modifiedAtSeconds = modifiedAtSeconds,
            type = type,
            queue = queue,
            due = due,
            interval = schedule?.scheduledDays ?: 0,
            factor = schedule?.let { (it.difficulty * 100.0).roundToInt().coerceIn(100, 1_100) } ?: 0,
            repetitions = schedule?.repetitions ?: 0,
            lapses = schedule?.lapses ?: 0,
            remainingSteps = schedule?.step?.let { (it + 1) * 1_001 } ?: 0,
            data = schedule?.let { "{\"s\":${it.stability},\"d\":${it.difficulty}}" }.orEmpty(),
        )
    }

    private fun buildPresetMapping(
        deckNames: Set<String>,
        optionsByDeck: Map<String, DeckOptions>,
        presets: DeckPresetState,
        include: Boolean,
        usedIds: MutableSet<Long>,
    ): PresetMapping {
        if (!include) return PresetMapping(emptyMap(), emptyList())
        val presetsById = presets.presets.associateBy(DeckOptionsPreset::id)
        val configs = mutableMapOf<String, MappedDeckConfig>()
        val deckIds = mutableMapOf<String, Long>()
        deckNames.forEach { deck ->
            val assigned = presets.assignments[deck]?.let(presetsById::get)
            val options = optionsByDeck[deck] ?: assigned?.options ?: return@forEach
            val key = assigned?.let { "preset:${it.id}" } ?: "options:${json.encodeToString(options.validated())}"
            val config = configs.getOrPut(key) {
                MappedDeckConfig(
                    id = allocateId("deck-config:$key", usedIds),
                    name = assigned?.name ?: "${deck.substringAfterLast("::")} Options",
                    options = options.validated(),
                )
            }
            deckIds[deck] = config.id
        }
        return PresetMapping(deckIds, configs.values.toList())
    }
}

private data class AnkiMappedReview(
    val sourceCardId: Long,
    val row: AnkiReviewRow,
    val after: LocalCardSchedule,
)

private data class MappedDeckConfig(val id: Long, val name: String, val options: DeckOptions)
private data class PresetMapping(
    val deckConfigIds: Map<String, Long>,
    val configurations: List<MappedDeckConfig>,
)

private fun ankiModel(id: Long, notetype: SyncNotetype, modifiedAtSeconds: Long): JsonObject {
    val source = notetype.definition
    val fields = source.array("flds").mapIndexed { index, element ->
        buildJsonObject {
            element.jsonObjectOrEmpty().forEach { (key, value) -> put(key, value) }
            put("name", element.jsonObjectOrEmpty().stringValue("name").ifBlank { "Field ${index + 1}" })
            put("ord", index)
            put("sticky", element.jsonObjectOrEmpty().booleanValue("sticky"))
            put("rtl", element.jsonObjectOrEmpty().booleanValue("rtl"))
            put("font", element.jsonObjectOrEmpty().stringValue("font").ifBlank { "Arial" })
            put("size", element.jsonObjectOrEmpty().longValue("size").takeIf { it > 0 } ?: 20)
        }
    }
    val templates = source.array("tmpls").mapIndexed { index, element ->
        buildJsonObject {
            element.jsonObjectOrEmpty().forEach { (key, value) -> put(key, value) }
            put("name", element.jsonObjectOrEmpty().stringValue("name").ifBlank { "Card ${index + 1}" })
            put("ord", index)
            put("qfmt", element.jsonObjectOrEmpty().stringValue("qfmt"))
            put("afmt", element.jsonObjectOrEmpty().stringValue("afmt"))
            put("did", JsonNull)
        }
    }
    return buildJsonObject {
        source.forEach { (key, value) -> put(key, value) }
        put("id", id)
        put("name", notetype.name)
        put("type", if (templates.any { it.stringValue("qfmt").contains("{{cloze:") }) 1 else 0)
        put("mod", modifiedAtSeconds)
        put("usn", -1)
        put("sortf", 0)
        put("did", JsonNull)
        put("flds", JsonArray(fields))
        put("tmpls", JsonArray(templates))
        putJsonArray("req") {
            templates.indices.forEach { ordinal ->
                add(
                    buildJsonArray {
                        add(JsonPrimitive(ordinal))
                        add(JsonPrimitive("any"))
                        add(buildJsonArray { add(JsonPrimitive(0)) })
                    },
                )
            }
        }
        if ("css" !in source) put("css", "")
    }
}

private fun ankiDeck(id: Long, name: String, configId: Long, modifiedAtSeconds: Long): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("mod", modifiedAtSeconds)
    put("usn", -1)
    put("desc", "")
    put("dyn", 0)
    put("conf", configId)
    put("collapsed", true)
    put("browserCollapsed", true)
    put("extendNew", 0)
    put("extendRev", 0)
    put("newToday", JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0))))
    put("revToday", JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0))))
    put("lrnToday", JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0))))
    put("timeToday", JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0))))
}

private fun ankiDeckConfiguration(id: Long, name: String, options: DeckOptions, modified: Long): JsonObject =
    buildJsonObject {
        put("id", id)
        put("name", name)
        put("mod", modified)
        put("usn", -1)
        put("maxTaken", options.maximumAnswerSeconds)
        put("autoplay", options.autoplayAudio)
        put("timer", 0)
        put("replayq", true)
        put("dyn", false)
        putJsonObject("new") {
            put("bury", options.buryNewSiblings)
            put(
                "delays",
                JsonArray(options.effectiveLearningStepsSeconds.map { JsonPrimitive(it / 60.0) }),
            )
            put("initialFactor", 2_500)
            put("ints", JsonArray(listOf(1, 4, 0).map(::JsonPrimitive)))
            put("order", 1)
            put("perDay", options.newCardsPerDay)
        }
        putJsonObject("rev") {
            put("bury", options.buryReviewSiblings)
            put("ease4", 1.3)
            put("ivlFct", 1.0)
            put("maxIvl", options.maximumIntervalDays)
            put("perDay", options.maximumReviewsPerDay)
            put("hardFactor", 1.2)
        }
        putJsonObject("lapse") {
            put(
                "delays",
                JsonArray(options.effectiveRelearningStepsSeconds.map { JsonPrimitive(it / 60.0) }),
            )
            put("leechAction", 1)
            put("leechFails", 8)
            put("minInt", 1)
            put("mult", 0.0)
        }
        put("newMix", options.newReviewMixOrder.ordinal)
        put("interdayLearningMix", options.interdayLearningMixOrder.ordinal)
        put("reviewOrder", options.reviewSortOrder.ankiValue())
        put("newSortOrder", options.newCardSortOrder.ordinal)
        put("newGatherPriority", options.newCardGatherOrder.ankiValue())
        put("buryInterdayLearning", options.buryInterdayLearningSiblings)
        put("desiredRetention", options.desiredRetention)
        val paramsKey = if (options.effectiveSchedulerAlgorithm == SchedulerAlgorithm.Fsrs6) {
            "fsrsParams6"
        } else {
            "fsrsParams5"
        }
        put(paramsKey, JsonArray(options.fsrsParameters.map(::JsonPrimitive)))
    }

private fun ankiCollectionConfiguration(): JsonObject = buildJsonObject {
    put("curDeck", 1)
    put("curModel", 0)
    put("newSpread", 0)
    put("nextPos", 1)
    put("schedVer", 2)
    put("sched2021", true)
    put("creationOffset", 0)
    put("activeDecks", JsonArray(listOf(JsonPrimitive(1))))
}

private fun ankiReviewInterval(schedule: LocalCardSchedule, reviewedAtMillis: Long): Int {
    val delaySeconds = ((schedule.dueAtMillis - reviewedAtMillis) / 1_000L).coerceAtLeast(1L)
    return if (schedule.phase != ReviewPhase.Review && delaySeconds < 86_400L) {
        -delaySeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else {
        schedule.scheduledDays.coerceAtLeast(1)
    }
}

private fun noteChecksum(field: String): Long = sha1(field.encodeToByteArray()).take(4).fold(0L) { value, byte ->
    (value shl 8) or (byte.toLong() and 0xff)
}

private fun allocateId(seed: String, used: MutableSet<Long>): Long {
    var candidate = stablePositiveId(seed)
    while (!used.add(candidate)) candidate = if (candidate == Long.MAX_VALUE) 2 else candidate + 1
    return candidate
}

private fun allocateTimestampId(preferred: Long, used: MutableSet<Long>): Long {
    var candidate = preferred.coerceAtLeast(2L)
    while (!used.add(candidate)) candidate = if (candidate == Long.MAX_VALUE) 2L else candidate + 1L
    return candidate
}

private fun NewCardGatherOrder.ankiValue(): Int = when (this) {
    NewCardGatherOrder.Deck -> 0
    NewCardGatherOrder.DeckThenRandomNotes -> 5
    NewCardGatherOrder.LowestPosition -> 1
    NewCardGatherOrder.HighestPosition -> 2
    NewCardGatherOrder.RandomNotes -> 3
    NewCardGatherOrder.RandomCards -> 4
}

private fun ReviewSortOrder.ankiValue(): Int = when (this) {
    ReviewSortOrder.DueDateThenRandom -> 0
    ReviewSortOrder.DueDateThenDeck -> 1
    ReviewSortOrder.DeckThenDueDate -> 2
    ReviewSortOrder.IntervalAscending -> 3
    ReviewSortOrder.IntervalDescending -> 4
    ReviewSortOrder.DifficultyAscending -> 5
    ReviewSortOrder.DifficultyDescending -> 6
    ReviewSortOrder.RetrievabilityAscending -> 7
    ReviewSortOrder.RetrievabilityDescending -> 11
    ReviewSortOrder.RelativeOverdueness -> 12
    ReviewSortOrder.Random -> 8
    ReviewSortOrder.Added -> 9
    ReviewSortOrder.LatestAddedFirst -> 10
}

private fun portableCardIdentity(guid: String, ordinal: Int): String = "$guid\u0000$ordinal"
private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
private fun JsonElement.jsonObjectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.stringValue(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()
private fun JsonObject.booleanValue(key: String): Boolean = (this[key] as? JsonPrimitive)?.content == "true"
private fun JsonObject.longValue(key: String): Long = (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
