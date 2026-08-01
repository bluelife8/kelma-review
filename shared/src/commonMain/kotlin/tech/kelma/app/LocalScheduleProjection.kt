package tech.kelma.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tech.kelma.db.KelmaQueries

internal data class ScheduleProjectionChanges(
    val portableIdentities: Set<String>,
    val cardIds: Set<Long>,
) {
    val isEmpty: Boolean
        get() = portableIdentities.isEmpty() && cardIds.isEmpty()
}

internal fun SyncedCollection.scheduleProjectionChanges(
    next: SyncedCollection,
): ScheduleProjectionChanges {
    val identities = mutableSetOf<String>()
    val cardIds = mutableSetOf<Long>()

    fun addCard(card: SyncCard?) {
        if (card == null) return
        identities += card.portableIdentity()
        cardIds += card.cardId
    }

    fun addReview(collection: SyncedCollection, review: SyncReview?) {
        if (review == null) return
        collection.portableIdentity(review)?.let(identities::add)
    }

    cards.forEach { (cardId, card) ->
        val replacement = next.cards[cardId]
        if (replacement != card) {
            addCard(card)
            addCard(replacement)
        }
    }
    next.cards.forEach { (cardId, card) ->
        if (cardId !in cards) addCard(card)
    }
    reviews.forEach { (reviewId, review) ->
        val replacement = next.reviews[reviewId]
        if (replacement != review) {
            addReview(this, review)
            addReview(next, replacement)
        }
    }
    next.reviews.forEach { (reviewId, review) ->
        if (reviewId !in reviews) addReview(next, review)
    }
    return ScheduleProjectionChanges(identities, cardIds)
}

/** Rebuilds device-local scheduling state exclusively from immutable review history. */
internal class LocalScheduleProjection(
    private val queries: KelmaQueries,
    private val json: Json,
    private val scheduler: SchedulingEngine,
) {
    fun rebuild() {
        val localContent = loadLocalContentSnapshot(queries, json)
        val collection = loadDownloadedCollection(queries, json).withLocalContent(localContent)
        rebuild(collection, localContent, changes = null)
    }

    fun rebuild(
        downloaded: SyncedCollection,
        changes: ScheduleProjectionChanges?,
    ) {
        if (changes?.isEmpty == true) return
        val localContent = loadLocalContentSnapshot(queries, json)
        rebuild(downloaded.withLocalContent(localContent), localContent, changes)
    }

    private fun rebuild(
        collection: SyncedCollection,
        localContent: LocalContentSnapshot,
        changes: ScheduleProjectionChanges?,
    ) {
        val targetIdentities = changes?.portableIdentities
        val cardsByIdentity = collection.cards.values.associateBy { it.portableIdentity() }
        val localResetThroughByIdentity = queries.selectLocalCardResets {
                noteGuid, cardOrd, _, resetThrough, _, _ ->
            portableIdentity(noteGuid, cardOrd.toInt()) to resetThrough
        }.executeAsList().toMap()
        val resetThroughByIdentity = collection.cards.values.associate { card ->
            card.portableIdentity() to maxOf(
                card.scheduleResetThroughReviewId,
                localResetThroughByIdentity[card.portableIdentity()] ?: 0L,
            )
        }
        val confirmedIds = collection.reviews.keys
        val accountOptions = queries.selectActiveLocalSchedulerProfile { _, settingsJson, _ ->
            json.decodeFromString<SchedulerProfileSettings>(settingsJson).validated().asDeckOptions()
        }.executeAsOneOrNull() ?: DeckOptions()
        val studyDayPolicy = loadStudyDayPolicy(queries, json)
        val eventsByCard = mutableMapOf<Long, MutableList<ProjectionReview>>()
        val localEvents = queries.selectAllLocalReviewEvents {
                _, cardId, noteGuid, cardOrd, _, rating, reviewedAt, _, _, _, _, _, reviewId, _, _ ->
            LocalProjectionReview(
                cardId = cardId,
                noteGuid = noteGuid,
                cardOrd = cardOrd.toInt(),
                reviewId = reviewId.takeIf { it > 0L } ?: reviewedAt,
                reviewedAtMillis = reviewedAt,
                rating = Rating.entries.firstOrNull { it.name == rating },
            )
        }.executeAsList()
        val localReviewedAtByReviewId = localEvents.associate { it.reviewId to it.reviewedAtMillis }

        collection.reviews.values.forEach { review ->
            val card = cardsByIdentity[portableIdentity(review.noteGuid, review.cardOrd)]
                ?: collection.cards[review.sourceCardId]
                ?: return@forEach
            if (targetIdentities != null && card.portableIdentity() !in targetIdentities) return@forEach
            val rating = Rating.entries.getOrNull(review.ease - 1) ?: return@forEach
            val resetThrough = resetThroughByIdentity[card.portableIdentity()] ?: Long.MIN_VALUE
            if (review.reviewId <= resetThrough) return@forEach
            eventsByCard.getOrPut(card.cardId, ::mutableListOf) += ProjectionReview(
                reviewId = review.reviewId,
                reviewedAtMillis = localReviewedAtByReviewId[review.reviewId] ?: review.reviewId,
                rating = rating,
            )
        }
        localEvents.forEach { event ->
            val card = cardsByIdentity[portableIdentity(event.noteGuid, event.cardOrd)]
                ?: collection.cards[event.cardId]
                ?: return@forEach
            if (targetIdentities != null && card.portableIdentity() !in targetIdentities) return@forEach
            val resetThrough = resetThroughByIdentity[card.portableIdentity()] ?: Long.MIN_VALUE
            if (event.reviewId !in confirmedIds && event.rating != null && event.reviewId > resetThrough) {
                eventsByCard.getOrPut(card.cardId, ::mutableListOf) += ProjectionReview(
                    reviewId = event.reviewId,
                    reviewedAtMillis = event.reviewedAtMillis,
                    rating = event.rating,
                )
            }
        }

        if (changes == null) {
            queries.clearLocalSchedules()
        } else {
            val currentTargetCardIds = targetIdentities.orEmpty().mapNotNullTo(mutableSetOf()) {
                cardsByIdentity[it]?.cardId
            }
            (changes.cardIds + currentTargetCardIds).forEach(queries::deleteLocalSchedule)
        }
        eventsByCard.forEach { (cardId, events) ->
            val card = collection.cards[cardId]?.copy(scheduling = JsonObject(emptyMap())) ?: return@forEach
            val options = localContent.deckOptions[card.deckName] ?: accountOptions
            var schedule: LocalCardSchedule? = null
            events.sortedBy(ProjectionReview::reviewId).forEach { event ->
                if (event.reviewId > 0L) {
                    schedule = scheduler.review(
                        card = card,
                        previous = schedule,
                        rating = event.rating,
                        reviewedAtMillis = event.reviewedAtMillis,
                        serverLastReviewAtMillis = null,
                        options = options,
                    ).alignedToStudyDay(studyDayPolicy)
                }
            }
            schedule?.let(::upsert)
        }
    }

    private fun upsert(schedule: LocalCardSchedule) {
        queries.upsertLocalSchedule(
            schedule.cardId,
            schedule.phase.name,
            schedule.dueAtMillis,
            schedule.stability,
            schedule.difficulty,
            schedule.scheduledDays.toLong(),
            schedule.repetitions.toLong(),
            schedule.lapses.toLong(),
            schedule.lastReviewAtMillis,
            schedule.step?.toLong(),
        )
    }
}

private data class ProjectionReview(
    val reviewId: Long,
    val reviewedAtMillis: Long,
    val rating: Rating,
)

private data class LocalProjectionReview(
    val cardId: Long,
    val noteGuid: String,
    val cardOrd: Int,
    val reviewId: Long,
    val reviewedAtMillis: Long,
    val rating: Rating?,
)

private fun SyncedCollection.portableIdentity(review: SyncReview): String? =
    review.noteGuid.takeIf(String::isNotBlank)?.let { portableIdentity(it, review.cardOrd) }
        ?: cards[review.sourceCardId]?.portableIdentity()

private fun SyncCard.portableIdentity(): String = portableIdentity(noteGuid, ord)
private fun portableIdentity(noteGuid: String, ord: Int): String = "$noteGuid\u0000$ord"
