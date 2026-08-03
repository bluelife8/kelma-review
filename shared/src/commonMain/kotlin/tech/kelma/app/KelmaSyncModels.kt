package tech.kelma.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

const val DefaultKelmaSyncEndpoint = "https://sync2.kelma.tech"

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    @SerialName("client_label") val clientLabel: String,
)

@Serializable
data class LoginResponse(
    val token: String,
    @SerialName("client_id") val clientId: String,
)

@Serializable
data class SyncError(
    val error: String = "request_failed",
    val message: String = "",
)

@Serializable
data class SyncManifest(
    val notes: List<ManifestEntry> = emptyList(),
    val cards: List<ManifestEntry> = emptyList(),
    val reviews: List<ManifestEntry> = emptyList(),
    @SerialName("study_days") val studyDays: List<SyncStudyDay> = emptyList(),
    val notetypes: List<ManifestEntry> = emptyList(),
    val decks: List<ManifestEntry> = emptyList(),
    val media: List<ManifestEntry> = emptyList(),
    val tombstones: List<SyncTombstone> = emptyList(),
    @SerialName("server_time") val serverTime: String,
)

@Serializable
data class ManifestEntry(
    val guid: String = "",
    @SerialName("card_id") val cardId: Long = 0,
    @SerialName("review_id") val reviewId: Long = 0,
    @SerialName("notetype_id") val notetypeId: Long = 0,
    val name: String = "",
    val filename: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("modified_at") val modifiedAt: String = "",
    @SerialName("study_state") val studyState: CardStudyState = CardStudyState.Active,
    @SerialName("study_state_modified_at") val studyStateModifiedAt: String = "",
    @SerialName("study_state_client_modified_at") val studyStateClientModifiedAt: String = "",
    @SerialName("schedule_reset_through_review_id") val scheduleResetThroughReviewId: Long = 0,
    @SerialName("schedule_reset_modified_at") val scheduleResetModifiedAt: String = "",
    @SerialName("schedule_reset_client_modified_at") val scheduleResetClientModifiedAt: String = "",
    @SerialName("due_date_override_ms") val dueDateOverrideMillis: Long = 0,
    @SerialName("due_date_override_modified_at") val dueDateOverrideModifiedAt: String = "",
    @SerialName("due_date_override_client_modified_at") val dueDateOverrideClientModifiedAt: String = "",
)

@Serializable
data class SyncTombstone(
    val type: String,
    @SerialName("resource_id") val resourceId: String,
)

@Serializable
internal data class MediaFilenamesRequest(
    val filenames: List<String>,
)

@Serializable
internal data class PreparedMediaTarResponse(
    @SerialName("job_id") val jobId: String,
    @SerialName("archive_bytes") val archiveBytes: Long,
    @SerialName("payload_bytes") val payloadBytes: Long,
    val files: Int,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class BatchPullRequest(
    val notes: List<String> = emptyList(),
    val cards: List<Long> = emptyList(),
    val reviews: List<Long> = emptyList(),
    val notetypes: List<Long> = emptyList(),
    val decks: List<String> = emptyList(),
)

@Serializable
data class BatchPullResponse(
    val notes: List<SyncNote> = emptyList(),
    val cards: List<SyncCard> = emptyList(),
    val reviews: List<SyncReview> = emptyList(),
    val notetypes: List<SyncNotetype> = emptyList(),
    val decks: List<SyncDeck> = emptyList(),
)

@Serializable
data class SyncNote(
    val guid: String,
    @SerialName("notetype_id") val notetypeId: Long = 0,
    val fields: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val checksum: String = "",
    @SerialName("modified_at") val modifiedAt: String = "",
    @SerialName("client_modified_at") val clientModifiedAt: String = "",
)

@Serializable
data class SyncCard(
    @SerialName("card_id") val cardId: Long,
    @SerialName("note_guid") val noteGuid: String,
    @SerialName("deck_name") val deckName: String,
    val ord: Int = 0,
    /** Opaque source payload; only an explicit New-card position may affect New gather order. */
    val scheduling: JsonObject = JsonObject(emptyMap()),
    @SerialName("study_state") val studyState: CardStudyState = CardStudyState.Active,
    @SerialName("study_state_modified_at") val studyStateModifiedAt: String = "",
    @SerialName("study_state_client_modified_at") val studyStateClientModifiedAt: String = "",
    @SerialName("schedule_reset_through_review_id") val scheduleResetThroughReviewId: Long = 0,
    @SerialName("schedule_reset_modified_at") val scheduleResetModifiedAt: String = "",
    @SerialName("schedule_reset_client_modified_at") val scheduleResetClientModifiedAt: String = "",
    @SerialName("due_date_override_ms") val dueDateOverrideMillis: Long = 0,
    @SerialName("due_date_override_modified_at") val dueDateOverrideModifiedAt: String = "",
    @SerialName("due_date_override_client_modified_at") val dueDateOverrideClientModifiedAt: String = "",
    @SerialName("modified_at") val modifiedAt: String = "",
    @SerialName("client_modified_at") val clientModifiedAt: String = "",
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * Anki stores a New card's gather position in `due`. It is not a due date and
 * is safe to use only when the source explicitly identifies the card as New.
 */
internal val SyncCard.synchronizedNewPosition: Long?
    get() {
        if (scheduling["type"]?.jsonPrimitive?.intOrNull != 0) return null
        return scheduling["due"]?.jsonPrimitive?.longOrNull?.takeIf { it >= 0L }
    }

@Serializable
data class SyncReview(
    @SerialName("review_id") val reviewId: Long,
    @SerialName("source_card_id") val sourceCardId: Long = 0,
    @SerialName("note_guid") val noteGuid: String = "",
    @SerialName("card_ord") val cardOrd: Int = 0,
    @SerialName("deck_name") val deckName: String = "",
    val ease: Int = 0,
    val interval: Int = 0,
    @SerialName("last_interval") val lastInterval: Int = 0,
    val factor: Int = 0,
    @SerialName("taken_millis") val takenMillis: Int = 0,
    @SerialName("review_kind") val reviewKind: Int = 0,
    val checksum: String = "",
    @SerialName("modified_at") val modifiedAt: String = "",
)

@Serializable
data class SyncStudyDay(
    val day: Long,
    @SerialName("deck_name") val deckName: String,
    @SerialName("new_studied") val newStudied: Int = 0,
    @SerialName("review_studied") val reviewStudied: Int = 0,
    @SerialName("learning_studied") val learningStudied: Int = 0,
    @SerialName("milliseconds_studied") val millisecondsStudied: Long = 0,
    @SerialName("modified_at") val modifiedAt: String = "",
)

@Serializable
data class SyncNotetype(
    @SerialName("notetype_id") val notetypeId: Long,
    val name: String,
    val definition: JsonObject = JsonObject(emptyMap()),
    val checksum: String = "",
    @SerialName("modified_at") val modifiedAt: String = "",
    @SerialName("client_modified_at") val clientModifiedAt: String = "",
)

@Serializable
data class SyncDeck(
    val name: String,
    val config: JsonObject = JsonObject(emptyMap()),
    val checksum: String = "",
    @SerialName("modified_at") val modifiedAt: String = "",
    @SerialName("client_modified_at") val clientModifiedAt: String = "",
)

data class SyncMediaFile(
    val filename: String,
    val modifiedAt: String,
    val bytes: ByteArray,
    val sizeBytes: Long = bytes.size.toLong(),
)

data class PullReport(
    val collection: SyncedCollection,
    val downloaded: Int,
    val removed: Int,
    val remoteMediaMissing: Set<String> = emptySet(),
    val downloadedMediaFilenames: Set<String> = emptySet(),
)

data class SyncedCollection(
    val notes: Map<String, SyncNote> = emptyMap(),
    val cards: Map<Long, SyncCard> = emptyMap(),
    val reviews: Map<Long, SyncReview> = emptyMap(),
    val studyDays: Map<String, SyncStudyDay> = emptyMap(),
    val notetypes: Map<Long, SyncNotetype> = emptyMap(),
    val deckRecords: Map<String, SyncDeck> = emptyMap(),
    val media: Map<String, SyncMediaFile> = emptyMap(),
    val deckNames: Set<String> = emptySet(),
    val serverTime: String? = null,
) {
    fun apply(
        manifest: SyncManifest,
        pulled: BatchPullResponse,
        downloadedMedia: Map<String, SyncMediaFile> = emptyMap(),
        remoteMediaMissing: Set<String> = emptySet(),
    ): PullReport {
        val nextNotes = notes.toMutableMap()
        val nextCards = cards.toMutableMap()
        val nextReviews = reviews.toMutableMap()
        val nextStudyDays = studyDays.toMutableMap()
        val nextNotetypes = notetypes.toMutableMap()
        val nextDeckRecords = deckRecords.toMutableMap()
        val nextMedia = media.toMutableMap()
        val nextDeckNames = deckNames.toMutableSet()
        var removed = 0

        manifest.tombstones.forEach { tombstone ->
            when (tombstone.type) {
                "note" -> {
                    if (nextNotes.remove(tombstone.resourceId) != null) removed++
                    val cardIds = nextCards.values.filter { it.noteGuid == tombstone.resourceId }.map { it.cardId }
                    cardIds.forEach { if (nextCards.remove(it) != null) removed++ }
                }
                "card" -> tombstone.resourceId.toLongOrNull()?.let {
                    if (nextCards.remove(it) != null) removed++
                }
                "notetype" -> tombstone.resourceId.toLongOrNull()?.let {
                    if (nextNotetypes.remove(it) != null) removed++
                }
                "deck" -> {
                    if (nextDeckRecords.remove(tombstone.resourceId) != null) removed++
                    nextDeckNames.remove(tombstone.resourceId)
                }
                "media" -> if (nextMedia.remove(tombstone.resourceId) != null) removed++
            }
        }

        pulled.notes.forEach { nextNotes[it.guid] = it }
        pulled.cards.forEach {
            nextCards[it.cardId] = it
            nextDeckNames += it.deckName
        }
        pulled.reviews.forEach { nextReviews[it.reviewId] = it }
        manifest.studyDays.forEach { nextStudyDays[it.key()] = it }
        pulled.notetypes.forEach { nextNotetypes[it.notetypeId] = it }
        pulled.decks.forEach {
            nextDeckRecords[it.name] = it
            nextDeckNames += it.name
        }
        nextMedia.putAll(downloadedMedia)

        val next = copy(
            notes = nextNotes,
            cards = nextCards,
            reviews = nextReviews,
            studyDays = nextStudyDays,
            notetypes = nextNotetypes,
            deckRecords = nextDeckRecords,
            media = nextMedia,
            deckNames = nextDeckNames,
            serverTime = manifest.serverTime,
        )
        return PullReport(
            collection = next,
            downloaded = pulled.notes.size + pulled.cards.size + pulled.reviews.size +
                manifest.studyDays.size + pulled.notetypes.size + pulled.decks.size + downloadedMedia.size,
            removed = removed,
            remoteMediaMissing = remoteMediaMissing,
            downloadedMediaFilenames = downloadedMedia.keys,
        )
    }

    fun asDecks(
        localSchedules: Map<Long, LocalCardSchedule> = emptyMap(),
        nowMillis: Long = currentEpochMillis(),
        deckOptions: Map<String, DeckOptions> = emptyMap(),
        studiedTodayByDeck: Map<String, DeckStudyCounts> = emptyMap(),
        studiedCardOrdsByNoteToday: Map<String, Set<Int>> = emptyMap(),
        buriedCardIds: Set<Long> = emptySet(),
        buriedNoteGuids: Set<String> = emptySet(),
        dueDateOverrides: Map<Long, Long> = emptyMap(),
        studyDayPolicy: AccountStudyDayPolicy = AccountStudyDayPolicy(dayStartHour = 0),
    ): List<DeckSummary> = projectDecks(
        localSchedules,
        nowMillis,
        deckOptions,
        studiedTodayByDeck,
        studiedCardOrdsByNoteToday,
        buriedCardIds,
        buriedNoteGuids,
        dueDateOverrides,
        studyDayPolicy,
        loadQueues = true,
    )

    internal fun asDeckList(
        localSchedules: Map<Long, LocalCardSchedule> = emptyMap(),
        nowMillis: Long = currentEpochMillis(),
        deckOptions: Map<String, DeckOptions> = emptyMap(),
        studiedTodayByDeck: Map<String, DeckStudyCounts> = emptyMap(),
        studiedCardOrdsByNoteToday: Map<String, Set<Int>> = emptyMap(),
        buriedCardIds: Set<Long> = emptySet(),
        buriedNoteGuids: Set<String> = emptySet(),
        dueDateOverrides: Map<Long, Long> = emptyMap(),
        studyDayPolicy: AccountStudyDayPolicy = AccountStudyDayPolicy(dayStartHour = 0),
    ): List<DeckSummary> = projectDecks(
        localSchedules,
        nowMillis,
        deckOptions,
        studiedTodayByDeck,
        studiedCardOrdsByNoteToday,
        buriedCardIds,
        buriedNoteGuids,
        dueDateOverrides,
        studyDayPolicy,
        loadQueues = false,
    )

    private fun projectDecks(
        localSchedules: Map<Long, LocalCardSchedule>,
        nowMillis: Long,
        deckOptions: Map<String, DeckOptions>,
        studiedTodayByDeck: Map<String, DeckStudyCounts>,
        studiedCardOrdsByNoteToday: Map<String, Set<Int>>,
        buriedCardIds: Set<Long>,
        buriedNoteGuids: Set<String>,
        dueDateOverrides: Map<Long, Long>,
        studyDayPolicy: AccountStudyDayPolicy,
        loadQueues: Boolean,
    ): List<DeckSummary> {
        val allCards = cards.values.toList()
        val knownDecks = (deckNames + deckRecords.keys + allCards.map(SyncCard::deckName))
            .flatMap(::deckHierarchyNames)
            .toSet()
        return knownDecks.sortedWith(String.CASE_INSENSITIVE_ORDER).map { name ->
            projectDeckCards(
                name = name,
                deckCards = allCards.filter { it.deckName.isDeckOrDescendantOf(name) },
                localSchedules = localSchedules,
                nowMillis = nowMillis,
                deckOptions = deckOptions,
                studiedTodayByDeck = studiedTodayByDeck,
                studiedCardOrdsByNoteToday = studiedCardOrdsByNoteToday,
                buriedCardIds = buriedCardIds,
                buriedNoteGuids = buriedNoteGuids,
                dueDateOverrides = dueDateOverrides,
                studyDayPolicy = studyDayPolicy,
                loadQueue = loadQueues,
            )
        }
    }

    internal fun projectDeckCards(
        name: String,
        deckCards: List<SyncCard>,
        localSchedules: Map<Long, LocalCardSchedule>,
        nowMillis: Long,
        deckOptions: Map<String, DeckOptions>,
        studiedTodayByDeck: Map<String, DeckStudyCounts>,
        studiedCardOrdsByNoteToday: Map<String, Set<Int>>,
        buriedCardIds: Set<Long>,
        buriedNoteGuids: Set<String>,
        dueDateOverrides: Map<Long, Long>,
        studyDayPolicy: AccountStudyDayPolicy,
        loadQueue: Boolean,
    ): DeckSummary {
        val options = effectiveDeckOptions(name, deckOptions)
        val eligibleCards = deckCards.filter {
            notes.containsKey(it.noteGuid) &&
                it.studyState == CardStudyState.Active &&
                it.cardId !in buriedCardIds &&
                it.noteGuid !in buriedNoteGuids
        }
        val dueCards = buildDeckQueue(
            cards = eligibleCards,
            localSchedules = localSchedules,
            options = options,
            remainingNew = options.newCardsPerDay,
            remainingReviews = options.maximumReviewsPerDay,
            nowMillis = nowMillis,
            dailyLimitPlan = dailyLimitPlan(name, eligibleCards, deckOptions, studiedTodayByDeck),
            studiedCardOrdsByNoteToday = studiedCardOrdsByNoteToday,
            dueDateOverrides = dueDateOverrides,
            studyDayPolicy = studyDayPolicy,
        )
        val newCount = dueCards.count { it.cardId !in localSchedules }
        val learningCount = dueCards.count { card ->
            localSchedules[card.cardId]?.phase in setOf(ReviewPhase.Learning, ReviewPhase.Relearning)
        }
        return DeckSummary(
            id = name,
            name = name,
            cards = if (loadQueue) dueCards.mapNotNull { reviewCard(it.cardId) } else emptyList(),
            newCount = newCount,
            learningCount = learningCount,
            dueCount = (dueCards.size - newCount - learningCount).coerceAtLeast(0),
            queueLoaded = loadQueue,
        )
    }

    internal fun asDeck(
        name: String,
        localSchedules: Map<Long, LocalCardSchedule> = emptyMap(),
        nowMillis: Long = currentEpochMillis(),
        deckOptions: Map<String, DeckOptions> = emptyMap(),
        studiedTodayByDeck: Map<String, DeckStudyCounts> = emptyMap(),
        studiedCardOrdsByNoteToday: Map<String, Set<Int>> = emptyMap(),
        buriedCardIds: Set<Long> = emptySet(),
        buriedNoteGuids: Set<String> = emptySet(),
        dueDateOverrides: Map<Long, Long> = emptyMap(),
        studyDayPolicy: AccountStudyDayPolicy = AccountStudyDayPolicy(dayStartHour = 0),
    ): DeckSummary? {
        val scopedCards = cards.values.filter { it.deckName.isDeckOrDescendantOf(name) }
        val knownDeck = name in deckNames || name in deckRecords || scopedCards.isNotEmpty()
        if (!knownDeck) return null
        return projectDeckCards(
            name = name,
            deckCards = scopedCards,
            localSchedules = localSchedules,
            nowMillis = nowMillis,
            deckOptions = deckOptions,
            studiedTodayByDeck = studiedTodayByDeck,
            studiedCardOrdsByNoteToday = studiedCardOrdsByNoteToday,
            buriedCardIds = buriedCardIds,
            buriedNoteGuids = buriedNoteGuids,
            dueDateOverrides = dueDateOverrides,
            studyDayPolicy = studyDayPolicy,
            loadQueue = true,
        )
    }

    fun reviewCard(cardId: Long): ReviewCard? {
        val card = cards[cardId] ?: return null
        val note = notes[card.noteGuid] ?: return null
        return CardTemplateRenderer.render(card, note, notetypes[note.notetypeId], media)
    }
}

private fun SyncStudyDay.key(): String = "$day\u0000$deckName"
