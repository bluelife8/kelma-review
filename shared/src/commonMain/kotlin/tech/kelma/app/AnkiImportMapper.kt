package tech.kelma.app

import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class AnkiImportMapper(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun map(
        sourceName: String,
        database: AnkiDatabaseSnapshot,
        media: List<ImportedMedia>,
    ): CollectionImportPlan {
        val warnings = mutableListOf<String>()
        val notetypes = if (database.normalizedNotetypes.isNotEmpty()) {
            normalizedNotetypes(database)
        } else {
            legacyNotetypes(database.collection.modelsJson)
        }
        val decks = if (database.normalizedDecks.isNotEmpty()) {
            database.normalizedDecks.associate { deck -> deck.id to normalizeAnkiDeckName(deck.name) }
        } else {
            legacyDecks(database.collection.decksJson)
        }
        val deckConfigurations = if (database.normalizedDeckConfigs.isNotEmpty()) {
            normalizedDeckConfigurations(database, decks)
        } else {
            legacyDeckConfigurations(database.collection.decksJson, database.collection.deckConfigurationsJson)
        }
        val notes = database.notes.map { note ->
            ImportedNote(
                sourceId = note.id,
                guid = note.guid.ifBlank { "anki-${note.id}" },
                notetypeId = note.notetypeId,
                fields = note.fields.split('\u001f'),
                tags = note.tags.trim().split(Regex("\\s+")).filter(String::isNotBlank).distinct(),
            )
        }
        val noteIds = notes.mapTo(mutableSetOf(), ImportedNote::sourceId)
        val cards = database.cards.filter { it.noteId in noteIds }.map { card ->
            ImportedCard(
                sourceId = card.id,
                noteSourceId = card.noteId,
                deckName = decks[card.deckId] ?: "Imported",
                ordinal = card.ordinal,
            )
        }
        val cardIds = cards.mapTo(mutableSetOf(), ImportedCard::sourceId)
        val reviews = database.reviews.mapNotNull { review ->
            val rating = Rating.entries.getOrNull(review.ease - 1) ?: return@mapNotNull null
            review.takeIf { it.cardId in cardIds && it.id > 0 }?.let {
                ImportedReview(it.id, it.cardId, rating, it.durationMillis.toLong().coerceAtLeast(0L))
            }
        }
        if (database.cards.any { it.type != 0 } && reviews.isEmpty()) {
            warnings += "Card due states were not trusted because the package contains no immutable review history."
        }
        val usedDecks = cards.map(ImportedCard::deckName).flatMap(::deckHierarchyNames).toSet()
        val declaredDecks = decks.values.filter { deck ->
            !deck.equals("Default", ignoreCase = true) || deck in usedDecks ||
                sourceName.endsWith(".colpkg", ignoreCase = true)
        }.flatMap(::deckHierarchyNames)
        return CollectionImportPlan(
            sourceName = sourceName,
            decks = usedDecks + declaredDecks,
            notetypes = notetypes,
            notes = notes,
            cards = cards,
            reviews = reviews,
            media = media,
            deckOptions = deckConfigurations.filterKeys { it in usedDecks || it in decks.values },
            warnings = warnings,
        )
    }

    private fun legacyNotetypes(modelsJson: String): List<ImportedNotetype> {
        val models = parseObject(modelsJson, "note types")
        return models.mapNotNull { (key, element) ->
            val model = element as? JsonObject ?: return@mapNotNull null
            val id = model.long("id") ?: key.toLongOrNull() ?: return@mapNotNull null
            ImportedNotetype(id, model.text("name").ifBlank { "Imported $id" }, model.toString())
        }
    }

    private fun normalizedNotetypes(database: AnkiDatabaseSnapshot): List<ImportedNotetype> {
        val fields = database.normalizedFields.groupBy(AnkiNormalizedField::notetypeId)
        val templates = database.normalizedTemplates.groupBy(AnkiNormalizedTemplate::notetypeId)
        return database.normalizedNotetypes.map { notetype ->
            val configuration = decodeProto(notetype.configuration)
            val css = configuration.firstOrNull { it.number == 3 && it.wireType == 2 }
                ?.bytes?.decodeToString(throwOnInvalidSequence = true).orEmpty()
            val definition = buildJsonObject {
                put("css", css)
                put(
                    "flds",
                    JsonArray(fields[notetype.id].orEmpty().sortedBy(AnkiNormalizedField::ordinal).map { field ->
                        buildJsonObject {
                            put("name", field.name)
                            put("ord", field.ordinal)
                        }
                    }),
                )
                put(
                    "tmpls",
                    JsonArray(
                        templates[notetype.id].orEmpty().sortedBy(AnkiNormalizedTemplate::ordinal).map { template ->
                            val values = decodeProto(template.configuration)
                            buildJsonObject {
                                put("name", template.name)
                                put("ord", template.ordinal)
                                put(
                                    "qfmt",
                                    values.firstOrNull { it.number == 1 && it.wireType == 2 }
                                        ?.bytes?.decodeToString(throwOnInvalidSequence = true).orEmpty(),
                                )
                                put(
                                    "afmt",
                                    values.firstOrNull { it.number == 2 && it.wireType == 2 }
                                        ?.bytes?.decodeToString(throwOnInvalidSequence = true).orEmpty(),
                                )
                            }
                        },
                    ),
                )
            }
            ImportedNotetype(notetype.id, notetype.name, definition.toString())
        }
    }

    private fun legacyDecks(decksJson: String): Map<Long, String> = buildMap {
        parseObject(decksJson, "decks").forEach { (key, value) ->
            val deck = value as? JsonObject ?: return@forEach
            val id = deck.long("id") ?: key.toLongOrNull() ?: return@forEach
            put(id, normalizeAnkiDeckName(deck.text("name").ifBlank { "Imported" }))
        }
    }

    private fun legacyDeckConfigurations(
        decksJson: String,
        configurationsJson: String,
    ): Map<String, ImportedDeckOptions> {
        val configurations = parseObject(configurationsJson, "deck presets").mapNotNull { (key, value) ->
            val source = value as? JsonObject ?: return@mapNotNull null
            val id = source.long("id") ?: key.toLongOrNull() ?: return@mapNotNull null
            id to importedDeckOptions(id, source.text("name").ifBlank { "Imported Options" }, source)
        }.toMap()
        return buildMap {
            parseObject(decksJson, "decks").values.forEach { element ->
                val deck = element as? JsonObject ?: return@forEach
                val name = normalizeAnkiDeckName(deck.text("name").ifBlank { "Imported" })
                val config = deck.long("conf")?.let(configurations::get) ?: return@forEach
                put(name, config)
            }
        }
    }

    private fun normalizedDeckConfigurations(
        database: AnkiDatabaseSnapshot,
        deckNames: Map<Long, String>,
    ): Map<String, ImportedDeckOptions> {
        val configurations = database.normalizedDeckConfigs.associate { config ->
            config.id to ImportedDeckOptions(
                config.id,
                config.name,
                parseNormalizedDeckOptions(config.configuration),
            )
        }
        return buildMap {
            database.normalizedDecks.forEach { deck ->
                val configId = decodeProto(deck.common).firstOrNull { it.number == 2 && it.wireType == 0 }?.varint ?: 1L
                val config = configurations[configId] ?: return@forEach
                put(deckNames[deck.id] ?: return@forEach, config)
            }
        }
    }

    private fun importedDeckOptions(id: Long, name: String, source: JsonObject): ImportedDeckOptions {
        val new = source.objectValue("new")
        val review = source.objectValue("rev")
        val lapse = source.objectValue("lapse")
        val params6 = source.numberList("fsrsParams6")
        val params5 = source.numberList("fsrsParams5")
        val algorithm = if (params6.size == 21) SchedulerAlgorithm.Fsrs6 else if (params5.size == 19) {
            SchedulerAlgorithm.Fsrs5
        } else {
            SchedulerAlgorithm.Fsrs6
        }
        val parameters = when (algorithm) {
            SchedulerAlgorithm.Fsrs6 -> params6.takeIf { it.size == 21 } ?: DefaultFsrs6Parameters
            SchedulerAlgorithm.Fsrs5 -> params5
        }
        val learningSteps = new.minuteSteps("delays", listOf(1, 10), 2)
        val relearningSteps = lapse.minuteSteps("delays", listOf(10), 1)
        val options = DeckOptions(
            newCardsPerDay = new.int("perDay", 20).coerceIn(0, 9_999),
            maximumReviewsPerDay = review.int("perDay", 200).coerceIn(0, 9_999),
            learningStepsMinutes = learningSteps,
            relearningStepsMinutes = relearningSteps,
            fsrsLearningStepsSeconds = if (algorithm == SchedulerAlgorithm.Fsrs6) {
                new.secondSteps("delays", learningSteps.map { it * 60 }, 2)
            } else {
                null
            },
            fsrsRelearningStepsSeconds = if (algorithm == SchedulerAlgorithm.Fsrs6) {
                lapse.secondSteps("delays", relearningSteps.map { it * 60 }, 1)
            } else {
                null
            },
            autoplayAudio = source.boolean("autoplay", true),
            maximumAnswerSeconds = source.int("maxTaken", 60).coerceIn(1, 7_200),
            desiredRetention = source.double("desiredRetention", 0.9).coerceIn(0.70, 0.99),
            fsrsParameters = parameters,
            schedulerAlgorithm = algorithm,
            maximumIntervalDays = review.int("maxIvl", 36_500).coerceIn(1, 36_500),
            newCardGatherOrder = ankiNewCardGatherOrder(source.int("newGatherPriority", 0)),
            newCardSortOrder = enumAt(source.int("newSortOrder", 0), NewCardSortOrder.entries),
            newReviewMixOrder = enumAt(source.int("newMix", 0), QueueMixOrder.entries),
            interdayLearningMixOrder = enumAt(source.int("interdayLearningMix", 0), QueueMixOrder.entries),
            reviewSortOrder = ankiReviewSortOrder(source.int("reviewOrder", 0)),
            buryNewSiblings = new.boolean("bury", true),
            buryReviewSiblings = review.boolean("bury", true),
            buryInterdayLearningSiblings = source.boolean("buryInterdayLearning", false),
        ).validated()
        return ImportedDeckOptions(id, name, options)
    }

    private fun parseNormalizedDeckOptions(bytes: ByteArray): DeckOptions {
        val fields = decodeProto(bytes)
        val learningSeconds = fields.floatList(1).toStepSeconds(2, listOf(60, 600))
        val relearningSeconds = fields.floatList(2).toStepSeconds(1, listOf(600))
        val params6 = fields.floatList(6).map(Float::toDouble)
        val params5 = fields.floatList(5).map(Float::toDouble)
        val algorithm = if (params6.size == 21) {
            SchedulerAlgorithm.Fsrs6
        } else if (params5.size == 19) {
            SchedulerAlgorithm.Fsrs5
        } else {
            SchedulerAlgorithm.Fsrs6
        }
        val parameters = when (algorithm) {
            SchedulerAlgorithm.Fsrs6 -> params6.takeIf { it.size == 21 } ?: DefaultFsrs6Parameters
            SchedulerAlgorithm.Fsrs5 -> params5
        }
        val retention = fields.fixedFloat(37)?.toDouble()?.takeIf { it in 0.70..0.99 } ?: 0.9
        return DeckOptions(
            newCardsPerDay = fields.varintInt(9)?.coerceIn(0, 9_999) ?: 20,
            maximumReviewsPerDay = fields.varintInt(10)?.coerceIn(0, 9_999) ?: 200,
            learningStepsMinutes = learningSeconds.toDisplayMinutes(),
            relearningStepsMinutes = relearningSeconds.toDisplayMinutes(),
            fsrsLearningStepsSeconds = learningSeconds,
            fsrsRelearningStepsSeconds = relearningSeconds,
            autoplayAudio = fields.varint(23)?.let { it == 0L } ?: true,
            maximumAnswerSeconds = fields.varintInt(24)?.coerceIn(1, 7_200) ?: 60,
            desiredRetention = retention,
            fsrsParameters = parameters,
            schedulerAlgorithm = algorithm,
            maximumIntervalDays = fields.varintInt(16)?.coerceIn(1, 36_500) ?: 36_500,
            newCardGatherOrder = ankiNewCardGatherOrder(fields.varintInt(34) ?: 0),
            newCardSortOrder = enumAt(fields.varintInt(32) ?: 0, NewCardSortOrder.entries),
            newReviewMixOrder = enumAt(fields.varintInt(30) ?: 0, QueueMixOrder.entries),
            interdayLearningMixOrder = enumAt(fields.varintInt(31) ?: 0, QueueMixOrder.entries),
            reviewSortOrder = ankiReviewSortOrder(fields.varintInt(33) ?: 0),
            buryNewSiblings = fields.varint(27)?.let { it != 0L } ?: true,
            buryReviewSiblings = fields.varint(28)?.let { it != 0L } ?: true,
            buryInterdayLearningSiblings = fields.varint(29)?.let { it != 0L } ?: false,
        ).validated()
    }

    private fun parseObject(value: String, description: String): JsonObject = try {
        json.parseToJsonElement(value.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
    } catch (failure: Exception) {
        throw IllegalArgumentException("The Anki $description metadata is invalid", failure)
    }
}

private fun normalizeAnkiDeckName(name: String): String =
    normalizeDeckName(name.replace("\u001f", "::").ifBlank { "Imported" })

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
private fun JsonObject.int(key: String, default: Int): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: default
private fun JsonObject.double(key: String, default: Double): Double =
    (this[key] as? JsonPrimitive)?.doubleOrNull ?: default
private fun JsonObject.boolean(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default
private fun JsonObject.objectValue(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.numberList(key: String): List<Double> = (this[key] as? JsonArray).orEmpty().mapNotNull {
    (it as? JsonPrimitive)?.doubleOrNull
}
private fun JsonObject.minuteSteps(key: String, fallback: List<Int>, maximum: Int): List<Int> =
    numberList(key).map(Double::roundToInt).filter { it > 0 }.take(maximum).ifEmpty { fallback }
private fun JsonObject.secondSteps(key: String, fallback: List<Int>, maximum: Int): List<Int> =
    numberList(key).map { (it * 60.0).roundToInt() }.filter { it in 1 until 86_400 }.take(maximum)
        .ifEmpty { fallback }
private fun <T> enumAt(index: Int, values: List<T>): T = values.getOrElse(index) { values.first() }
private fun ankiNewCardGatherOrder(value: Int): NewCardGatherOrder = when (value) {
    5 -> NewCardGatherOrder.DeckThenRandomNotes
    1 -> NewCardGatherOrder.LowestPosition
    2 -> NewCardGatherOrder.HighestPosition
    3 -> NewCardGatherOrder.RandomNotes
    4 -> NewCardGatherOrder.RandomCards
    else -> NewCardGatherOrder.Deck
}
private fun ankiReviewSortOrder(value: Int): ReviewSortOrder = when (value) {
    1 -> ReviewSortOrder.DueDateThenDeck
    2 -> ReviewSortOrder.DeckThenDueDate
    3 -> ReviewSortOrder.IntervalAscending
    4 -> ReviewSortOrder.IntervalDescending
    5 -> ReviewSortOrder.DifficultyAscending
    6 -> ReviewSortOrder.DifficultyDescending
    7 -> ReviewSortOrder.RetrievabilityAscending
    11 -> ReviewSortOrder.RetrievabilityDescending
    12 -> ReviewSortOrder.RelativeOverdueness
    8 -> ReviewSortOrder.Random
    9 -> ReviewSortOrder.Added
    10 -> ReviewSortOrder.LatestAddedFirst
    else -> ReviewSortOrder.DueDateThenRandom
}
private fun List<ProtoField>.varint(number: Int): Long? =
    firstOrNull { it.number == number && it.wireType == 0 }?.varint
private fun List<ProtoField>.varintInt(number: Int): Int? =
    varint(number)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
private fun List<ProtoField>.fixedFloat(number: Int): Float? =
    firstOrNull { it.number == number && it.wireType == 5 }?.bytes?.toLittleEndianFloat()
private fun List<ProtoField>.floatList(number: Int): List<Float> {
    val bytes = firstOrNull { it.number == number && it.wireType == 2 }?.bytes ?: return emptyList()
    return bytes.asList().chunked(4).filter { it.size == 4 }.map { chunk -> chunk.toByteArray().toLittleEndianFloat() }
}
private fun List<Float>.toStepSeconds(maximum: Int, fallback: List<Int>): List<Int> =
    map { (it * 60f).roundToInt() }
        .filter { it in 1 until 86_400 }
        .take(maximum)
        .takeIf { it.zipWithNext().all { (left, right) -> left < right } }
        .orEmpty()
        .ifEmpty { fallback }
private fun List<Int>.toDisplayMinutes(): List<Int> = map { ((it + 30) / 60).coerceAtLeast(1) }
private fun ByteArray.toLittleEndianFloat(): Float {
    if (size != 4) return Float.NaN
    val bits = (this[0].toInt() and 0xff) or ((this[1].toInt() and 0xff) shl 8) or
        ((this[2].toInt() and 0xff) shl 16) or ((this[3].toInt() and 0xff) shl 24)
    return Float.fromBits(bits)
}
