package tech.kelma.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class KelmaSyncPusher(
    private val baseUrl: String,
    private val httpClient: HttpClient,
) {
    suspend fun push(
        token: String,
        plan: SyncUploadPlan,
        onProgress: suspend (SyncPushProgress) -> Unit,
    ): SyncPushResult {
        if (plan.isEmpty) return SyncPushResult()
        val conflicts = mutableListOf<SyncUploadConflict>()
        val uploadedReviews = uploadReviews(token, plan.reviews, conflicts, onProgress)
        val uploadedReviewRetractions = uploadReviewRetractions(token, plan.reviewRetractions, onProgress)
        val uploadedMedia = uploadMedia(token, plan.media, onProgress)
        val notes = uploadNotes(token, plan.notes, conflicts, onProgress)
        val uploadableMarks = plan.noteMarks.filter { !it.requiresNoteUpload || it.guid in notes.uploadedGuids }
        val uploadedNoteMarks = uploadNoteMarks(token, uploadableMarks, onProgress)
        val decks = prepareDecks(token, plan.decks, conflicts, onProgress)
        val studyCards = plan.cardStudyStates.map { it.cardId to it.body }
        val resetCards = plan.cardScheduleResets.map { it.cardId to it.body }
        val dueDateCards = plan.cardDueDates.map { it.cardId to it.body }
        val uploadedCardIds = uploadCards(
            token,
            notes.cards + decks.cards + studyCards + resetCards + dueDateCards,
            onProgress,
        )
        val uploadedCardStudyKeys = plan.cardStudyStates
            .filter { it.cardId in uploadedCardIds }
            .mapTo(mutableSetOf(), PendingCardStudyUpload::key)
        val uploadedCardResetKeys = plan.cardScheduleResets
            .filter { it.cardId in uploadedCardIds }
            .mapTo(mutableSetOf(), PendingCardResetUpload::key)
        val uploadedCardDueDateKeys = plan.cardDueDates
            .filter { it.cardId in uploadedCardIds }
            .mapTo(mutableSetOf(), PendingCardDueDateUpload::key)
        val uploadableFlags = plan.cardFlags.filter { !it.requiresCardUpload || it.cardId in uploadedCardIds }
        val uploadedCardFlags = uploadCardFlags(token, uploadableFlags, onProgress)
        val uploadedDecks = finishDecks(token, decks, onProgress)
        val profile = uploadSchedulerProfile(token, plan.schedulerProfile, conflicts, onProgress)
        return SyncPushResult(
            uploadedReviewIds = uploadedReviews,
            uploadedReviewRetractionIntentIds = uploadedReviewRetractions,
            uploadedNoteMarkIntentIds = uploadedNoteMarks,
            uploadedCardFlagIntentIds = uploadedCardFlags,
            uploadedCardStudyKeys = uploadedCardStudyKeys,
            uploadedCardResetKeys = uploadedCardResetKeys,
            uploadedCardDueDateKeys = uploadedCardDueDateKeys,
            uploadedNoteGuids = notes.uploadedGuids,
            uploadedDeckSources = uploadedDecks,
            uploadedMediaFilenames = uploadedMedia,
            acknowledgedSchedulerProfile = profile,
            conflicts = conflicts,
        )
    }

    private suspend fun uploadReviews(
        token: String,
        reviews: List<ReviewPushBody>,
        conflicts: MutableList<SyncUploadConflict>,
        progress: suspend (SyncPushProgress) -> Unit,
    ): Set<Long> {
        if (reviews.isEmpty()) return emptySet()
        progress(SyncPushProgress(SyncPushResource.Reviews, 0, reviews.size))
        val uploaded = mutableSetOf<Long>()
        var completed = 0
        var conflictCount = 0
        reviews.chunked(MaximumBatchPushRecords).forEach { chunk ->
            val response = postBatch(token, BatchPushRequest(reviews = chunk))
            val collided = response.conflicts["reviews"].orEmpty().associateBy(SyncPushConflictEntry::reviewId)
            requireAcknowledged(response, "reviews", chunk.size, collided.size)
            chunk.forEach { review ->
                val conflict = collided[review.reviewId]
                if (conflict == null) {
                    uploaded += review.reviewId
                } else {
                    conflicts += SyncUploadConflict("review", review.reviewId.toString(), conflict.server.toString())
                }
            }
            completed += chunk.size
            conflictCount += collided.size
            progress(SyncPushProgress(SyncPushResource.Reviews, completed, reviews.size, uploaded.size, conflictCount))
        }
        return uploaded
    }

    private suspend fun uploadReviewRetractions(
        token: String,
        retractions: List<PendingReviewRetractionUpload>,
        progress: suspend (SyncPushProgress) -> Unit,
    ): Set<String> {
        if (retractions.isEmpty()) return emptySet()
        progress(SyncPushProgress(SyncPushResource.ReviewRetractions, 0, retractions.size))
        val uploaded = mutableSetOf<String>()
        var completed = 0
        retractions.chunked(MaximumBatchPushRecords).forEach { chunk ->
            val response = postBatch(
                token,
                BatchPushRequest(reviewRetractions = chunk.map(PendingReviewRetractionUpload::body)),
            )
            val conflicts = response.conflicts["review_retractions"].orEmpty()
            requireAcknowledged(response, "review_retractions", chunk.size, conflicts.size)
            if (conflicts.isNotEmpty()) {
                throw KelmaSyncException("A stable review retraction intent conflicted")
            }
            val results = response.reviewRetractions.associateBy { it.retraction.reviewId }
            val valid = response.reviewRetractions.size == chunk.size && results.size == chunk.size &&
                chunk.all { pending -> results[pending.reviewId]?.isValidAcknowledgement(pending) == true }
            if (!valid) throw KelmaSyncException("KelmaSync returned an invalid review retraction acknowledgement")
            uploaded += chunk.map(PendingReviewRetractionUpload::intentId)
            completed += chunk.size
            progress(SyncPushProgress(SyncPushResource.ReviewRetractions, completed, retractions.size))
        }
        return uploaded
    }

    private fun ReviewRetractionPushResult.isValidAcknowledgement(
        pending: PendingReviewRetractionUpload,
    ): Boolean = accepted && retraction.reviewId == pending.reviewId &&
        retraction.intentId == pending.intentId &&
        runCatching { rfc3339ToEpochMillis(retraction.clientModifiedAt) }.getOrNull() == pending.clientModifiedAtMillis

    private suspend fun uploadMedia(
        token: String,
        media: List<PendingMediaUpload>,
        progress: suspend (SyncPushProgress) -> Unit,
    ): Set<String> {
        if (media.isEmpty()) return emptySet()
        progress(SyncPushProgress(SyncPushResource.Media, 0, media.size))
        val uploaded = mutableSetOf<String>()
        media.chunked(MaximumParallelMediaRequests).forEach { chunk ->
            val completed = coroutineScope {
                chunk.map { item ->
                    async {
                        val response = httpClient.put("$baseUrl/v2/media/${item.filename.encodeURLPathPart()}") {
                            bearerAuth(token)
                            contentType(ContentType.parse(item.mimeType))
                            setBody(item.bytes)
                        }
                        ensureSuccess(response, "Media upload failed")
                        item.filename
                    }
                }.awaitAll()
            }
            uploaded += completed
            progress(SyncPushProgress(SyncPushResource.Media, uploaded.size, media.size))
        }
        return uploaded
    }

    private suspend fun uploadNotes(
        token: String,
        notes: List<PendingNoteUpload>,
        conflicts: MutableList<SyncUploadConflict>,
        progress: suspend (SyncPushProgress) -> Unit,
    ): NoteUploadOutcome {
        if (notes.isEmpty()) return NoteUploadOutcome()
        progress(SyncPushProgress(SyncPushResource.Notes, 0, notes.size))
        val uploaded = mutableSetOf<String>()
        val cards = mutableListOf<Pair<Long, CardPushBody>>()
        var completed = 0
        val deletions = notes.filter { it.operation == "delete" }
        deleteRequests(token, deletions.mapNotNull(PendingNoteUpload::deleteRequest))
        deletions.filter { it.deleteRequest == null }.forEach { note ->
            deleteResource(token, "/v2/notes/${note.guid.encodeURLPathPart()}")
        }
        uploaded += deletions.map(PendingNoteUpload::guid)
        completed += deletions.size
        if (completed > 0) {
            progress(SyncPushProgress(SyncPushResource.Notes, completed, notes.size, uploaded.size))
        }

        val upserts = notes.filter { it.operation != "delete" }
        val blocked = uploadNoteDependencies(token, upserts, conflicts, progress)
        completed += blocked.size
        if (blocked.isNotEmpty()) {
            progress(
                SyncPushProgress(
                    SyncPushResource.Notes,
                    completed,
                    notes.size,
                    uploaded.size,
                    blocked.size,
                ),
            )
        }
        upserts.filterNot { it.guid in blocked }
            .groupBy(PendingNoteUpload::forceOverride)
            .forEach { (force, group) ->
                group.chunked(MaximumBatchPushRecords).forEach { chunk ->
                    val request = chunk.map { note ->
                        note.body?.toBatchItem(note.guid) ?: error("Missing note upload body")
                    }
                    val response = postBatch(token, BatchPushRequest(notes = request), force)
                    val collided = response.conflicts["notes"].orEmpty().associateBy(SyncPushConflictEntry::guid)
                    requireAcknowledged(response, "notes", chunk.size, collided.size)
                    chunk.forEach { note ->
                        val conflict = collided[note.guid]
                        if (conflict == null) {
                            uploaded += note.guid
                            cards += note.cards
                        } else {
                            conflicts += SyncUploadConflict("note", note.guid, conflict.server.toString())
                        }
                    }
                    completed += chunk.size
                    progress(
                        SyncPushProgress(
                            SyncPushResource.Notes,
                            completed,
                            notes.size,
                            uploaded.size,
                            conflicts.count { it.kind == "note" },
                        ),
                    )
                }
            }
        return NoteUploadOutcome(uploaded, cards)
    }

    private suspend fun uploadNoteMarks(
        token: String,
        marks: List<PendingNoteMarkUpload>,
        progress: suspend (SyncPushProgress) -> Unit,
    ): Set<String> {
        if (marks.isEmpty()) return emptySet()
        progress(SyncPushProgress(SyncPushResource.NoteMarks, 0, marks.size))
        val uploaded = mutableSetOf<String>()
        var completed = 0
        marks.chunked(MaximumBatchPushRecords).forEach { chunk ->
            val response = postBatch(token, BatchPushRequest(noteMarks = chunk.map(PendingNoteMarkUpload::body)))
            val conflicts = response.conflicts["note_marks"].orEmpty()
            requireAcknowledged(response, "note_marks", chunk.size, conflicts.size)
            if (conflicts.isNotEmpty()) {
                throw KelmaSyncException("A stable note mark intent conflicted")
            }
            val results = response.noteMarks.associateBy(NoteMarkPushResult::guid)
            val valid = response.noteMarks.size == chunk.size && results.size == chunk.size && chunk.all { pending ->
                results[pending.guid]?.isValidAcknowledgement(pending) == true
            }
            if (!valid) throw KelmaSyncException("KelmaSync returned an invalid note mark acknowledgement")
            uploaded += chunk.map(PendingNoteMarkUpload::intentId)
            completed += chunk.size
            progress(SyncPushProgress(SyncPushResource.NoteMarks, completed, marks.size))
        }
        return uploaded
    }

    private fun NoteMarkPushResult.isValidAcknowledgement(pending: PendingNoteMarkUpload): Boolean {
        if (!accepted || mark.intentId.isBlank()) return false
        val winnerMillis = runCatching { rfc3339ToEpochMillis(mark.clientModifiedAt) }.getOrNull() ?: return false
        val sameIntent = mark.intentId == pending.intentId
        if (sameIntent) {
            return mark.marked == pending.marked && winnerMillis == pending.clientModifiedAtMillis
        }
        return !applied && (winnerMillis > pending.clientModifiedAtMillis ||
            (winnerMillis == pending.clientModifiedAtMillis && mark.intentId > pending.intentId))
    }

    private suspend fun uploadCardFlags(
        token: String,
        flags: List<PendingCardFlagUpload>,
        progress: suspend (SyncPushProgress) -> Unit,
    ): Set<String> {
        if (flags.isEmpty()) return emptySet()
        progress(SyncPushProgress(SyncPushResource.CardFlags, 0, flags.size))
        val uploaded = mutableSetOf<String>()
        var completed = 0
        flags.chunked(MaximumBatchPushRecords).forEach { chunk ->
            val response = postBatch(token, BatchPushRequest(cardFlags = chunk.map(PendingCardFlagUpload::body)))
            val conflicts = response.conflicts["card_flags"].orEmpty()
            requireAcknowledged(response, "card_flags", chunk.size, conflicts.size)
            if (conflicts.isNotEmpty()) {
                throw KelmaSyncException("A stable card flag intent conflicted")
            }
            val results = response.cardFlags.associateBy { cardStudyKey(it.noteGuid, it.cardOrd) }
            val valid = response.cardFlags.size == chunk.size && results.size == chunk.size && chunk.all { pending ->
                results[pending.key]?.isValidAcknowledgement(pending) == true
            }
            if (!valid) throw KelmaSyncException("KelmaSync returned an invalid card flag acknowledgement")
            uploaded += chunk.map(PendingCardFlagUpload::intentId)
            completed += chunk.size
            progress(SyncPushProgress(SyncPushResource.CardFlags, completed, flags.size))
        }
        return uploaded
    }

    private fun CardFlagPushResult.isValidAcknowledgement(pending: PendingCardFlagUpload): Boolean {
        if (!accepted || flag.intentId.isBlank()) return false
        val winnerMillis = runCatching { rfc3339ToEpochMillis(flag.clientModifiedAt) }.getOrNull() ?: return false
        val sameIntent = flag.intentId == pending.intentId
        if (sameIntent) {
            return flag.flag == pending.flag && winnerMillis == pending.clientModifiedAtMillis
        }
        return !applied && (winnerMillis > pending.clientModifiedAtMillis ||
            (winnerMillis == pending.clientModifiedAtMillis && flag.intentId > pending.intentId))
    }

    private suspend fun uploadNoteDependencies(
        token: String,
        notes: List<PendingNoteUpload>,
        conflicts: MutableList<SyncUploadConflict>,
        progress: suspend (SyncPushProgress) -> Unit,
    ): Set<String> {
        val decks = notes.mapNotNull { note ->
            note.deck?.let { (name, body) -> DeckDependency(name, body, note.guid, note.forceOverride) }
        }.groupBy(DeckDependency::name).values.map(::mergeDeckDependencies)
        val notetypes = notes.mapNotNull { note ->
            note.notetype?.let { (id, body) -> NotetypeDependency(id, body, note.guid, note.forceOverride) }
        }.groupBy(NotetypeDependency::id).values.map(::mergeNotetypeDependencies)
        val total = decks.size + notetypes.size
        if (total == 0) return emptySet()
        progress(SyncPushProgress(SyncPushResource.Dependencies, 0, total))
        val blocked = mutableSetOf<String>()
        var completed = 0
        var accepted = 0
        var dependencyConflicts = 0

        decks.groupBy(DeckDependency::force).forEach { (force, group) ->
            group.chunked(MaximumBatchPushRecords).forEach { chunk ->
                val response = postBatch(
                    token,
                    BatchPushRequest(decks = chunk.map { it.body.toBatchItem(it.name) }),
                    force,
                )
                val collided = response.conflicts["decks"].orEmpty().associateBy(SyncPushConflictEntry::name)
                requireAcknowledged(response, "decks", chunk.size, collided.size)
                chunk.forEach { dependency ->
                    collided[dependency.name]?.let { conflict ->
                        dependency.noteGuids.filter(blocked::add).forEach { guid ->
                            conflicts += SyncUploadConflict("note", guid, conflict.server.toString())
                        }
                    }
                }
                completed += chunk.size
                accepted += response.accepted["decks"] ?: 0
                dependencyConflicts += collided.size
                progress(
                    SyncPushProgress(
                        SyncPushResource.Dependencies,
                        completed,
                        total,
                        accepted,
                        dependencyConflicts,
                    ),
                )
            }
        }
        notetypes.groupBy(NotetypeDependency::force).forEach { (force, group) ->
            group.chunked(MaximumBatchPushRecords).forEach { chunk ->
                val response = postBatch(
                    token,
                    BatchPushRequest(notetypes = chunk.map { it.body.toBatchItem(it.id) }),
                    force,
                )
                val collided = response.conflicts["notetypes"].orEmpty()
                    .associateBy(SyncPushConflictEntry::notetypeId)
                requireAcknowledged(response, "notetypes", chunk.size, collided.size)
                chunk.forEach { dependency ->
                    collided[dependency.id]?.let { conflict ->
                        dependency.noteGuids.filter(blocked::add).forEach { guid ->
                            conflicts += SyncUploadConflict("note", guid, conflict.server.toString())
                        }
                    }
                }
                completed += chunk.size
                accepted += response.accepted["notetypes"] ?: 0
                dependencyConflicts += collided.size
                progress(
                    SyncPushProgress(
                        SyncPushResource.Dependencies,
                        completed,
                        total,
                        accepted,
                        dependencyConflicts,
                    ),
                )
            }
        }
        return blocked
    }

    private suspend fun prepareDecks(
        token: String,
        decks: List<PendingDeckUpload>,
        conflicts: MutableList<SyncUploadConflict>,
        progress: suspend (SyncPushProgress) -> Unit,
    ): DeckUploadOutcome {
        if (decks.isEmpty()) return DeckUploadOutcome()
        progress(SyncPushProgress(SyncPushResource.Decks, 0, decks.size))
        val records = decks.filter { it.operation != "delete" }.flatMap { deck ->
            val target = deck.targetName ?: deck.sourceName
            val body = deck.targetBody ?: error("Missing deck upload body")
            (listOf(target to body) + deck.additionalDecks).map { (name, item) ->
                DeckMutationRecord(deck.sourceName, name, item, deck.forceOverride)
            }
        }
        val blocked = mutableSetOf<String>()
        records.groupBy(DeckMutationRecord::force).forEach { (force, group) ->
            group.chunked(MaximumBatchPushRecords).forEach { chunk ->
                val response = postBatch(
                    token,
                    BatchPushRequest(decks = chunk.map { it.body.toBatchItem(it.name) }),
                    force,
                )
                val collided = response.conflicts["decks"].orEmpty().associateBy(SyncPushConflictEntry::name)
                requireAcknowledged(response, "decks", chunk.size, collided.size)
                chunk.forEach { record ->
                    collided[record.name]?.takeIf { blocked.add(record.source) }?.let { conflict ->
                        conflicts += SyncUploadConflict("deck", record.source, conflict.server.toString())
                    }
                }
            }
        }
        val successful = decks.filterNot { it.sourceName in blocked }
        return DeckUploadOutcome(
            decks = successful,
            cards = successful.flatMap(PendingDeckUpload::cards),
            conflictCount = blocked.size,
        )
    }

    private suspend fun uploadCards(
        token: String,
        rawCards: List<Pair<Long, CardPushBody>>,
        progress: suspend (SyncPushProgress) -> Unit,
    ): Set<Long> {
        val cards = linkedMapOf<Long, CardPushBody>().apply {
            rawCards.forEach { (id, body) ->
                put(id, get(id)?.mergeIndependentState(body) ?: body)
            }
        }
        if (cards.isEmpty()) return emptySet()
        val uploaded = mutableSetOf<Long>()
        progress(SyncPushProgress(SyncPushResource.Cards, 0, cards.size))
        var completed = 0
        var accepted = 0
        cards.entries.chunked(MaximumBatchPushRecords).forEach { chunk ->
            val request = chunk.map { (id, body) -> body.toBatchItem(id) }
            val response = postBatch(token, BatchPushRequest(cards = request))
            val chunkAccepted = response.accepted["cards"] ?: 0
            accepted += chunkAccepted
            require(chunkAccepted <= chunk.size) { "KelmaSync returned an invalid card acknowledgement" }
            if (chunkAccepted == chunk.size) uploaded += chunk.map(Map.Entry<Long, CardPushBody>::key)
            completed += chunk.size
            progress(SyncPushProgress(SyncPushResource.Cards, completed, cards.size, accepted))
        }
        return uploaded
    }

    private suspend fun finishDecks(
        token: String,
        outcome: DeckUploadOutcome,
        progress: suspend (SyncPushProgress) -> Unit,
    ): Set<String> {
        if (outcome.decks.isEmpty() && outcome.conflictCount == 0) return emptySet()
        deleteRequests(token, outcome.decks.mapNotNull(PendingDeckUpload::deleteRequest))
        val uploaded = outcome.decks.mapTo(mutableSetOf(), PendingDeckUpload::sourceName)
        val total = uploaded.size + outcome.conflictCount
        progress(SyncPushProgress(SyncPushResource.Decks, total, total, uploaded.size, outcome.conflictCount))
        return uploaded
    }

    private suspend fun uploadSchedulerProfile(
        token: String,
        candidate: SchedulerProfileCandidate?,
        conflicts: MutableList<SyncUploadConflict>,
        progress: suspend (SyncPushProgress) -> Unit,
    ): SchedulerProfileResponse? {
        if (candidate == null) return null
        progress(SyncPushProgress(SyncPushResource.SchedulerProfile, 0, 1))
        val response = httpClient.put("$baseUrl/v2/scheduler-profile") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(candidate)
        }
        if (response.status.value == 409) {
            val conflict = runCatching { response.body<SchedulerProfileConflictResponse>() }.getOrNull()
                ?: throw KelmaSyncException("Scheduler profile upload conflicted")
            conflicts += SyncUploadConflict(
                kind = SchedulerProfileConflictKind,
                resourceKey = candidate.idempotencyKey,
                serverJson = Json.encodeToString(conflict.server),
            )
            progress(SyncPushProgress(SyncPushResource.SchedulerProfile, 1, 1, 0, 1))
            return null
        }
        ensureSuccess(response, "Scheduler profile upload failed")
        progress(SyncPushProgress(SyncPushResource.SchedulerProfile, 1, 1))
        return response.body()
    }

    private suspend fun postBatch(
        token: String,
        request: BatchPushRequest,
        forceOverride: Boolean = false,
    ): BatchPushResponse {
        val response = httpClient.post("$baseUrl/v2/batch/push") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            if (forceOverride) header("Force-Override", "true")
            setBody(request)
        }
        ensureSuccess(response, "Batch upload failed")
        return response.body()
    }

    private fun requireAcknowledged(response: BatchPushResponse, kind: String, requested: Int, conflicts: Int) {
        val accepted = response.accepted[kind] ?: 0
        if (accepted + conflicts != requested) {
            throw KelmaSyncException("KelmaSync did not acknowledge every uploaded $kind record")
        }
    }

    private suspend fun deleteRequests(token: String, requests: List<BatchDeleteRequest>) {
        val combined = BatchDeleteRequest(
            notes = requests.flatMap(BatchDeleteRequest::notes).distinct(),
            cards = requests.flatMap(BatchDeleteRequest::cards).distinct(),
            notetypes = requests.flatMap(BatchDeleteRequest::notetypes).distinct(),
            decks = requests.flatMap(BatchDeleteRequest::decks).distinct(),
        )
        combined.chunked(MaximumBatchDeleteRecords).forEach { chunk ->
            val response = httpClient.post("$baseUrl/v2/batch/delete") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(chunk)
            }
            ensureSuccess(response, "Delete upload failed")
            val result = response.body<BatchDeleteResponse>()
            val requested = chunk.notes.size + chunk.cards.size + chunk.notetypes.size + chunk.decks.size
            require(result.requested.values.sum() == requested) { "KelmaSync did not confirm the deletion plan" }
        }
    }

    private suspend fun deleteResource(token: String, path: String) {
        val response = httpClient.delete("$baseUrl$path") { bearerAuth(token) }
        ensureSuccess(response, "Delete upload failed")
    }

    private suspend fun ensureSuccess(response: HttpResponse, fallback: String) {
        if (response.status.isSuccess()) return
        val error = runCatching { response.body<SyncError>() }.getOrNull()
        throw KelmaSyncException(error?.message?.ifBlank { error.error } ?: fallback)
    }
}

private fun CardPushBody.mergeIndependentState(other: CardPushBody): CardPushBody = other.copy(
    studyState = other.studyState ?: studyState,
    studyStateClientModifiedAt = other.studyStateClientModifiedAt ?: studyStateClientModifiedAt,
    scheduleResetThroughReviewId = other.scheduleResetThroughReviewId ?: scheduleResetThroughReviewId,
    scheduleResetClientModifiedAt = other.scheduleResetClientModifiedAt ?: scheduleResetClientModifiedAt,
    dueDateOverrideMillis = other.dueDateOverrideMillis ?: dueDateOverrideMillis,
    dueDateOverrideClientModifiedAt =
        other.dueDateOverrideClientModifiedAt ?: dueDateOverrideClientModifiedAt,
)
