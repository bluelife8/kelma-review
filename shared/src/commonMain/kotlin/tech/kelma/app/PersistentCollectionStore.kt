package tech.kelma.app

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import tech.kelma.db.KelmaDatabase

class PersistentCollectionStore(
    private val database: KelmaDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val scheduler: SchedulingEngine = FsrsScheduler,
    private val credentialVault: CredentialVault = InMemoryCredentialVault(),
    private val mediaCache: MediaCache = NoOpMediaCache,
) {
    private val queries = database.kelmaQueries
    private val studyDayPolicies = StudyDayPolicyPersistence(database, json)
    private val localDeckOperations = LocalDeckOperations(database)
    private val localNoteActions = LocalNoteActions(database, json, ::loadCollection, ::loadLocalContent)
    private val scheduleProjection = LocalScheduleProjection(queries, json, scheduler)
    private val collectionWriter = DownloadedCollectionWriter(queries, json, scheduleProjection::rebuild)
    private val mediaAttachments = MediaAttachmentPersistence(queries, json, mediaCache)
    private val collectionImports = CollectionImportPersistence(
        database,
        json,
        scheduler,
        scheduleProjection::rebuild,
        mediaAttachments,
    )
    private val deckPresets = DeckPresetPersistence(database, json, scheduleProjection::rebuild)
    private val schedulerProfiles = SchedulerProfilePersistence(database, json, scheduleProjection::rebuild)
    private val schedulerOptimizer = SchedulerOptimizerPersistence(database, json, schedulerProfiles)
    private val plugins = PluginPersistence(database, json)
    private val pluginRendererAssignments = PluginRendererAssignmentPersistence(database)
    private val browseIndex = BrowseIndexPersistence(database)
    private val syncOutbox = SyncOutboxPersistence(
        database,
        json,
        localDeckOperations,
        // Upload planning and outbox reconciliation only read content/scheduling metadata, never the
        // review history, so skip loading the revlog on these twice-per-cycle hot paths.
        { loadDownloadedCollection(queries, json, includeReviews = false) },
        ::loadLocalContent,
        mediaAttachments::loadDownloadedBytes,
    )
    private val stringList = ListSerializer(String.serializer())

    fun load(
        nowMillis: Long = currentEpochMillis(),
        recoverOptimizerJobs: Boolean = false,
    ): StoredAppState {
        mediaAttachments.repairCache()
        database.transaction {
            queries.clearBrowseQueryTerms()
            queries.backfillLocalReviewPortableIdentities()
            scheduleProjection.rebuild()
        }
        return StoredAppState(
            auth = loadAuth(),
            collection = loadCollection(),
            localContent = loadLocalContent(),
            localReviews = loadLocalReviews(nowMillis),
            schedulerProfile = schedulerProfiles.load(),
            studyDayPolicy = studyDayPolicies.load(),
            schedulerOptimizer = schedulerOptimizer.load(
                recoverInterrupted = recoverOptimizerJobs,
                nowMillis = nowMillis,
            ),
        )
    }

    internal fun appendSyncLog(progress: SyncProgress, nowMillis: Long = currentEpochMillis()): List<SyncLogEntry> {
        val safeMessage = sanitizeSyncLogMessage(progress.message)
        database.transaction {
            if (progress.replaceLatest) {
                queries.updateLatestSyncLogEntry(
                    occurredAt = nowMillis,
                    level = progress.level.name,
                    message = safeMessage,
                    phase = progress.phase,
                )
            } else {
                queries.insertSyncLogEntry(nowMillis, progress.level.name, progress.phase, safeMessage)
                queries.pruneSyncLogEntries(500)
            }
        }
        return loadSyncLog()
    }

    internal fun loadSyncLog(limit: Long = 500): List<SyncLogEntry> =
        queries.selectSyncLogEntries(limit) { id, occurredAt, level, phase, message ->
            SyncLogEntry(id, occurredAt, SyncLogLevel.valueOf(level), phase, sanitizeSyncLogMessage(message))
        }.executeAsList()

    internal fun clearSyncLog() {
        queries.clearSyncLogEntries()
    }

    internal suspend fun prepareBrowseIndex(collection: SyncedCollection) {
        browseIndex.prepare(collection)
    }

    internal suspend fun loadBrowsePage(
        collection: SyncedCollection,
        request: BrowsePageRequest,
        rebuildIfDirty: Boolean = true,
    ): BrowsePage = browseIndex.loadPage(collection, request, rebuildIfDirty)

    fun saveSignedInState(
        auth: StoredSyncAuth,
        collection: SyncedCollection,
        nowMillis: Long = currentEpochMillis(),
    ): LocalReviewSnapshot {
        val existingCredential = queries.selectAuth { clientId, endpoint, username ->
            Triple(clientId, endpoint, username)
        }.executeAsOneOrNull()
        val previousToken = existingCredential?.first?.let(credentialVault::read)
        if (existingCredential != null && existingCredential.first != auth.clientId) mediaAttachments.clearCache()
        mediaAttachments.cache(collection)
        credentialVault.write(auth.clientId, auth.token)
        try {
            database.transaction {
                val existingAccount = existingCredential?.let { it.second to it.third }
                if (existingAccount != null && existingAccount != (auth.endpoint to auth.username)) {
                    queries.clearLocalSchedules()
                    queries.clearLocalCardFlags()
                    queries.clearLocalCardBuries()
                    queries.clearLocalNoteBuries()
                    queries.clearLocalCardDueOverrides()
                    queries.clearLocalCardResets()
                    queries.clearLocalReviewEvents()
                    queries.clearLocalCards()
                    queries.clearLocalNotes()
                    queries.clearLocalNotetypes()
                    queries.clearLocalDecks()
                    queries.clearLocalDeckOptions()
                    queries.clearLocalMedia()
                    deckPresets.clear()
                    queries.clearLocalDeckOverrides()
                    queries.clearLocalNoteOverrides()
                    queries.clearLocalNoteSync()
                    queries.clearLocalDeckSync()
                    schedulerProfiles.clearAccount()
                    studyDayPolicies.clearAccount()
                    schedulerOptimizer.clear()
                    pluginRendererAssignments.clear()
                }
                queries.upsertAuth(auth.clientId, auth.endpoint, auth.username)
                collectionWriter.replace(collection, syncOutbox::reconcileUploadedRows)
                queries.pruneLocalCardDueOverrides()
                queries.pruneLocalNoteBuries()
            }
        } catch (failure: Throwable) {
            try {
                if (existingCredential?.first == auth.clientId && previousToken != null) {
                    credentialVault.write(auth.clientId, previousToken)
                } else {
                    credentialVault.delete(auth.clientId)
                }
            } catch (restoreFailure: Throwable) {
                failure.addSuppressed(restoreFailure)
            }
            throw failure
        }
        mediaAttachments.clearSyncStaging(collection)
        existingCredential?.first
            ?.takeIf { it != auth.clientId }
            ?.let(credentialVault::delete)
        return loadLocalReviews(nowMillis)
    }

    fun replaceCollection(
        collection: SyncedCollection,
        nowMillis: Long = currentEpochMillis(),
        mediaFilenamesToCache: Set<String>? = null,
        preserveDownloadedMedia: Boolean = false,
    ): LocalReviewSnapshot {
        mediaAttachments.cache(collection, mediaFilenamesToCache)
        database.transaction {
            collectionWriter.replace(
                collection,
                syncOutbox::reconcileUploadedRows,
                preserveMedia = preserveDownloadedMedia,
            )
            queries.pruneLocalCardDueOverrides()
            queries.pruneLocalNoteBuries()
        }
        mediaAttachments.clearSyncStaging(collection, mediaFilenamesToCache)
        return loadLocalReviews(nowMillis)
    }

    internal fun replaceCollectionIncrementally(
        previous: SyncedCollection,
        collection: SyncedCollection,
        nowMillis: Long = currentEpochMillis(),
        mediaFilenamesToCache: Set<String>? = null,
    ): LocalReviewSnapshot {
        mediaAttachments.cache(collection, mediaFilenamesToCache)
        database.transaction {
            collectionWriter.replaceIncrementally(previous, collection, syncOutbox::reconcileUploadedRows)
            queries.pruneLocalCardDueOverrides()
            queries.pruneLocalNoteBuries()
        }
        mediaAttachments.clearSyncStaging(collection, mediaFilenamesToCache)
        return loadLocalReviews(nowMillis)
    }

    internal fun advanceSyncCursor(
        serverTime: String?,
        nowMillis: Long = currentEpochMillis(),
    ): LocalReviewSnapshot {
        database.transaction {
            queries.upsertServerTime(serverTime)
            if (syncOutbox.hasUploadedRows()) {
                syncOutbox.reconcileUploadedRows()
                scheduleProjection.rebuild()
            }
        }
        return loadLocalReviews(nowMillis)
    }

    fun saveDeckOptions(
        deckName: String,
        options: DeckOptions,
        nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot {
        val normalizedName = normalizeDeckName(deckName)
        val validated = options.validated()
        deckPresets.saveDeckOptions(normalizedName, validated, nowMillis)
        return loadLocalContent()
    }

    fun createDeckOptionsPreset(
        deckName: String,
        name: String,
        options: DeckOptions,
        nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot = deckPresets.create(normalizeDeckName(deckName), name, options, nowMillis)

    fun cloneDeckOptionsPreset(
        deckName: String,
        sourcePresetId: String,
        name: String,
        nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot = deckPresets.clone(normalizeDeckName(deckName), sourcePresetId, name, nowMillis)

    fun assignDeckOptionsPreset(
        deckName: String,
        presetId: String?,
        nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot = deckPresets.assign(normalizeDeckName(deckName), presetId, nowMillis)

    fun renameDeckOptionsPreset(
        presetId: String,
        name: String,
        nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot = deckPresets.rename(presetId, name, nowMillis)

    fun deleteDeckOptionsPreset(
        presetId: String,
        nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot = deckPresets.delete(presetId, nowMillis)

    fun saveMediaAttachment(
        requestedFilename: String,
        mimeType: String,
        bytes: ByteArray,
        nowMillis: Long = currentEpochMillis(),
    ): SavedMediaAttachment = mediaAttachments.save(requestedFilename, mimeType, bytes, nowMillis)

    fun queueMissingRemoteMedia(
        filenames: Set<String>,
        nowMillis: Long = currentEpochMillis(),
    ): Int = mediaAttachments.queueRemoteRepairs(filenames, nowMillis)

    fun loadLocalReviewExports(): List<ImmutableReviewExport> = queries.selectAllLocalReviewEvents {
            _, _, noteGuid, cardOrdinal, _, rating, reviewedAt, _, duration, _, _, _, reviewId, _, _ ->
        ImmutableReviewExport(
            reviewId = reviewId.takeIf { it > 0L } ?: reviewedAt,
            noteGuid = noteGuid,
            cardOrdinal = cardOrdinal.toInt(),
            rating = Rating.entries.first { it.name == rating },
            durationMillis = duration,
        )
    }.executeAsList()

    fun importCollection(
        plan: CollectionImportPlan,
        nowMillis: Long = currentEpochMillis(),
    ): CollectionImportResult {
        val result = collectionImports.import(plan, nowMillis)
        plan.reviews.maxOfOrNull(ImportedReview::reviewId)?.let(schedulerOptimizer::markHistoryChanged)
        return result
    }

    fun createLocalDeck(name: String, nowMillis: Long = currentEpochMillis()): LocalContentSnapshot {
        localDeckOperations.create(name, nowMillis)
        return loadLocalContent()
    }

    fun renameLocalDeck(
        oldName: String,
        newName: String,
        nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot {
        database.transaction {
            localDeckOperations.rename(oldName, newName, nowMillis)
            pluginRendererAssignments.renameDeck(oldName, newName)
        }
        return loadLocalContent()
    }

    fun deleteLocalDeck(
        name: String,
        nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot {
        database.transaction {
            localDeckOperations.delete(name, nowMillis)
            pluginRendererAssignments.deleteDeck(name)
        }
        return loadLocalContent()
    }

    fun addLocalNote(
        draft: AddNoteDraft,
        nowMillis: Long = currentEpochMillis(),
        noteGuid: String = "local-${randomUuidString()}",
    ): AddedLocalNote {
        val deckName = draft.deckName.trim()
        val front = draft.front.trim()
        val back = draft.back.trim()
        require(deckName.isNotEmpty()) { "Choose or enter a deck" }
        require(front.isNotEmpty()) { "Front cannot be empty" }
        require(back.isNotEmpty()) { "Back cannot be empty" }
        val ords = draft.cardOrds.ifEmpty { listOf(0) }.distinct()
        val tags = draft.tags.map(String::trim).filter(String::isNotEmpty).distinct()
        database.transaction {
            deckHierarchyNames(deckName).forEach { queries.insertLocalDeck(it, nowMillis) }
            queries.insertLocalNote(
                noteGuid,
                draft.notetypeId,
                json.encodeToString(stringList, listOf(front, back)),
                json.encodeToString(stringList, tags),
                nowMillis,
            )
            ords.forEach { ord ->
                queries.insertLocalCard(localCardId(noteGuid, ord), noteGuid, deckName, ord.toLong(), nowMillis)
            }
            queries.upsertLocalNoteSync(noteGuid, "upsert", "", nowMillis)
            queries.markBrowseIndexDirty()
        }
        return AddedLocalNote(localCardId(noteGuid, ords.first()), noteGuid, loadLocalContent())
    }

    /** Updates a locally authored note's fields, tags, and deck in place, preserving its schedules. */
    fun updateLocalNote(
        noteGuid: String,
        draft: AddNoteDraft,
        nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot {
        val deckName = draft.deckName.trim()
        require(deckName.isNotEmpty()) { "Choose or enter a deck" }
        require(draft.front.isNotBlank()) { "Front cannot be empty" }
        require(draft.back.isNotBlank()) { "Back cannot be empty" }
        val tags = draft.tags.map(String::trim).filter(String::isNotEmpty).distinct()
        database.transaction {
            deckHierarchyNames(deckName).forEach { queries.insertLocalDeck(it, nowMillis) }
            queries.updateLocalNote(
                fieldsJson = json.encodeToString(stringList, listOf(draft.front.trim(), draft.back.trim())),
                tagsJson = json.encodeToString(stringList, tags),
                guid = noteGuid,
            )
            queries.updateLocalCardDeck(deckName = deckName, guid = noteGuid)
            queries.upsertLocalNoteSync(noteGuid, "upsert", "", nowMillis)
            queries.markBrowseIndexDirty()
        }
        return loadLocalContent()
    }

    /** Updates ordered fields and tags, overlaying downloaded rows so edits survive subsequent pulls. */
    fun updateNoteFields(
        noteGuid: String, fields: List<String>, tags: List<String>, nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot {
        require(fields.isNotEmpty() && fields.first().isNotBlank()) { "The first field cannot be empty" }
        val normalizedTags = tags.map(String::trim).filter(String::isNotEmpty).distinct()
        val fieldsJson = json.encodeToString(stringList, fields)
        val tagsJson = json.encodeToString(stringList, normalizedTags)
        database.transaction {
            when {
                queries.countLocalNote(noteGuid).executeAsOne() > 0 -> {
                    queries.updateLocalNote(fieldsJson = fieldsJson, tagsJson = tagsJson, guid = noteGuid)
                    queries.upsertLocalNoteSync(noteGuid, "upsert", "", nowMillis)
                }
                queries.countSyncNote(noteGuid).executeAsOne() > 0 -> {
                    queries.upsertLocalNoteOverride(noteGuid, fieldsJson, tagsJson, nowMillis)
                    queries.upsertLocalNoteSync(
                        noteGuid,
                        "upsert",
                        queries.selectSyncNoteChecksum(noteGuid).executeAsOne(),
                        nowMillis,
                    )
                }
                else -> error("This note no longer exists")
            }
            queries.markBrowseIndexDirty()
        }
        return loadLocalContent()
    }

    fun setNoteMarked(
        noteGuid: String,
        marked: Boolean,
        nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot {
        val note = loadCollection().withLocalContent(loadLocalContent()).notes[noteGuid]
            ?: error("This note no longer exists")
        val tags = note.tags.filterNot { it.equals("marked", ignoreCase = true) }
            .let { if (marked) it + "marked" else it }
        return updateNoteFields(noteGuid, note.fields, tags, nowMillis)
    }

    fun createNoteCopy(
        noteGuid: String,
        nowMillis: Long = currentEpochMillis(),
        copyGuid: String = "local-${randomUuidString()}",
    ): AddedLocalNote = localNoteActions.createCopy(noteGuid, nowMillis, copyGuid)

    fun deleteLocalNote(
        noteGuid: String,
        nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot = localNoteActions.delete(noteGuid, nowMillis)

    fun setCardFlag(cardId: Long, flag: Int): LocalContentSnapshot {
        require(flag in 0..7) { "Card flag must be between 0 and 7" }
        database.transaction {
            if (flag == 0) queries.deleteLocalCardFlag(cardId)
            else queries.upsertLocalCardFlag(cardId, flag.toLong())
        }
        return loadLocalContent()
    }

    fun buryCard(
        cardId: Long,
        nowMillis: Long = currentEpochMillis(),
    ): LocalReviewSnapshot {
        queries.upsertLocalCardBury(cardId, studyDayAt(nowMillis, studyDayPolicies.load()))
        return loadLocalReviews(nowMillis)
    }

    fun buryNote(
        noteGuid: String,
        nowMillis: Long = currentEpochMillis(),
    ): LocalReviewSnapshot = localNoteActions.bury(noteGuid, nowMillis, studyDayPolicies.load())

    fun setCardDueDate(
        cardId: Long,
        dueAtMillis: Long,
        nowMillis: Long = currentEpochMillis(),
    ): LocalReviewSnapshot {
        require(dueAtMillis % MillisPerDay == 0L) { "Due date must begin at a UTC day boundary" }
        val card = loadCollection().cards[cardId] ?: loadLocalContent().cards[cardId]
            ?: error("Card $cardId no longer exists")
        queries.upsertLocalCardDueOverride(
            noteGuid = card.noteGuid,
            cardOrd = card.ord.toLong(),
            cardId = card.cardId,
            dueAtMillis = dueAtMillis,
            clientModifiedAt = nowMillis,
        )
        return loadLocalReviews(nowMillis)
    }

    fun resetCard(
        cardId: Long,
        nowMillis: Long = currentEpochMillis(),
    ): LocalReviewSnapshot {
        val card = loadCollection().cards[cardId] ?: loadLocalContent().cards[cardId]
            ?: error("Card $cardId no longer exists")
        database.transaction {
            queries.upsertLocalCardDueOverride(
                noteGuid = card.noteGuid,
                cardOrd = card.ord.toLong(),
                cardId = card.cardId,
                dueAtMillis = 0L,
                clientModifiedAt = nowMillis,
            )
            val resetThrough = maxOf(
                nowMillis,
                queries.selectMaximumLocalReviewId().executeAsOne().max ?: 0L,
                queries.selectMaximumServerReviewId().executeAsOne().max ?: 0L,
                queries.selectMaximumLocalCardResetReviewId().executeAsOne().max ?: 0L,
                queries.selectMaximumSyncCardResetReviewId().executeAsOne().max ?: 0L,
            )
            queries.upsertLocalCardReset(
                card.noteGuid,
                card.ord.toLong(),
                card.cardId,
                resetThrough,
                nowMillis,
            )
            scheduleProjection.rebuild()
        }
        return loadLocalReviews(nowMillis)
    }

    fun setCardsStudyState(
        cards: List<SyncCard>,
        state: CardStudyState,
        nowMillis: Long = currentEpochMillis(),
    ): LocalContentSnapshot {
        require(cards.isNotEmpty()) { "At least one card is required" }
        database.transaction {
            cards.distinctBy { cardStudyKey(it.noteGuid, it.ord) }.forEach { card ->
                queries.upsertLocalCardStudyState(
                    noteGuid = card.noteGuid,
                    cardOrd = card.ord.toLong(),
                    cardId = card.cardId,
                    studyState = state.name.lowercase(),
                    clientModifiedAt = nowMillis,
                )
            }
            queries.markBrowseIndexDirty()
        }
        return loadLocalContent()
    }

    fun recordReview(
        card: SyncCard,
        rating: Rating,
        reviewedAtMillis: Long = currentEpochMillis(),
        durationMillis: Long = 0,
    ): LocalReviewChange = recordReviewInternal(card, rating, reviewedAtMillis, durationMillis, null, null)

    internal fun recordReviewIncrementally(
        card: SyncCard,
        rating: Rating,
        currentSnapshot: LocalReviewSnapshot,
        options: DeckOptions,
        reviewedAtMillis: Long = currentEpochMillis(),
        durationMillis: Long = 0,
    ): LocalReviewChange = recordReviewInternal(
        card,
        rating,
        reviewedAtMillis,
        durationMillis,
        currentSnapshot,
        options.validated(),
    )

    private fun recordReviewInternal(
        card: SyncCard,
        rating: Rating,
        reviewedAtMillis: Long,
        durationMillis: Long,
        currentSnapshot: LocalReviewSnapshot?,
        optionsOverride: DeckOptions?,
    ): LocalReviewChange {
        lateinit var delta: RecordedReviewDelta
        database.transaction {
            val localDueDate = queries.selectLocalCardDueOverride(
                noteGuid = card.noteGuid,
                cardOrd = card.ord.toLong(),
            ) { _, _, _, dueAtMillis, _, _ -> dueAtMillis }.executeAsOneOrNull()
            val clearedDueDateOverride = (localDueDate ?: card.dueDateOverrideMillis) > 0L
            if (clearedDueDateOverride) {
                queries.upsertLocalCardDueOverride(
                    noteGuid = card.noteGuid,
                    cardOrd = card.ord.toLong(),
                    cardId = card.cardId,
                    dueAtMillis = 0L,
                    clientModifiedAt = reviewedAtMillis,
                )
            }
            val options = optionsOverride
                ?: queries.selectLocalDeckOptionsForDeck(card.deckName).executeAsOneOrNull()
                    ?.let { json.decodeFromString<DeckOptions>(it).validated() }
                ?: schedulerProfiles.activeOptions()
            val before = loadLocalSchedule(queries, card.cardId)
            val wasNew = before == null
            val policy = studyDayPolicies.load()
            val studyDay = studyDayAt(reviewedAtMillis, policy)
            val quotaSnapshot = currentSnapshot
                ?.takeIf { it.studyDay == studyDay }
                ?: loadLocalReviewSnapshot(queries, reviewedAtMillis, policy)
            val reviewCardKey = reviewLimitCardKey(card.noteGuid, card.ord, card.cardId)
            val consumedReviewLimit = before?.phase == ReviewPhase.Review &&
                reviewCardKey !in quotaSnapshot.reviewLimitConsumedCardKeysToday
            val boundedDuration = durationMillis.coerceIn(0, options.maximumAnswerSeconds * 1_000L)
            val lastReviewId = queries.selectMaximumReviewMutationId { value -> value ?: 0L }
                .executeAsOne()
            val reviewId = maxOf(reviewedAtMillis, lastReviewId + 1L)
            val schedule = scheduler.review(
                card.copy(scheduling = JsonObject(emptyMap())),
                before,
                rating,
                reviewedAtMillis,
                null,
                options,
            ).alignedToStudyDay(policy)
            upsertLocalSchedule(queries, schedule)
            queries.insertLocalReviewEvent(
                card.cardId,
                card.noteGuid,
                card.ord.toLong(),
                card.deckName,
                rating.name,
                reviewedAtMillis,
                studyDay,
                boundedDuration,
                before?.let { json.encodeToString(it) },
                json.encodeToString(schedule),
                if (wasNew) 1L else 0L,
                if (consumedReviewLimit) 1L else 0L,
                reviewId,
            )
            schedulerOptimizer.markHistoryChanged(reviewId)
            val isLocalCard = queries.countLocalCardById(card.cardId).executeAsOne() > 0L
            delta = RecordedReviewDelta(
                schedule = schedule,
                noteGuid = card.noteGuid,
                cardOrd = card.ord,
                deckName = card.deckName,
                reviewedAtMillis = reviewedAtMillis,
                wasNew = wasNew,
                consumedReviewLimit = consumedReviewLimit,
                clearedDueDateOverride = clearedDueDateOverride,
                pendingDownloadedCardId = card.cardId.takeUnless { isLocalCard },
            )
        }
        val policy = studyDayPolicies.load()
        val snapshot = currentSnapshot
            ?.takeIf { it.studyDay == studyDayAt(reviewedAtMillis, policy) }
            ?.applying(delta, policy)
            ?: loadLocalReviews(reviewedAtMillis)
        return LocalReviewChange(delta.schedule, snapshot)
    }

    fun undoLastReview(
        deckName: String,
        nowMillis: Long = currentEpochMillis(),
    ): UndoneReview? {
        var undoneCardId: Long? = null
        database.transaction {
            val event = queries.selectLatestLocalReviewEvent(deckName) { eventId, cardId, beforeJson ->
                Triple(eventId, cardId, beforeJson)
            }.executeAsOneOrNull() ?: return@transaction
            queries.deleteLocalReviewEvent(event.first)
            schedulerOptimizer.markHistoryChanged(nowMillis)
            scheduleProjection.rebuild()
            undoneCardId = event.second
        }
        return undoneCardId?.let { cardId ->
            UndoneReview(cardId, loadLocalReviews(nowMillis))
        }
    }

    fun signOutPreservingCollection() {
        val clientId = queries.selectAuth { storedClientId, _, _ -> storedClientId }.executeAsOneOrNull()
        database.transaction { queries.clearAuth() }
        clientId?.let(credentialVault::delete)
    }

    /** Clears only server-downloaded state so the next sync starts from an empty cursor. */
    fun resetDownloadedCollectionForRedownload(
        nowMillis: Long = currentEpochMillis(),
    ): StoredAppState {
        database.transaction {
            collectionWriter.clear()
            queries.clearLocalSchedules()
        }
        mediaAttachments.clearCache()
        return load(nowMillis)
    }

    fun clearAll() {
        queries.selectAuth { clientId, _, _ -> clientId }.executeAsOneOrNull()
            ?.let(credentialVault::delete)
        database.transaction {
            queries.clearAuth()
            collectionWriter.clear()
            queries.clearLocalSchedules()
            queries.clearLocalCardFlags()
            queries.clearLocalCardBuries()
            queries.clearLocalNoteBuries()
            queries.clearLocalCardDueOverrides()
            queries.clearLocalCardResets()
            queries.clearLocalCardStudyStates()
            queries.clearLocalReviewEvents()
            queries.clearLocalCards()
            queries.clearLocalNotes()
            queries.clearLocalNotetypes()
            queries.clearLocalDecks()
            queries.clearLocalDeckOptions()
            queries.clearLocalMedia()
            deckPresets.clear()
            queries.clearLocalDeckOverrides()
            queries.clearLocalNoteOverrides()
            queries.clearLocalNoteSync()
            queries.clearLocalDeckSync()
            schedulerProfiles.clearAccount()
            studyDayPolicies.clearAccount()
            schedulerOptimizer.clear()
            pluginRendererAssignments.clear()
        }
        mediaAttachments.clearCache()
    }

    private fun loadAuth(): StoredSyncAuth? {
        val metadata = queries.selectAuth { clientId, endpoint, username ->
            Triple(clientId, endpoint, username)
        }.executeAsOneOrNull() ?: return null
        val token = credentialVault.read(metadata.first) ?: return null
        return StoredSyncAuth(token, metadata.first, metadata.second, metadata.third)
    }

    private fun loadCollection(): SyncedCollection = loadDownloadedCollection(queries, json)

    fun prepareSyncUpload(): SyncUploadPlan = syncOutbox.prepare().copy(
        schedulerProfile = schedulerProfiles.prepare(),
    )

    internal fun loadPluginRendererAssignments(): PluginRendererAssignmentState =
        pluginRendererAssignments.load()

    internal fun setPluginRendererAssignment(
        scope: PluginRendererScope,
        targetId: String,
        rendererId: String?,
    ): PluginRendererAssignmentState = pluginRendererAssignments.set(scope, targetId, rendererId)

    fun applySyncPushResult(result: SyncPushResult) {
        syncOutbox.apply(result)
        schedulerProfiles.applyPush(result)
    }

    fun resolveSyncConflict(
        conflict: SyncUploadConflict,
        keepLocal: Boolean,
        nowMillis: Long = currentEpochMillis(),
    ) {
        if (conflict.kind == SchedulerProfileConflictKind) {
            schedulerProfiles.resolveConflict(keepLocal, nowMillis)
        } else {
            syncOutbox.resolve(conflict, keepLocal, nowMillis)
            database.transaction { scheduleProjection.rebuild() }
        }
    }

    fun loadSyncConflicts(): List<SyncUploadConflict> = buildList {
        addAll(syncOutbox.conflicts())
        val state = schedulerProfiles.load()
        if (state.syncStatus == SchedulerProfileSyncStatus.Conflict) {
            add(
                SyncUploadConflict(
                    kind = SchedulerProfileConflictKind,
                    resourceKey = "account",
                    serverJson = state.cloud?.let(json::encodeToString).orEmpty(),
                ),
            )
        }
    }

    fun loadSchedulerProfile(): SchedulerProfileState = schedulerProfiles.load()

    fun loadStudyDayPolicy(): AccountStudyDayPolicy = studyDayPolicies.load()

    fun observeCloudStudyDayPolicy(
        policy: AccountStudyDayPolicy,
        nowMillis: Long = currentEpochMillis(),
    ): AccountStudyDayPolicy {
        val previous = studyDayPolicies.load()
        lateinit var observed: AccountStudyDayPolicy
        database.transaction {
            observed = studyDayPolicies.observeCloud(policy, nowMillis)
            if (
                previous.timezoneId != observed.timezoneId ||
                previous.dayStartHour != observed.dayStartHour
            ) {
                scheduleProjection.rebuild()
            }
        }
        return observed
    }

    fun observeCloudSchedulerProfile(
        response: SchedulerProfileResponse,
        nowMillis: Long = currentEpochMillis(),
    ): SchedulerProfileState {
        schedulerProfiles.observeCloud(response, nowMillis)
        return schedulerProfiles.load()
    }

    fun applyAccountSchedulerProfile(
        settings: SchedulerProfileSettings,
        publishToCloud: Boolean,
        nowMillis: Long = currentEpochMillis(),
    ): SchedulerProfileState = schedulerProfiles.applyLocal(settings, publishToCloud, nowMillis)

    fun applyCloudSchedulerProfileLocally(
        nowMillis: Long = currentEpochMillis(),
    ): SchedulerProfileState = schedulerProfiles.applyCloudLocally(nowMillis)

    fun loadSchedulerOptimizer(recoverInterrupted: Boolean = false): SchedulerOptimizerState =
        schedulerOptimizer.load(recoverInterrupted)

    fun prepareSchedulerOptimization(
        timezoneId: String = kotlinx.datetime.TimeZone.currentSystemDefault().id,
        dayStartHour: Int = 4,
        nowMillis: Long = currentEpochMillis(),
    ): SchedulerOptimizerState = schedulerOptimizer.prepare(timezoneId, dayStartHour, nowMillis)

    fun runSchedulerOptimization(jobId: String): SchedulerOptimizerState =
        schedulerOptimizer.run(jobId)

    fun cancelSchedulerOptimization(
        jobId: String,
        nowMillis: Long = currentEpochMillis(),
    ): SchedulerOptimizerState = schedulerOptimizer.requestCancellation(jobId, nowMillis)

    fun interruptSchedulerOptimization(
        jobId: String,
        nowMillis: Long = currentEpochMillis(),
    ): SchedulerOptimizerState = schedulerOptimizer.interrupt(jobId, nowMillis)

    fun discardSchedulerOptimizerCandidate(
        candidateId: String,
        nowMillis: Long = currentEpochMillis(),
    ): SchedulerOptimizerState = schedulerOptimizer.discardCandidate(candidateId, nowMillis)

    fun applySchedulerOptimizerCandidate(
        candidateId: String,
        publishToCloud: Boolean,
        nowMillis: Long = currentEpochMillis(),
    ): Pair<SchedulerOptimizerState, SchedulerProfileState> =
        schedulerOptimizer.applyCandidate(candidateId, publishToCloud, nowMillis)

    fun loadLocalContent(): LocalContentSnapshot = loadLocalContentSnapshot(queries, json)

    internal fun loadDownloadedMedia(filename: String): ByteArray? =
        mediaAttachments.loadDownloadedBytes(filename)

    internal fun hydrateMediaForExport(collection: SyncedCollection): SyncedCollection = collection.copy(
        media = collection.media.mapValues { (_, file) ->
            if (file.bytes.isNotEmpty() || file.sizeBytes == 0L) {
                file
            } else {
                val bytes = mediaCache.read(file.filename)
                    ?: error("Downloaded media is unavailable: ${file.filename}")
                require(bytes.size.toLong() == file.sizeBytes) {
                    "Downloaded media size changed: ${file.filename}"
                }
                file.copy(bytes = bytes)
            }
        },
    )

    fun loadLocalReviews(nowMillis: Long = currentEpochMillis()): LocalReviewSnapshot =
        loadLocalReviewSnapshot(queries, nowMillis, studyDayPolicies.load())

    fun listInstalledPlugins(): List<InstalledPlugin> = plugins.list()

    internal fun createLuaPluginHost(
        commands: PluginCommandRegistry,
        events: PluginEventRegistry,
        renderers: PluginRendererRegistry,
    ): LuaPluginHost = LuaPluginHost(plugins, json, commands, events, renderers)

    fun installPluginManifest(
        manifest: PluginManifest,
        nowMillis: Long = currentEpochMillis(),
    ): InstalledPlugin = plugins.install(manifest, nowMillis)

    fun setPluginEnabled(
        pluginId: String,
        enabled: Boolean,
        nowMillis: Long = currentEpochMillis(),
    ): List<InstalledPlugin> = plugins.setEnabled(pluginId, enabled, nowMillis)

    fun uninstallPlugin(pluginId: String) = plugins.uninstall(pluginId)

    fun loadStudyStats(nowMillis: Long = currentEpochMillis()): StudyStats {
        val local = loadLocalContent()
        // Only card projections are needed here; loadStudyStatsReviews reads the history separately,
        // so avoid parsing the entire revlog through the full collection load.
        val cards = loadDownloadedCollection(queries, json, includeReviews = false)
            .withLocalContent(local).cards.values
        val policy = studyDayPolicies.load()
        val reviews = loadLocalReviewSnapshot(queries, nowMillis, policy)
        return calculateStudyStats(
            reviews = loadStudyStatsReviews(queries, policy),
            cards = cards,
            schedules = reviews.schedules,
            nowMillis = nowMillis,
            dueDateOverrides = reviews.dueDateOverrides,
            studyDayPolicy = policy,
        )
    }

}
