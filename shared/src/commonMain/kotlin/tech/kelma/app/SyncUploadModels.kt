package tech.kelma.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NotePushBody(
    @SerialName("notetype_id") val notetypeId: Long,
    val fields: List<String>,
    val tags: List<String>,
    @SerialName("client_modified_at") val clientModifiedAt: String,
    @SerialName("base_checksum") val baseChecksum: String,
)

@Serializable
data class CardPushBody(
    @SerialName("note_guid") val noteGuid: String,
    @SerialName("deck_name") val deckName: String,
    val ord: Int,
    val scheduling: JsonObject,
    @SerialName("client_modified_at") val clientModifiedAt: String,
    @SerialName("study_state") val studyState: CardStudyState? = null,
    @SerialName("study_state_client_modified_at") val studyStateClientModifiedAt: String? = null,
    @SerialName("schedule_reset_through_review_id") val scheduleResetThroughReviewId: Long? = null,
    @SerialName("schedule_reset_client_modified_at") val scheduleResetClientModifiedAt: String? = null,
    @SerialName("due_date_override_ms") val dueDateOverrideMillis: Long? = null,
    @SerialName("due_date_override_client_modified_at") val dueDateOverrideClientModifiedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class NotetypePushBody(
    val name: String,
    val definition: JsonObject,
    @SerialName("client_modified_at") val clientModifiedAt: String,
    @SerialName("base_checksum") val baseChecksum: String = "",
)

@Serializable
data class DeckPushBody(
    val config: JsonObject,
    @SerialName("client_modified_at") val clientModifiedAt: String,
    @SerialName("base_checksum") val baseChecksum: String,
)

/** Immutable review fact plus Anki-compatible origin metadata; interval/factor are not receiving-client state. */
@Serializable
data class ReviewPushBody(
    @SerialName("review_id") val reviewId: Long,
    @SerialName("source_card_id") val sourceCardId: Long,
    @SerialName("note_guid") val noteGuid: String,
    @SerialName("card_ord") val cardOrd: Int,
    @SerialName("deck_name") val deckName: String,
    val ease: Int,
    val interval: Int,
    @SerialName("last_interval") val lastInterval: Int,
    val factor: Int,
    @SerialName("taken_millis") val takenMillis: Int,
    @SerialName("review_kind") val reviewKind: Int,
)

@Serializable
data class BatchReviewPushRequest(
    val reviews: List<ReviewPushBody> = emptyList(),
)

@Serializable
data class BatchNotePushItem(
    val guid: String,
    @SerialName("notetype_id") val notetypeId: Long,
    val fields: List<String>,
    val tags: List<String>,
    @SerialName("client_modified_at") val clientModifiedAt: String,
    @SerialName("base_checksum") val baseChecksum: String,
)

@Serializable
data class BatchCardPushItem(
    @SerialName("card_id") val cardId: Long,
    @SerialName("note_guid") val noteGuid: String,
    @SerialName("deck_name") val deckName: String,
    val ord: Int,
    val scheduling: JsonObject,
    @SerialName("client_modified_at") val clientModifiedAt: String,
    @SerialName("study_state") val studyState: CardStudyState? = null,
    @SerialName("study_state_client_modified_at") val studyStateClientModifiedAt: String? = null,
    @SerialName("schedule_reset_through_review_id") val scheduleResetThroughReviewId: Long? = null,
    @SerialName("schedule_reset_client_modified_at") val scheduleResetClientModifiedAt: String? = null,
    @SerialName("due_date_override_ms") val dueDateOverrideMillis: Long? = null,
    @SerialName("due_date_override_client_modified_at") val dueDateOverrideClientModifiedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class BatchNotetypePushItem(
    @SerialName("notetype_id") val notetypeId: Long,
    val name: String,
    val definition: JsonObject,
    @SerialName("client_modified_at") val clientModifiedAt: String,
    @SerialName("base_checksum") val baseChecksum: String,
)

@Serializable
data class BatchDeckPushItem(
    val name: String,
    val config: JsonObject,
    @SerialName("client_modified_at") val clientModifiedAt: String,
    @SerialName("base_checksum") val baseChecksum: String,
)

@Serializable
data class BatchPushRequest(
    val notes: List<BatchNotePushItem> = emptyList(),
    val cards: List<BatchCardPushItem> = emptyList(),
    val reviews: List<ReviewPushBody> = emptyList(),
    val notetypes: List<BatchNotetypePushItem> = emptyList(),
    val decks: List<BatchDeckPushItem> = emptyList(),
)

@Serializable
data class BatchPushResponse(
    val accepted: Map<String, Int> = emptyMap(),
    val conflicts: Map<String, List<SyncPushConflictEntry>> = emptyMap(),
)

@Serializable
data class SyncPushConflictEntry(
    val guid: String = "",
    @SerialName("review_id") val reviewId: Long = 0,
    @SerialName("notetype_id") val notetypeId: Long = 0,
    val name: String = "",
    val server: JsonObject = JsonObject(emptyMap()),
    val client: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class BatchDeleteRequest(
    val notes: List<String> = emptyList(),
    val cards: List<Long> = emptyList(),
    val notetypes: List<Long> = emptyList(),
    val decks: List<String> = emptyList(),
)

@Serializable
data class BatchDeleteResponse(
    val requested: Map<String, Int> = emptyMap(),
    val deleted: Map<String, Int> = emptyMap(),
)

data class PendingNoteUpload(
    val guid: String,
    val operation: String,
    val body: NotePushBody?,
    val notetype: Pair<Long, NotetypePushBody>?,
    val deck: Pair<String, DeckPushBody>?,
    val cards: List<Pair<Long, CardPushBody>>,
    val deleteRequest: BatchDeleteRequest? = null,
    val forceOverride: Boolean,
)

data class PendingDeckUpload(
    val sourceName: String,
    val operation: String,
    val targetName: String?,
    val targetBody: DeckPushBody?,
    val additionalDecks: List<Pair<String, DeckPushBody>> = emptyList(),
    val cards: List<Pair<Long, CardPushBody>>,
    val deleteRequest: BatchDeleteRequest?,
    val forceOverride: Boolean,
)

data class PendingMediaUpload(
    val filename: String,
    val mimeType: String,
    val checksum: String,
    val bytes: ByteArray,
)

enum class SyncPushResource(val phase: String, val label: String) {
    Reviews("REVIEWS", "reviews"),
    Dependencies("DEPENDENCIES", "dependencies"),
    Notes("NOTES", "notes"),
    Cards("CARDS", "cards"),
    Decks("DECKS", "deck changes"),
    Media("MEDIA", "media files"),
    SchedulerProfile("SCHEDULER PROFILE", "scheduler profile"),
}

data class SyncPushProgress(
    val resource: SyncPushResource,
    val completed: Int,
    val total: Int,
    val accepted: Int = completed,
    val conflicts: Int = 0,
)

data class PendingCardStudyUpload(
    val key: String,
    val cardId: Long,
    val body: CardPushBody,
)

data class PendingCardResetUpload(
    val key: String,
    val cardId: Long,
    val body: CardPushBody,
)

data class PendingCardDueDateUpload(
    val key: String,
    val cardId: Long,
    val body: CardPushBody,
)

data class SyncUploadPlan(
    val reviews: List<ReviewPushBody> = emptyList(),
    val cardStudyStates: List<PendingCardStudyUpload> = emptyList(),
    val cardScheduleResets: List<PendingCardResetUpload> = emptyList(),
    val cardDueDates: List<PendingCardDueDateUpload> = emptyList(),
    val notes: List<PendingNoteUpload> = emptyList(),
    val decks: List<PendingDeckUpload> = emptyList(),
    val media: List<PendingMediaUpload> = emptyList(),
    val schedulerProfile: SchedulerProfileCandidate? = null,
) {
    val isEmpty: Boolean
        get() = reviews.isEmpty() && cardStudyStates.isEmpty() && cardScheduleResets.isEmpty() &&
            cardDueDates.isEmpty() && notes.isEmpty() && decks.isEmpty() && media.isEmpty() && schedulerProfile == null
}

data class SyncUploadConflict(
    val kind: String,
    val resourceKey: String,
    val serverJson: String,
)

data class SyncPushResult(
    val uploadedReviewIds: Set<Long> = emptySet(),
    val uploadedCardStudyKeys: Set<String> = emptySet(),
    val uploadedCardResetKeys: Set<String> = emptySet(),
    val uploadedCardDueDateKeys: Set<String> = emptySet(),
    val uploadedNoteGuids: Set<String> = emptySet(),
    val uploadedDeckSources: Set<String> = emptySet(),
    val uploadedMediaFilenames: Set<String> = emptySet(),
    val acknowledgedSchedulerProfile: SchedulerProfileResponse? = null,
    val conflicts: List<SyncUploadConflict> = emptyList(),
) {
    val uploadedCount: Int
        get() = uploadedReviewIds.size + uploadedCardStudyKeys.size + uploadedCardResetKeys.size +
            uploadedCardDueDateKeys.size + uploadedNoteGuids.size + uploadedDeckSources.size + uploadedMediaFilenames.size +
            if (acknowledgedSchedulerProfile == null) 0 else 1
}
