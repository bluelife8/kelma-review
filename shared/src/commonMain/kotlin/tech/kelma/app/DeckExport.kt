package tech.kelma.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class KelmaDeckExport(
    val format: String = "kelma-deck",
    val version: Int = 1,
    val deckName: String,
    val exportedAtMillis: Long,
    val deckOptions: Map<String, DeckOptions>,
    val notes: List<SyncNote>,
    val cards: List<SyncCard>,
    val notetypes: List<SyncNotetype>,
    val schedules: List<LocalCardSchedule>,
    val media: List<KelmaExportMedia>,
)

@Serializable
data class KelmaExportMedia(
    val filename: String,
    val modifiedAt: String,
    val base64: String,
)

@Serializable
data class KelmaJsonExport(
    val format: String = "kelma-interchange",
    val version: Int = 2,
    val scope: String,
    val deckName: String? = null,
    val exportedAtMillis: Long,
    val deckNames: List<String>,
    val deckOptions: Map<String, DeckOptions>,
    val presets: List<DeckOptionsPreset>,
    val presetAssignments: Map<String, String>,
    val notes: List<SyncNote>,
    val cards: List<SyncCard>,
    val notetypes: List<SyncNotetype>,
    val reviews: List<KelmaJsonReview>,
    val media: List<KelmaExportMedia>,
)

@Serializable
data class KelmaJsonReview(
    val reviewId: Long,
    val noteGuid: String,
    val cardOrdinal: Int,
    val rating: String,
    val durationMillis: Long,
)

data class DeckExportFile(val suggestedName: String, val content: String)

private val ExportJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

fun SyncedCollection.exportDeck(
    deckName: String,
    optionsByDeck: Map<String, DeckOptions> = emptyMap(),
    schedules: Map<Long, LocalCardSchedule> = emptyMap(),
    exportedAtMillis: Long = currentEpochMillis(),
    includeScheduling: Boolean = true,
    includeDeckOptions: Boolean = true,
    includeMedia: Boolean = true,
    presets: DeckPresetState = DeckPresetState(),
    localReviews: List<ImmutableReviewExport> = emptyList(),
): DeckExportFile = exportKelmaJson(
    deckName = normalizeDeckName(deckName),
    optionsByDeck = optionsByDeck,
    exportedAtMillis = exportedAtMillis,
    includeScheduling = includeScheduling,
    includeDeckOptions = includeDeckOptions,
    includeMedia = includeMedia,
    presets = presets,
    localReviews = localReviews,
)

fun SyncedCollection.exportCollection(
    optionsByDeck: Map<String, DeckOptions> = emptyMap(),
    schedules: Map<Long, LocalCardSchedule> = emptyMap(),
    exportedAtMillis: Long = currentEpochMillis(),
    includeScheduling: Boolean = true,
    includeDeckOptions: Boolean = true,
    includeMedia: Boolean = true,
    presets: DeckPresetState = DeckPresetState(),
    localReviews: List<ImmutableReviewExport> = emptyList(),
): DeckExportFile = exportKelmaJson(
    deckName = null,
    optionsByDeck = optionsByDeck,
    exportedAtMillis = exportedAtMillis,
    includeScheduling = includeScheduling,
    includeDeckOptions = includeDeckOptions,
    includeMedia = includeMedia,
    presets = presets,
    localReviews = localReviews,
)

private fun SyncedCollection.exportKelmaJson(
    deckName: String?,
    optionsByDeck: Map<String, DeckOptions>,
    exportedAtMillis: Long,
    includeScheduling: Boolean,
    includeDeckOptions: Boolean,
    includeMedia: Boolean,
    presets: DeckPresetState,
    localReviews: List<ImmutableReviewExport>,
): DeckExportFile {
    val selectedCards = cards.values
        .filter { deckName == null || it.deckName.isDeckOrDescendantOf(deckName) }
        .sortedBy(SyncCard::cardId)
    require(deckName == null || selectedCards.isNotEmpty() || deckNames.any { it.isDeckOrDescendantOf(deckName) }) {
        "This deck no longer exists"
    }
    val selectedDeckNames = if (deckName == null) {
        (deckNames + selectedCards.map(SyncCard::deckName)).flatMap(::deckHierarchyNames).toSet()
    } else {
        (deckNames.filter { it.isDeckOrDescendantOf(deckName) } + selectedCards.map(SyncCard::deckName) + deckName)
            .flatMap(::deckHierarchyNames)
            .filter { it.isDeckOrDescendantOf(deckName) }
            .toSet()
    }
    val noteGuids = selectedCards.mapTo(mutableSetOf(), SyncCard::noteGuid)
    val selectedNotes = if (deckName == null) notes.values.sortedBy(SyncNote::guid) else {
        notes.values.filter { it.guid in noteGuids }.sortedBy(SyncNote::guid)
    }
    val notetypeIds = selectedNotes.mapTo(mutableSetOf(), SyncNote::notetypeId)
    val selectedNotetypes = if (deckName == null) notetypes.values.sortedBy(SyncNotetype::notetypeId) else {
        notetypes.values.filter { it.notetypeId in notetypeIds }.sortedBy(SyncNotetype::notetypeId)
    }
    val mediaSources = selectedNotes.flatMap(SyncNote::fields) + selectedNotetypes.map { it.definition.toString() }
    val selectedMedia = if (includeMedia) {
        media.values.filter { file -> deckName == null || mediaSources.any { file.filename in it } }
            .sortedBy(SyncMediaFile::filename)
            .map { KelmaExportMedia(it.filename, it.modifiedAt, encodeBase64(it.bytes)) }
    } else {
        emptyList()
    }
    val selectedOptions = if (includeDeckOptions) {
        selectedDeckNames.sorted().associateWith { name ->
            (optionsByDeck[name] ?: DeckOptions()).validated()
        }
    } else {
        emptyMap()
    }
    val selectedAssignments = if (includeDeckOptions) {
        presets.assignments.filterKeys { assigned -> selectedDeckNames.any { it.equals(assigned, ignoreCase = true) } }
    } else {
        emptyMap()
    }
    val selectedPresetIds = selectedAssignments.values.toSet()
    val selectedPresets = when {
        !includeDeckOptions -> emptyList()
        deckName == null -> presets.presets.sortedBy(DeckOptionsPreset::id)
        else -> presets.presets.filter { it.id in selectedPresetIds }.sortedBy(DeckOptionsPreset::id)
    }
    val cardIdentities = selectedCards.associateBy { portableExportCardIdentity(it.noteGuid, it.ord) }
    val downloadedReviews = reviews.values.mapNotNull { review ->
        val rating = Rating.entries.getOrNull(review.ease - 1) ?: return@mapNotNull null
        if (portableExportCardIdentity(review.noteGuid, review.cardOrd) !in cardIdentities) return@mapNotNull null
        KelmaJsonReview(
            review.reviewId,
            review.noteGuid,
            review.cardOrd,
            rating.name,
            review.takenMillis.toLong(),
        )
    }
    val pendingReviews = localReviews.mapNotNull { review ->
        if (portableExportCardIdentity(review.noteGuid, review.cardOrdinal) !in cardIdentities) return@mapNotNull null
        KelmaJsonReview(
            review.reviewId,
            review.noteGuid,
            review.cardOrdinal,
            review.rating.name,
            review.durationMillis,
        )
    }
    val export = KelmaJsonExport(
        scope = if (deckName == null) "collection" else "deck",
        deckName = deckName,
        exportedAtMillis = exportedAtMillis,
        deckNames = selectedDeckNames.sorted(),
        deckOptions = selectedOptions,
        presets = selectedPresets,
        presetAssignments = selectedAssignments,
        notes = selectedNotes,
        cards = selectedCards.map { it.copy(scheduling = JsonObject(emptyMap())) },
        notetypes = selectedNotetypes,
        reviews = if (includeScheduling) {
            (downloadedReviews + pendingReviews).distinctBy(KelmaJsonReview::reviewId).sortedBy(KelmaJsonReview::reviewId)
        } else {
            emptyList()
        },
        media = selectedMedia,
    )
    return DeckExportFile(
        suggestedName = (deckName?.substringAfterLast("::") ?: "collection").safeExportFilename() + ".kelma.json",
        content = ExportJson.encodeToString(export),
    )
}

private fun portableExportCardIdentity(guid: String, ordinal: Int): String = "$guid\u0000$ordinal"

private fun String.safeExportFilename(): String {
    val sanitized = map { character ->
        if (character.isLetterOrDigit() || character in " ._-") character else '_'
    }.joinToString("").trim().trim('.')
    return sanitized.ifEmpty { "deck" }
}

private fun encodeBase64(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    return buildString((bytes.size + 2) / 3 * 4) {
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xff
            val second = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
            val third = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
            append(alphabet[first ushr 2])
            append(alphabet[((first and 3) shl 4) or if (second >= 0) second ushr 4 else 0])
            append(if (second >= 0) alphabet[((second and 15) shl 2) or if (third >= 0) third ushr 6 else 0] else '=')
            append(if (third >= 0) alphabet[third and 63] else '=')
        }
    }
}
