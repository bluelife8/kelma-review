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
        val uploadedMedia = uploadMedia(token, plan.media, onProgress)
        val notes = uploadNotes(token, plan.notes, conflicts, onProgress)
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
        val uploadedDecks = finishDecks(token, decks, onProgress)
        val profile = uploadSchedulerProfile(token, plan.schedulerProfile, conflicts, onProgress)
        return SyncPushResult(
            uploadedReviewIds = uploadedReviews,
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
