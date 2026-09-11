package tech.kelma.app

data class ReviewHistoryInfo(
    val key: String,
    val reviewedAtMillis: Long,
    val rating: Rating,
    val interval: String,
    val takenMillis: Int,
    val pendingSync: Boolean,
)

data class ReviewCardInfo(
    val cardId: Long,
    val noteGuid: String,
    val deckName: String,
    val notetypeName: String,
    val templateOrdinal: Int,
    val createdAtMillis: Long?,
    val state: String,
    val dueAtMillis: Long?,
    val lastReviewAtMillis: Long?,
    val scheduledDays: Int?,
    val stability: Double?,
    val difficulty: Double?,
    val repetitions: Int,
    val lapses: Int,
    val scheduler: String,
    val schedulerProfileVersion: Long,
    val cloudSchedulerProfileVersion: Long?,
    val schedulerProfileStatus: String,
    val desiredRetention: Double,
    val flag: Int,
    val noteMarked: Boolean,
    val tags: List<String>,
    val recentReviews: List<ReviewHistoryInfo>,
)

internal fun buildReviewCardInfo(
    cardId: Long,
    collection: SyncedCollection,
    localReviews: LocalReviewSnapshot,
    effectiveFlags: Map<Long, Int>,
    options: DeckOptions,
    schedulerProfileVersion: Long,
    cloudSchedulerProfileVersion: Long?,
    schedulerProfileStatus: String,
    recentReviews: List<ReviewHistoryInfo>,
): ReviewCardInfo? {
    val card = collection.cards[cardId] ?: return null
    val note = collection.notes[card.noteGuid] ?: return null
    val schedule = localReviews.schedules[cardId]
    val dueAtMillis = localReviews.dueDateOverrides[cardId]?.takeIf { it > 0L }
        ?: schedule?.dueAtMillis
    return ReviewCardInfo(
        cardId = card.cardId,
        noteGuid = card.noteGuid,
        deckName = card.deckName,
        notetypeName = collection.notetypes[note.notetypeId]?.name ?: "Unknown note type",
        templateOrdinal = card.ord,
        createdAtMillis = card.createdAt?.let { runCatching { rfc3339ToEpochMillis(it) }.getOrNull() },
        state = when {
            card.studyState == CardStudyState.Suspended -> "Suspended"
            schedule == null -> "New"
            else -> schedule.phase.name
        },
        dueAtMillis = dueAtMillis,
        lastReviewAtMillis = schedule?.lastReviewAtMillis?.takeIf { it > 0L },
        scheduledDays = schedule?.scheduledDays,
        stability = schedule?.stability,
        difficulty = schedule?.difficulty,
        repetitions = schedule?.repetitions ?: 0,
        lapses = schedule?.lapses ?: 0,
        scheduler = options.effectiveSchedulerAlgorithm.label,
        schedulerProfileVersion = schedulerProfileVersion,
        cloudSchedulerProfileVersion = cloudSchedulerProfileVersion,
        schedulerProfileStatus = schedulerProfileStatus,
        desiredRetention = options.desiredRetention,
        flag = effectiveFlags[cardId] ?: card.synchronizedFlag,
        noteMarked = note.tags.any { it.equals("marked", ignoreCase = true) },
        tags = note.tags.sortedWith(String.CASE_INSENSITIVE_ORDER),
        recentReviews = recentReviews.sortedByDescending(ReviewHistoryInfo::reviewedAtMillis).take(10),
    )
}

internal val SchedulerProfileSyncStatus.cardInfoLabel: String
    get() = when (this) {
        SchedulerProfileSyncStatus.Current -> "Current"
        SchedulerProfileSyncStatus.Pending -> "Pending upload"
        SchedulerProfileSyncStatus.AwaitingConfirmation -> "Awaiting confirmation"
        SchedulerProfileSyncStatus.Conflict -> "Conflict"
    }

internal fun formatStoredReviewInterval(interval: Int): String {
    if (interval < 0) {
        val seconds = -interval.toLong()
        if (seconds < 60L) return "${seconds}s"
        if (seconds < 3_600L) return "${(seconds + 59L) / 60L}m"
        return "${(seconds + 3_599L) / 3_600L}h"
    }
    val days = interval.coerceAtLeast(0)
    if (days < 30) return "${days}d"
    if (days < 365) return formatInfoTenths((days * 10 + 15) / 30, "mo")
    return formatInfoTenths((days * 10 + 182) / 365, "y")
}

private fun formatInfoTenths(value: Int, suffix: String): String =
    if (value % 10 == 0) "${value / 10}$suffix" else "${value / 10}.${value % 10}$suffix"
