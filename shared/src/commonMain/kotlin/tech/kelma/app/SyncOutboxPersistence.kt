package tech.kelma.app

import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaDatabase

internal class SyncOutboxPersistence(
    private val database: KelmaDatabase,
    private val json: Json,
    private val localDeckOperations: LocalDeckOperations,
    private val loadCollection: () -> SyncedCollection,
    private val loadLocalContent: () -> LocalContentSnapshot,
    private val loadMediaBytes: (String) -> ByteArray?,
) {
    private val queries = database.kelmaQueries

    fun prepare(): SyncUploadPlan = database.transactionWithResult {
        queries.backfillLocalReviewPortableIdentities()
        var nextReviewId = maxOf(
            queries.selectMaximumLocalReviewId().executeAsOne().max ?: 0L,
            queries.selectMaximumServerReviewId().executeAsOne().max ?: 0L,
            queries.selectMaximumLocalCardResetReviewId().executeAsOne().max ?: 0L,
            queries.selectMaximumSyncCardResetReviewId().executeAsOne().max ?: 0L,
        )
        queries.selectPendingLocalReviewEvents {
                eventId, _, _, _, _, _, reviewedAt, _, _, _, _, _, reviewId ->
            Triple(eventId, reviewedAt, reviewId)
        }.executeAsList().filter { it.third == 0L }.forEach { (eventId, reviewedAt, _) ->
            nextReviewId = maxOf(reviewedAt, nextReviewId + 1L)
            queries.updateLocalReviewId(reviewId = nextReviewId, eventId = eventId)
        }
        val raw = loadCollection()
        val reviewRetractions = if (ReviewRetractionSyncCapability in raw.capabilities) {
            queries.selectPendingLocalReviewRetractions { reviewId, intentId, modifiedAt ->
                PendingReviewRetractionUpload(reviewId, intentId, modifiedAt)
            }.executeAsList()
        } else {
            emptyList()
        }
        val local = loadLocalContent()
        val displayed = raw.withLocalContent(local)
        val reviews = queries.selectPendingLocalReviewEvents {
                eventId, cardId, noteGuid, cardOrd, deckName, rating, reviewedAt, studyDay, duration,
                beforeJson, afterJson, wasNew, reviewId ->
            val legacyCard = displayed.cards[cardId]
            PendingReviewEvent(
                eventId,
                cardId,
                noteGuid.ifBlank { legacyCard?.noteGuid.orEmpty() },
                if (noteGuid.isBlank()) legacyCard?.ord ?: cardOrd.toInt() else cardOrd.toInt(),
                deckName,
                Rating.entries.first { it.name == rating },
                reviewedAt,
                studyDay,
                duration,
                beforeJson?.let { json.decodeFromString<LocalCardSchedule>(it) },
                json.decodeFromString<LocalCardSchedule>(afterJson),
                wasNew == 1L,
                reviewId,
            )
        }.executeAsList()
        val notes = queries.selectPendingLocalNoteSync { guid, operation, base, modified, force ->
            PendingNoteSyncRow(guid, operation, base, modified, force == 1L)
        }.executeAsList()
        val noteMarks = queries.selectPendingLocalNoteMarks { guid, marked, intentId, modifiedAt ->
            PendingNoteMarkUpload(
                guid,
                marked == 1L,
                intentId,
                modifiedAt,
                requiresNoteUpload = guid !in raw.notes,
            )
        }.executeAsList()
        backfillLegacyCardFlags(raw, displayed)
        val remoteCardKeys = raw.cards.values.mapTo(mutableSetOf()) { cardStudyKey(it.noteGuid, it.ord) }
        val cardFlags = if (CardFlagSyncCapability in raw.capabilities) {
            queries.selectPendingLocalCardFlags { noteGuid, cardOrd, cardId, flag, intentId, modifiedAt ->
                val key = cardStudyKey(noteGuid, cardOrd.toInt())
                PendingCardFlagUpload(
                    key,
                    cardId,
                    flag.toInt(),
                    intentId,
                    modifiedAt,
                    requiresCardUpload = key !in remoteCardKeys,
                )
            }.executeAsList()
        } else {
            emptyList()
        }
        val decks = queries.selectPendingLocalDeckSync { source, operation, target, base, modified, force ->
            PendingDeckSyncRow(source, operation, target, base, modified, force == 1L)
        }.executeAsList()
        val pendingCardStudyStates = queries.selectPendingLocalCardStudyStates {
                noteGuid, cardOrd, cardId, studyState, modified, uploadState ->
            LocalCardStudyState(
                cardId = cardId,
                noteGuid = noteGuid,
                cardOrd = cardOrd.toInt(),
                state = studyState.asCardStudyState(),
                clientModifiedAtMillis = modified,
                uploadState = uploadState,
            )
        }.executeAsList()
        val cardsByStudyKey = displayed.cards.values.associateBy { cardStudyKey(it.noteGuid, it.ord) }
        val cardStudyStates = pendingCardStudyStates.mapNotNull { pending ->
            val key = cardStudyKey(pending.noteGuid, pending.cardOrd)
            val card = displayed.cards[pending.cardId] ?: cardsByStudyKey[key]
            card?.let {
                val stateModifiedAt = epochMillisToRfc3339(pending.clientModifiedAtMillis)
                PendingCardStudyUpload(
                    key = key,
                    cardId = it.cardId,
                    body = CardPushBody(
                        noteGuid = it.noteGuid,
                        deckName = it.deckName,
                        ord = it.ord,
                        scheduling = it.scheduling,
                        clientModifiedAt = it.clientModifiedAt.ifBlank { stateModifiedAt },
                        studyState = pending.state,
                        studyStateClientModifiedAt = stateModifiedAt,
                        createdAt = it.creationTimestamp(),
                    ),
                )
            }
        }
        val cardScheduleResets = queries.selectPendingLocalCardResets {
                noteGuid, cardOrd, cardId, resetThrough, modified, _ ->
            PendingCardResetRow(noteGuid, cardOrd.toInt(), cardId, resetThrough, modified)
        }.executeAsList().mapNotNull { pending ->
            val key = cardStudyKey(pending.noteGuid, pending.cardOrd)
            val card = displayed.cards[pending.cardId] ?: cardsByStudyKey[key]
            card?.let {
                val resetModifiedAt = epochMillisToRfc3339(pending.clientModifiedAtMillis)
                PendingCardResetUpload(
                    key = key,
                    cardId = it.cardId,
                    body = CardPushBody(
                        noteGuid = it.noteGuid,
                        deckName = it.deckName,
                        ord = it.ord,
                        scheduling = it.scheduling,
                        clientModifiedAt = it.clientModifiedAt.ifBlank { resetModifiedAt },
                        scheduleResetThroughReviewId = pending.resetThroughReviewId,
                        scheduleResetClientModifiedAt = resetModifiedAt,
                        createdAt = it.creationTimestamp(),
                    ),
                )
            }
        }
        val cardDueDates = queries.selectPendingLocalCardDueOverrides {
                noteGuid, cardOrd, cardId, dueAtMillis, modified, _ ->
            PendingCardDueDateRow(noteGuid, cardOrd.toInt(), cardId, dueAtMillis, modified)
        }.executeAsList().mapNotNull { pending ->
            val key = cardStudyKey(pending.noteGuid, pending.cardOrd)
            val card = displayed.cards[pending.cardId] ?: cardsByStudyKey[key]
            card?.let {
                val dueModifiedAt = epochMillisToRfc3339(pending.clientModifiedAtMillis)
                PendingCardDueDateUpload(
                    key = key,
                    cardId = it.cardId,
                    body = CardPushBody(
                        noteGuid = it.noteGuid,
                        deckName = it.deckName,
                        ord = it.ord,
                        scheduling = it.scheduling,
                        clientModifiedAt = it.clientModifiedAt.ifBlank { dueModifiedAt },
                        dueDateOverrideMillis = pending.dueAtMillis,
                        dueDateOverrideClientModifiedAt = dueModifiedAt,
                        createdAt = it.creationTimestamp(),
                    ),
                )
            }
        }
        buildSyncUploadPlan(raw, local, reviews, notes, decks).copy(
            reviewRetractions = reviewRetractions,
            noteMarks = noteMarks,
            cardFlags = cardFlags,
            cardStudyStates = cardStudyStates,
            cardScheduleResets = cardScheduleResets,
            cardDueDates = cardDueDates,
            media = queries.selectPendingLocalMedia { filename, mimeType, checksum, bytes, _ ->
                PendingMediaUpload(filename, mimeType, checksum, bytes)
            }.executeAsList(),
        )
    }

    fun apply(result: SyncPushResult) {
        database.transaction {
            result.uploadedReviewIds.forEach(queries::markLocalReviewUploaded)
            result.uploadedReviewRetractionIntentIds.forEach(queries::markLocalReviewRetractionUploaded)
            result.uploadedNoteMarkIntentIds.forEach(queries::markLocalNoteMarkUploaded)
            result.uploadedCardFlagIntentIds.forEach(queries::markLocalCardFlagUploaded)
            result.uploadedCardStudyKeys.forEach { key ->
                val (noteGuid, ord) = key.splitCardStudyKey()
                queries.markLocalCardStudyStateUploaded(noteGuid, ord.toLong())
            }
            result.uploadedCardResetKeys.forEach { key ->
                val (noteGuid, ord) = key.splitCardStudyKey()
                queries.markLocalCardResetUploaded(noteGuid, ord.toLong())
            }
            result.uploadedCardDueDateKeys.forEach { key ->
                val (noteGuid, ord) = key.splitCardStudyKey()
                queries.markLocalCardDueOverrideUploaded(noteGuid, ord.toLong())
            }
            result.uploadedNoteGuids.forEach(queries::markLocalNoteSyncUploaded)
            result.uploadedDeckSources.forEach(queries::markLocalDeckSyncUploaded)
            result.uploadedMediaFilenames.forEach(queries::markLocalMediaUploaded)
            result.conflicts.forEach { conflict ->
                when (conflict.kind) {
                    "review" -> conflict.resourceKey.toLongOrNull()?.let(queries::markLocalReviewConflict)
                    "note" -> queries.markLocalNoteSyncConflict(conflict.serverJson, conflict.resourceKey)
                    "deck" -> queries.markLocalDeckSyncConflict(conflict.serverJson, conflict.resourceKey)
                }
            }
        }
    }

    fun resolve(conflict: SyncUploadConflict, keepLocal: Boolean, nowMillis: Long) {
        if (conflict.kind == "deck" && !keepLocal) {
            localDeckOperations.discardSyncConflict(conflict.resourceKey, nowMillis)
            return
        }
        database.transaction {
            when (conflict.kind) {
                "review" -> {
                    require(!keepLocal) { "Conflicting immutable review history cannot be overwritten" }
                    queries.deleteLocalReviewConflict(conflict.resourceKey.toLong())
                }
                "note" -> if (keepLocal) {
                    queries.retryLocalNoteSyncWithForce(conflict.resourceKey)
                } else {
                    queries.selectLocalCardIdsForNote(conflict.resourceKey).executeAsList().forEach { cardId ->
                        queries.deleteLocalSchedule(cardId)
                    }
                    queries.deleteLocalCardsForNote(conflict.resourceKey)
                    queries.deleteLocalNote(conflict.resourceKey)
                    queries.deleteLocalNoteOverride(conflict.resourceKey)
                    queries.deleteLocalNoteSync(conflict.resourceKey)
                }
                "deck" -> queries.retryLocalDeckSyncWithForce(conflict.resourceKey)
            }
            if (conflict.kind == "note") queries.markBrowseIndexDirty()
        }
    }

    fun conflicts(): List<SyncUploadConflict> = buildList {
        queries.selectLocalReviewConflicts().executeAsList().forEach { reviewId ->
            add(SyncUploadConflict("review", reviewId.toString(), ""))
        }
        queries.selectLocalNoteSyncConflicts { guid, conflict -> guid to conflict.orEmpty() }
            .executeAsList()
            .forEach { (guid, conflict) -> add(SyncUploadConflict("note", guid, conflict)) }
        queries.selectLocalDeckSyncConflicts { source, conflict -> source to conflict.orEmpty() }
            .executeAsList()
            .forEach { (source, conflict) -> add(SyncUploadConflict("deck", source, conflict)) }
    }

    fun hasUploadedRows(): Boolean = queries.countUploadedSyncRows().executeAsOne() > 0L

    fun reconcileUploadedRows() {
        val downloadedCollection = loadCollection()
        val localDeckOptions = loadLocalContent().deckOptions
        val downloadedMedia = downloadedCollection.media
        queries.selectLocalMedia { filename, _, checksum, _, _, state -> Triple(filename, checksum, state) }
            .executeAsList()
            .filter { it.third == "uploaded" }
            .forEach { (filename, checksum, _) ->
                val downloadedChecksum = downloadedMedia[filename]
                    ?.let { loadMediaBytes(filename) }
                    ?.let { SchedulerHistorySha256().update(it).hexDigest() }
                if (downloadedChecksum == checksum) queries.deleteLocalMedia(filename)
                else queries.retryUploadedLocalMedia(filename)
            }
        val downloadedCardsByStudyKey = downloadedCollection.cards.values
            .associateBy { cardStudyKey(it.noteGuid, it.ord) }
        queries.selectLocalCardStudyStates {
                noteGuid, cardOrd, _, studyState, _, uploadState ->
            LocalCardStudyState(
                cardId = 0,
                noteGuid = noteGuid,
                cardOrd = cardOrd.toInt(),
                state = studyState.asCardStudyState(),
                clientModifiedAtMillis = 0,
                uploadState = uploadState,
            )
        }.executeAsList().filter { it.uploadState == "uploaded" }.forEach { localState ->
            val remote = downloadedCardsByStudyKey[cardStudyKey(localState.noteGuid, localState.cardOrd)]
            if (remote?.studyState == localState.state) {
                queries.deleteLocalCardStudyState(localState.noteGuid, localState.cardOrd.toLong())
            } else {
                queries.retryLocalCardStudyState(localState.noteGuid, localState.cardOrd.toLong())
            }
        }
        queries.selectLocalCardResets { noteGuid, cardOrd, _, resetThrough, _, uploadState ->
            Triple(cardStudyKey(noteGuid, cardOrd.toInt()), resetThrough, uploadState)
        }.executeAsList().filter { it.third == "uploaded" }.forEach { (key, resetThrough, _) ->
            val (noteGuid, ord) = key.splitCardStudyKey()
            val remote = downloadedCardsByStudyKey[key]
            if (remote != null && remote.scheduleResetThroughReviewId >= resetThrough) {
                queries.deleteUploadedLocalCardReset(noteGuid, ord.toLong())
            } else {
                queries.retryLocalCardReset(noteGuid, ord.toLong())
            }
        }
        queries.selectLocalCardDueOverrides {
                noteGuid, cardOrd, _, dueAtMillis, modifiedAt, uploadState ->
            PendingCardDueDateRow(
                noteGuid,
                cardOrd.toInt(),
                0L,
                dueAtMillis,
                modifiedAt,
                uploadState,
            )
        }.executeAsList().forEach { localDueDate ->
            val key = cardStudyKey(localDueDate.noteGuid, localDueDate.cardOrd)
            val remote = downloadedCardsByStudyKey[key]
            val remoteModifiedAt = remote?.dueDateOverrideClientModifiedAt
                ?.let(::rfc3339ToEpochMillis) ?: 0L
            when {
                remote != null && remoteModifiedAt >= localDueDate.clientModifiedAtMillis ->
                    queries.deleteLocalCardDueOverride(localDueDate.noteGuid, localDueDate.cardOrd.toLong())
                localDueDate.uploadState == "uploaded" ->
                    queries.retryLocalCardDueOverride(localDueDate.noteGuid, localDueDate.cardOrd.toLong())
            }
        }
        reconcileReviewRetractions(downloadedCollection)
        reconcileNoteMarks(downloadedCollection)
        reconcileCardFlags(downloadedCollection)
        queries.reconcileUploadedLocalReviews()
        queries.retryUnconfirmedLocalReviews()
        queries.reconcileUploadedLocalNoteCards()
        queries.reconcileUploadedLocalNotes()
        queries.reconcileUploadedLocalNoteOverrides()
        queries.reconcileUploadedLocalNoteSync()
        queries.retryUnconfirmedLocalNoteSync()
        val downloaded = queries.selectDownloadedDeckNames().executeAsList()
        fun dailyLimitsConfirmed(deckName: String): Boolean {
            val expected = localDeckOptions.entries
                .firstOrNull { it.key.equals(deckName, ignoreCase = true) }
                ?.value ?: return true
            val remoteConfig = downloadedCollection.deckRecords.entries
                .firstOrNull { it.key.equals(deckName, ignoreCase = true) }
                ?.value
                ?.config ?: return false
            val actual = remoteConfig.syncedDailyLimits()
            return actual.newCardsPerDay == expected.newCardsPerDay &&
                actual.maximumReviewsPerDay == expected.maximumReviewsPerDay
        }
        fun dailyLimitTreeConfirmed(rootName: String): Boolean = localDeckOptions.keys
            .filter { it.isDeckOrDescendantOf(rootName) }
            .all(::dailyLimitsConfirmed)
        val overrides = queries.selectLocalDeckOverrides { source, _ -> source }.executeAsList()
        queries.selectUploadedLocalDeckSync { source, operation, target -> Triple(source, operation, target) }
            .executeAsList()
            .forEach { (source, operation, target) ->
                val sourceExists = downloaded.any { it.isDeckOrDescendantOf(source) }
                val targetExists = target != null && downloaded.any { it.isDeckOrDescendantOf(target) }
                val reconciled = when (operation) {
                    "upsert" -> downloaded.any { it.equals(source, ignoreCase = true) } &&
                        dailyLimitsConfirmed(source)
                    "rename" -> !sourceExists && targetExists && dailyLimitTreeConfirmed(target)
                    "delete" -> !sourceExists
                    else -> false
                }
                if (reconciled) {
                    overrides.filter { it.isDeckOrDescendantOf(source) }
                        .forEach(queries::deleteLocalDeckOverride)
                    queries.deleteLocalDeckSync(source)
                } else {
                    queries.retryUploadedLocalDeckSync(source)
                }
            }
    }

    private fun reconcileReviewRetractions(downloaded: SyncedCollection) {
        queries.selectLocalReviewRetractions { reviewId, intentId, modifiedAt, uploadState ->
            LocalReviewRetraction(reviewId, intentId, modifiedAt, uploadState)
        }.executeAsList().forEach { local ->
            val remote = downloaded.reviewRetractions[local.reviewId]
            if (remote != null) {
                if (remote.intentId == local.intentId &&
                    rfc3339ToEpochMillis(remote.clientModifiedAt) != local.clientModifiedAtMillis) {
                    error("KelmaSync changed a stable review retraction intent")
                }
                queries.deleteLocalReviewRetraction(local.reviewId)
            } else if (local.uploadState == "uploaded") {
                queries.retryLocalReviewRetraction(local.intentId)
            }
        }
    }

    private fun backfillLegacyCardFlags(raw: SyncedCollection, displayed: SyncedCollection) {
        val existingCardIds = queries.selectLocalCardFlagIntents { _, _, cardId, _, _, _, _ -> cardId }
            .executeAsList().toSet()
        var intentTime = currentEpochMillis()
        queries.selectLocalCardFlags { cardId, flag -> cardId to flag.toInt() }
            .executeAsList()
            .filterNot { (cardId, _) -> cardId in existingCardIds }
            .forEach { (cardId, flag) ->
                val card = displayed.cards[cardId] ?: return@forEach
                val remoteTime = raw.cards.values
                    .firstOrNull { it.noteGuid == card.noteGuid && it.ord == card.ord }
                    ?.flag?.clientModifiedAt?.let(::rfc3339ToEpochMillis) ?: Long.MIN_VALUE
                intentTime = maxOf(intentTime + 1L, remoteTime + 1L)
                queries.upsertLocalCardFlagIntent(
                    card.noteGuid,
                    card.ord.toLong(),
                    card.cardId,
                    flag.toLong(),
                    randomUuidString(),
                    intentTime,
                )
            }
    }

    private fun reconcileCardFlags(downloaded: SyncedCollection) {
        val downloadedByKey = downloaded.cards.values.associateBy { cardStudyKey(it.noteGuid, it.ord) }
        val localCardKeys = loadLocalContent().cards.values.mapTo(mutableSetOf()) { cardStudyKey(it.noteGuid, it.ord) }
        queries.selectLocalCardFlagIntents { noteGuid, cardOrd, cardId, flag, intentId, modifiedAt, state ->
            LocalCardFlagIntent(noteGuid, cardOrd.toInt(), cardId, flag.toInt(), intentId, modifiedAt, state)
        }.executeAsList().forEach { local ->
            val key = cardStudyKey(local.noteGuid, local.cardOrd)
            val remoteCard = downloadedByKey[key]
            val remote = remoteCard?.flag
            val remoteMillis = remote?.clientModifiedAt?.let(::rfc3339ToEpochMillis)
            val sameIntent = remote?.intentId == local.intentId
            val confirmed = remote != null && sameIntent && remote.flag == local.flag &&
                remoteMillis == local.clientModifiedAtMillis
            if (sameIntent && !confirmed) error("KelmaSync changed a stable card flag intent")
            val remoteWins = remoteMillis != null && !sameIntent &&
                (remoteMillis > local.clientModifiedAtMillis ||
                    (remoteMillis == local.clientModifiedAtMillis && remote.intentId > local.intentId))
            when {
                remoteCard == null && key !in localCardKeys -> {
                    queries.deleteLocalCardFlagIntent(local.noteGuid, local.cardOrd.toLong())
                    queries.deleteLocalCardFlag(local.cardId)
                }
                confirmed || remoteWins -> {
                    queries.deleteLocalCardFlagIntent(local.noteGuid, local.cardOrd.toLong())
                    queries.deleteLocalCardFlag(local.cardId)
                    remoteCard.cardId.let(queries::deleteLocalCardFlag)
                }
                local.uploadState == "uploaded" -> queries.retryLocalCardFlag(local.intentId)
            }
        }
    }

    private fun reconcileNoteMarks(downloaded: SyncedCollection) {
        queries.selectLocalNoteMarks { guid, marked, intentId, modifiedAt, state ->
            LocalNoteMarkIntent(guid, marked == 1L, intentId, modifiedAt, state)
        }.executeAsList().forEach { local ->
            val remoteNote = downloaded.notes[local.noteGuid]
            val remote = remoteNote?.mark
            val remoteMillis = remote?.clientModifiedAt?.let(::rfc3339ToEpochMillis)
            val sameIntent = remote?.intentId == local.intentId
            val confirmed = remote != null && sameIntent && remote.marked == local.marked &&
                remoteMillis == local.clientModifiedAtMillis
            if (sameIntent && !confirmed) error("KelmaSync changed a stable note mark intent")
            val remoteWins = remoteMillis != null && !sameIntent &&
                (remoteMillis > local.clientModifiedAtMillis ||
                    (remoteMillis == local.clientModifiedAtMillis && remote.intentId > local.intentId))
            when {
                remoteNote == null && queries.countLocalNote(local.noteGuid).executeAsOne() == 0L ->
                    queries.deleteLocalNoteMark(local.noteGuid)
                confirmed || remoteWins -> queries.deleteLocalNoteMark(local.noteGuid)
                local.uploadState == "uploaded" -> queries.retryLocalNoteMark(local.intentId)
            }
        }
    }
}

private data class LocalReviewRetraction(
    val reviewId: Long,
    val intentId: String,
    val clientModifiedAtMillis: Long,
    val uploadState: String,
)

private data class PendingCardDueDateRow(
    val noteGuid: String,
    val cardOrd: Int,
    val cardId: Long,
    val dueAtMillis: Long,
    val clientModifiedAtMillis: Long,
    val uploadState: String = "pending",
)

private data class PendingCardResetRow(
    val noteGuid: String,
    val cardOrd: Int,
    val cardId: Long,
    val resetThroughReviewId: Long,
    val clientModifiedAtMillis: Long,
)
