package tech.kelma.app

import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaQueries
import tech.kelma.fsrs.OptimizerReview
import tech.kelma.fsrs.OptimizerReviewState
import tech.kelma.fsrs.Rating as FsrsRating

data class SchedulerOptimizerHistory(
    val reviews: List<OptimizerReview>,
    val serverHistorySha256: String?,
    val throughReviewId: Long?,
    val publishable: Boolean,
)

internal class SchedulerOptimizerHistoryLoader(
    private val queries: KelmaQueries,
    private val json: Json,
) {
    fun load(): SchedulerOptimizerHistory {
        queries.backfillLocalReviewPortableIdentities()
        val activeIdentities = queries.selectOptimizerCardIdentities { noteGuid, ord ->
            portableIdentity(noteGuid, ord.toInt())
        }.executeAsList().toSet()
        val syncedNotes = queries.selectOptimizerSyncedNoteGuids().executeAsList().toSet()
        val confirmed = queries.selectOptimizerConfirmedReviews {
                reviewId, noteGuid, cardOrd, ease, takenMillis, reviewKind, checksum ->
            ConfirmedOptimizerReview(
                reviewId,
                noteGuid,
                cardOrd.toInt(),
                ease.toInt(),
                takenMillis.toInt(),
                reviewKind.toInt(),
                checksum,
            )
        }.executeAsList()
        val confirmedIds = confirmed.asSequence().map { it.reviewId }.toSet()
        val allLocal = queries.selectOptimizerLocalReviews {
                _, noteGuid, cardOrd, rating, reviewedAt, duration, beforeJson,
                afterJson, wasNew, reviewId, uploadState ->
            LocalOptimizerReview(
                noteGuid = noteGuid,
                cardOrd = cardOrd.toInt(),
                rating = Rating.entries.first { it.name == rating },
                reviewedAtMillis = reviewedAt,
                durationMillis = duration,
                before = beforeJson?.let { json.decodeFromString<LocalCardSchedule>(it) },
                after = json.decodeFromString(afterJson),
                wasNew = wasNew == 1L,
                reviewId = reviewId,
                uploadState = uploadState,
            )
        }.executeAsList()
        val localReviewedAtByReviewId = allLocal.associate { it.reviewId to it.reviewedAtMillis }
        val local = allLocal.filter { it.reviewId !in confirmedIds && it.noteGuid.isNotBlank() }

        val optimizerReviews = buildList {
            confirmed.forEach { review ->
                    add(
                        OptimizerReview(
                            reviewId = review.reviewId,
                            cardIdentity = portableIdentity(review.noteGuid, review.cardOrd),
                            reviewedAtMillis = localReviewedAtByReviewId[review.reviewId] ?: review.reviewId,
                            rating = FsrsRating.fromValue(review.ease),
                            state = review.reviewKind.asOptimizerState(),
                            durationMillis = review.takenMillis.toLong().coerceAtLeast(0L),
                        ),
                    )
                }
            local.filter { portableIdentity(it.noteGuid, it.cardOrd) in activeIdentities }
                .forEach { review ->
                    add(
                        OptimizerReview(
                            reviewId = review.reviewId,
                            cardIdentity = portableIdentity(review.noteGuid, review.cardOrd),
                            reviewedAtMillis = review.reviewedAtMillis,
                            rating = FsrsRating.fromValue(review.rating.ordinal + 1),
                            state = when {
                                review.wasNew -> OptimizerReviewState.New
                                review.before?.phase == ReviewPhase.Relearning -> OptimizerReviewState.Relearning
                                review.before?.phase == ReviewPhase.Learning -> OptimizerReviewState.Learning
                                else -> OptimizerReviewState.Review
                            },
                            durationMillis = review.durationMillis.coerceAtLeast(0L),
                        ),
                    )
                }
        }.sortedWith(compareBy({ it.cardIdentity }, { it.reviewedAtMillis }, { it.reviewId }))
        val throughReviewId = optimizerReviews.maxOfOrNull(OptimizerReview::reviewId)
        val publishable = local.none {
            it.uploadState == "conflict" || it.noteGuid !in syncedNotes
        }
        val serverHash = throughReviewId?.takeIf { publishable }?.let { through ->
            val rows = buildList {
                confirmed.filter { it.reviewId <= through }.forEach { review ->
                    add(ServerHistoryReview(review.reviewId, review.noteGuid, review.cardOrd, review.checksum))
                }
                local.filter { it.reviewId <= through }.forEach { review ->
                    add(
                        ServerHistoryReview(
                            review.reviewId,
                            review.noteGuid,
                            review.cardOrd,
                            reviewChecksum(review),
                        ),
                    )
                }
            }.sortedWith(compareBy({ it.reviewId }, { it.noteGuid }, { it.cardOrd }))
            rows.takeIf { it.isNotEmpty() }?.let(::serverHistoryHash)
        }
        return SchedulerOptimizerHistory(
            reviews = optimizerReviews,
            serverHistorySha256 = serverHash,
            throughReviewId = throughReviewId,
            publishable = publishable && serverHash != null,
        )
    }

    private fun reviewChecksum(review: LocalOptimizerReview): String {
        val ease = review.rating.ordinal + 1
        val interval = review.after.scheduledDays
        val lastInterval = review.before?.scheduledDays ?: 0
        val factor = ((5.0 - review.after.difficulty) * 500 + 2_500)
            .toInt().coerceIn(1_300, 3_500)
        val takenMillis = review.durationMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val reviewKind = when {
            review.wasNew || review.before?.phase == ReviewPhase.Learning -> 0
            review.before?.phase == ReviewPhase.Relearning -> 2
            else -> 1
        }
        return schedulerReviewChecksum(
            review.noteGuid,
            review.cardOrd,
            ease,
            interval,
            lastInterval,
            factor,
            takenMillis,
            reviewKind,
        )
    }
}

private data class ConfirmedOptimizerReview(
    val reviewId: Long,
    val noteGuid: String,
    val cardOrd: Int,
    val ease: Int,
    val takenMillis: Int,
    val reviewKind: Int,
    val checksum: String,
)

private data class LocalOptimizerReview(
    val noteGuid: String,
    val cardOrd: Int,
    val rating: Rating,
    val reviewedAtMillis: Long,
    val durationMillis: Long,
    val before: LocalCardSchedule?,
    val after: LocalCardSchedule,
    val wasNew: Boolean,
    val reviewId: Long,
    val uploadState: String,
)

private data class ServerHistoryReview(
    val reviewId: Long,
    val noteGuid: String,
    val cardOrd: Int,
    val checksum: String,
)

private fun Int.asOptimizerState(): OptimizerReviewState = when (this) {
    0 -> OptimizerReviewState.Learning
    2 -> OptimizerReviewState.Relearning
    else -> OptimizerReviewState.Review
}

private fun portableIdentity(noteGuid: String, cardOrd: Int): String = "$noteGuid\u0000$cardOrd"

private fun serverHistoryHash(rows: List<ServerHistoryReview>): String {
    val digest = SchedulerHistorySha256()
    rows.forEach { row ->
        val noteGuid = row.noteGuid.encodeToByteArray()
        digest.update("${row.reviewId}\n${noteGuid.size}:")
        digest.update(noteGuid)
        digest.update("\n${row.cardOrd}\n${row.checksum}\n")
    }
    return digest.hexDigest()
}

internal fun schedulerReviewChecksum(
    noteGuid: String,
    cardOrd: Int,
    ease: Int,
    interval: Int,
    lastInterval: Int,
    factor: Int,
    takenMillis: Int,
    reviewKind: Int,
): String {
    val digest = SchedulerHistorySha256()
    digest.update(canonicalJsonString(noteGuid)).update("\n")
    listOf(cardOrd, ease, interval, lastInterval, factor, takenMillis, reviewKind)
        .forEach { digest.update(it.toString()).update("\n") }
    return digest.hexDigest()
}

private fun canonicalJsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}
