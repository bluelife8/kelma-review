package tech.kelma.app

import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.Serializable

@Serializable
enum class ReviewPhase {
    Learning,
    Review,
    Relearning,
}

@Serializable
data class LocalCardSchedule(
    val cardId: Long,
    val phase: ReviewPhase,
    val dueAtMillis: Long,
    val stability: Double,
    val difficulty: Double,
    val scheduledDays: Int,
    val repetitions: Int,
    val lapses: Int,
    val lastReviewAtMillis: Long,
    val step: Int? = null,
)

/** Daily cards admitted from New and Review queues; repeated learning answers do not increment these values. */
data class DeckStudyCounts(val newCards: Int = 0, val reviews: Int = 0)

internal class DeckProjectionToken

internal data class DeckProjectionMutationHint(
    val previousToken: DeckProjectionToken,
    val cardIds: Set<Long> = emptySet(),
    val noteGuids: Set<String> = emptySet(),
    val deckNames: Set<String> = emptySet(),
)

data class LocalReviewSnapshot(
    val revision: Long = 0,
    val schedules: Map<Long, LocalCardSchedule> = emptyMap(),
    val dueDateOverrides: Map<Long, Long> = emptyMap(),
    val buriedCardIds: Set<Long> = emptySet(),
    val buriedNoteGuids: Set<String> = emptySet(),
    val reviewedToday: Int = 0,
    val studiedTodayByDeck: Map<String, DeckStudyCounts> = emptyMap(),
    val studiedCardOrdsByNoteToday: Map<String, Set<Int>> = emptyMap(),
    val reviewLimitConsumedCardKeysToday: Set<String> = emptySet(),
    val lastReviewDeck: String? = null,
    val pendingSyncByDeck: Map<String, PendingDeckChanges> = emptyMap(),
    val studyDay: Long? = null,
    val studyDayPolicy: AccountStudyDayPolicy = AccountStudyDayPolicy(dayStartHour = 0),
) {
    internal val deckProjectionToken = DeckProjectionToken()
    internal var deckProjectionMutationHint: DeckProjectionMutationHint? = null
        private set

    val canUndo: Boolean
        get() = lastReviewDeck != null

    internal fun markDeckProjectionMutation(
        previous: LocalReviewSnapshot,
        cardIds: Set<Long> = emptySet(),
        noteGuids: Set<String> = emptySet(),
        deckNames: Set<String> = emptySet(),
    ): LocalReviewSnapshot = apply {
        deckProjectionMutationHint = DeckProjectionMutationHint(
            previousToken = previous.deckProjectionToken,
            cardIds = cardIds,
            noteGuids = noteGuids,
            deckNames = deckNames,
        )
    }
}

data class LocalReviewChange(
    val schedule: LocalCardSchedule,
    val snapshot: LocalReviewSnapshot,
)

internal data class RecordedReviewDelta(
    val schedule: LocalCardSchedule,
    val noteGuid: String,
    val cardOrd: Int,
    val deckName: String,
    val reviewedAtMillis: Long,
    val wasNew: Boolean,
    val consumedReviewLimit: Boolean = false,
    val clearedDueDateOverride: Boolean,
    val pendingDownloadedCardId: Long?,
)

internal fun LocalReviewSnapshot.applying(
    delta: RecordedReviewDelta,
    policy: AccountStudyDayPolicy = studyDayPolicy,
): LocalReviewSnapshot {
    require(studyDay == studyDayAt(delta.reviewedAtMillis, policy)) {
        "Review snapshot belongs to a different study day"
    }
    val counts = studiedTodayByDeck[delta.deckName] ?: DeckStudyCounts()
    val reviewCardKey = reviewLimitCardKey(delta.noteGuid, delta.cardOrd, delta.schedule.cardId)
    val newlyConsumedReviewLimit = delta.consumedReviewLimit &&
        reviewCardKey !in reviewLimitConsumedCardKeysToday
    val nextCounts = when {
        delta.wasNew -> counts.copy(newCards = counts.newCards + 1)
        newlyConsumedReviewLimit -> counts.copy(reviews = counts.reviews + 1)
        else -> counts
    }
    val studiedOrds = studiedCardOrdsByNoteToday[delta.noteGuid].orEmpty() + delta.cardOrd
    val pending = delta.pendingDownloadedCardId?.let { cardId ->
        val existing = pendingSyncByDeck[delta.deckName] ?: PendingDeckChanges()
        pendingSyncByDeck + (
            delta.deckName to existing.copy(changedCardIds = existing.changedCardIds + cardId)
        )
    } ?: pendingSyncByDeck
    return copy(
        revision = delta.schedule.lastReviewAtMillis,
        schedules = schedules.toPersistentMap().put(delta.schedule.cardId, delta.schedule),
        dueDateOverrides = if (delta.clearedDueDateOverride) {
            dueDateOverrides - delta.schedule.cardId
        } else {
            dueDateOverrides
        },
        reviewedToday = reviewedToday + 1,
        studiedTodayByDeck = studiedTodayByDeck + (delta.deckName to nextCounts),
        reviewLimitConsumedCardKeysToday = if (newlyConsumedReviewLimit) {
            reviewLimitConsumedCardKeysToday + reviewCardKey
        } else {
            reviewLimitConsumedCardKeysToday
        },
        studiedCardOrdsByNoteToday = if (delta.noteGuid.isBlank()) {
            studiedCardOrdsByNoteToday
        } else {
            studiedCardOrdsByNoteToday + (delta.noteGuid to studiedOrds)
        },
        lastReviewDeck = delta.deckName,
        pendingSyncByDeck = pending,
    ).markDeckProjectionMutation(
        previous = this,
        cardIds = setOf(delta.schedule.cardId),
        noteGuids = delta.noteGuid.takeIf(String::isNotBlank)?.let(::setOf).orEmpty(),
        deckNames = setOf(delta.deckName),
    )
}

data class UndoneReview(
    val cardId: Long,
    val snapshot: LocalReviewSnapshot,
)

internal fun reviewLimitCardKey(noteGuid: String, cardOrd: Int, cardId: Long): String =
    if (noteGuid.isNotBlank()) "$noteGuid\u0000$cardOrd" else "card:$cardId"

internal const val MillisPerDay = 86_400_000L

internal fun epochDayAt(epochMillis: Long): Long =
    if (epochMillis >= 0) epochMillis / MillisPerDay
    else (epochMillis - MillisPerDay + 1) / MillisPerDay

expect fun currentEpochMillis(): Long
