package tech.kelma.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class PulledLocalState(
    val content: LocalContentSnapshot,
    val reviews: LocalReviewSnapshot,
    val profile: SchedulerProfileState,
    val studyDayPolicy: AccountStudyDayPolicy,
    val conflicts: List<SyncUploadConflict>,
)

internal data class RemoteSyncState(
    val report: PullReport,
    val profile: SchedulerProfileResponse,
    val studyDayPolicy: AccountStudyDayPolicy,
)

data class CompletedSyncCycle(
    val report: PullReport,
    val pushed: SyncPushResult,
    val localContent: LocalContentSnapshot,
    val localReviews: LocalReviewSnapshot,
    val schedulerProfile: SchedulerProfileState,
    val studyDayPolicy: AccountStudyDayPolicy,
    val conflicts: List<SyncUploadConflict>,
)

suspend fun runSyncCycle(
    client: KelmaSyncService,
    store: PersistentCollectionStore,
    token: String,
    current: SyncedCollection,
    onProgress: suspend (SyncProgress) -> Unit = {},
    clock: () -> Long = ::currentEpochMillis,
): CompletedSyncCycle {
    val cycleStarted = clock()
    val preflightStarted = clock()
    onProgress(
        SyncProgress(
            phase = "PREFLIGHT",
            message = "Pulling changes since ${current.serverTime ?: "the beginning"}",
        ),
    )
    val preflightRows = mutableSetOf<SyncPullResource>()
    val pulledIncoming = pullRemoteState(client, token, current) { progress ->
        onProgress(progress.toSyncProgress(replaceLatest = !preflightRows.add(progress.resource)))
    }
    val incoming = pulledIncoming.copy(
        studyDayPolicy = ensureStudyDayPolicy(client, store, token, pulledIncoming.studyDayPolicy),
    )
    onProgress(
        SyncProgress(
            phase = "PREFLIGHT",
            message = "Server pull finished in ${elapsed(preflightStarted, clock)} · " +
                "${incoming.report.downloaded} changes · ${incoming.report.removed} removals · " +
                "${incoming.report.remoteMediaMissing.size} media repairs",
            replaceLatest = true,
        ),
    )

    val localApplyStarted = clock()
    onProgress(SyncProgress(phase = "LOCAL", message = "Applying preflight and updating affected schedules"))
    val pulledState = withContext(Dispatchers.Default) {
        val studyDayPolicy = store.observeCloudStudyDayPolicy(incoming.studyDayPolicy)
        val reviews = if (incoming.report.downloaded == 0 && incoming.report.removed == 0) {
            store.advanceSyncCursor(incoming.report.collection.serverTime)
        } else {
            store.replaceCollectionIncrementally(
                previous = current,
                collection = incoming.report.collection,
                mediaFilenamesToCache = incoming.report.downloadedMediaFilenames,
            )
        }
        store.queueMissingRemoteMedia(incoming.report.remoteMediaMissing)
        val profile = store.observeCloudSchedulerProfile(incoming.profile)
        PulledLocalState(
            store.loadLocalContent(),
            reviews,
            profile,
            studyDayPolicy,
            store.loadSyncConflicts(),
        )
    }
    onProgress(
        SyncProgress(
            SyncLogLevel.Success,
            "LOCAL",
            "Applied preflight in ${elapsed(localApplyStarted, clock)} · " +
                "${incoming.report.collection.deckRecords.size} decks · " +
                "${incoming.report.collection.notes.size} notes · " +
                "${incoming.report.collection.cards.size} cards · " +
                "${incoming.report.collection.reviews.size} reviews",
            replaceLatest = true,
        ),
    )

    val planStarted = clock()
    val plan = withContext(Dispatchers.Default) { store.prepareSyncUpload() }
    onProgress(SyncProgress(phase = "OUTBOX", message = "Prepared atomic snapshot in ${elapsed(planStarted, clock)}"))
    plan.summaryLines().forEach { onProgress(it) }
    if (plan.isEmpty) {
        onProgress(
            SyncProgress(
                SyncLogLevel.Success,
                "COMPLETE",
                "Up to date; nothing to upload · total ${elapsed(cycleStarted, clock)}",
            ),
        )
        return CompletedSyncCycle(
            incoming.report,
            SyncPushResult(),
            pulledState.content,
            pulledState.reviews,
            pulledState.profile,
            pulledState.studyDayPolicy,
            pulledState.conflicts,
        )
    }

    val uploadStarted = clock()
    onProgress(SyncProgress(phase = "UPLOAD", message = "Uploading the atomic outbox snapshot"))
    val progressRows = mutableSetOf<SyncPushResource>()
    val pushed = client.push(token, plan) { progress ->
        onProgress(progress.toSyncProgress(replaceLatest = !progressRows.add(progress.resource)))
    }
    onProgress(
        SyncProgress(
            phase = "UPLOAD",
            message = "Server upload finished in ${elapsed(uploadStarted, clock)} · " +
                "${pushed.uploadedCount} outbox changes acknowledged · ${pushed.conflicts.size} conflicts",
            replaceLatest = true,
        ),
    )

    val acknowledgementStarted = clock()
    onProgress(SyncProgress(phase = "LOCAL", message = "Saving server acknowledgements"))
    withContext(Dispatchers.Default) { store.applySyncPushResult(pushed) }
    val uploadBase = incoming.report.collection
    onProgress(
        SyncProgress(
            SyncLogLevel.Success,
            "LOCAL",
            "Saved acknowledgements in ${elapsed(acknowledgementStarted, clock)}",
            replaceLatest = true,
        ),
    )

    val confirmationStarted = clock()
    onProgress(SyncProgress(phase = "CONFIRM", message = "Pulling authoritative server confirmation"))
    val confirmationRows = mutableSetOf<SyncPullResource>()
    val pulledConfirmation = pullRemoteState(client, token, uploadBase) { progress ->
        onProgress(progress.toSyncProgress(replaceLatest = !confirmationRows.add(progress.resource)))
    }
    val confirming = pulledConfirmation.copy(
        studyDayPolicy = ensureStudyDayPolicy(client, store, token, pulledConfirmation.studyDayPolicy),
    )
    onProgress(
        SyncProgress(
            phase = "CONFIRM",
            message = "Confirmation pull finished in ${elapsed(confirmationStarted, clock)} · " +
                "${confirming.report.downloaded} changes · ${confirming.report.removed} removals",
            replaceLatest = true,
        ),
    )

    val confirmationApplyStarted = clock()
    onProgress(SyncProgress(phase = "LOCAL", message = "Applying confirmation and updating affected schedules"))
    val completed = withContext(Dispatchers.Default) {
        val studyDayPolicy = store.observeCloudStudyDayPolicy(confirming.studyDayPolicy)
        val reviews = if (confirming.report.downloaded == 0 && confirming.report.removed == 0) {
            store.advanceSyncCursor(confirming.report.collection.serverTime)
        } else {
            store.replaceCollectionIncrementally(
                previous = uploadBase,
                collection = confirming.report.collection,
                mediaFilenamesToCache = confirming.report.downloadedMediaFilenames,
            )
        }
        store.queueMissingRemoteMedia(confirming.report.remoteMediaMissing)
        val profile = store.observeCloudSchedulerProfile(confirming.profile)
        CompletedSyncCycle(
            report = PullReport(
                collection = confirming.report.collection,
                downloaded = incoming.report.downloaded + confirming.report.downloaded,
                removed = incoming.report.removed + confirming.report.removed,
                remoteMediaMissing = incoming.report.remoteMediaMissing + confirming.report.remoteMediaMissing,
                downloadedMediaFilenames = incoming.report.downloadedMediaFilenames +
                    confirming.report.downloadedMediaFilenames,
            ),
            pushed = pushed,
            localContent = store.loadLocalContent(),
            localReviews = reviews,
            schedulerProfile = profile,
            studyDayPolicy = studyDayPolicy,
            conflicts = store.loadSyncConflicts(),
        )
    }
    onProgress(
        SyncProgress(
            SyncLogLevel.Success,
            "LOCAL",
            "Applied confirmation in ${elapsed(confirmationApplyStarted, clock)} · " +
                "${completed.report.collection.cards.size} cards ready",
            replaceLatest = true,
        ),
    )
    onProgress(
        SyncProgress(
            SyncLogLevel.Success,
            "COMPLETE",
            associateSyncCompletion(completed.report, pushed) + " · total ${elapsed(cycleStarted, clock)}",
        ),
    )
    return completed
}

internal suspend fun pullRemoteState(
    client: KelmaSyncService,
    token: String,
    current: SyncedCollection,
    onProgress: suspend (SyncPullProgress) -> Unit = {},
): RemoteSyncState = coroutineScope {
    val progressMutex = Mutex()
    val report = async {
        client.pull(token, current) { progress ->
            progressMutex.withLock { onProgress(progress) }
        }
    }
    val profile = async { client.getSchedulerProfile(token) }
    val studyDayPolicy = async { client.getStudyDayPolicy(token) }
    RemoteSyncState(report.await(), profile.await(), studyDayPolicy.await())
}

private suspend fun ensureStudyDayPolicy(
    client: KelmaSyncService,
    store: PersistentCollectionStore,
    token: String,
    remote: AccountStudyDayPolicy,
): AccountStudyDayPolicy {
    if (remote.version > 0L) return remote.validated()
    val localDefault = store.loadStudyDayPolicy().copy(version = 0L)
    return try {
        client.putStudyDayPolicy(token, localDefault.toCandidate())
    } catch (conflict: KelmaSyncException) {
        client.getStudyDayPolicy(token).takeIf { it.version > 0L } ?: throw conflict
    }
}

internal suspend fun resolveSyncConflict(
    store: PersistentCollectionStore,
    conflict: SyncUploadConflict,
    keepLocal: Boolean,
): Triple<LocalContentSnapshot, LocalReviewSnapshot, List<SyncUploadConflict>> =
    withContext(Dispatchers.Default) {
        store.resolveSyncConflict(conflict, keepLocal)
        Triple(store.loadLocalContent(), store.loadLocalReviews(), store.loadSyncConflicts())
    }

private fun associateSyncCompletion(report: PullReport, pushed: SyncPushResult): String =
    "Confirmed ${pushed.uploadedCount} uploads · ${report.downloaded} changes · ${report.removed} removals · " +
        "${report.collection.cards.size} cards"

private fun elapsed(startedAt: Long, clock: () -> Long): String =
    formatSyncDuration((clock() - startedAt).coerceAtLeast(0L))

internal fun PullReport.syncMessage(firstSync: Boolean, uploaded: Int = 0): String {
    val uploadText = if (uploaded > 0) "Uploaded $uploaded · " else ""
    return when {
        firstSync -> "${uploadText}Downloaded full collection · ${collection.cards.size} cards · " +
            "${collection.reviews.size} reviews · ${collection.media.size} media"
        downloaded == 0 && removed == 0 -> if (uploaded > 0) {
            "Uploaded $uploaded changes · Up to date"
        } else {
            "Up to date"
        }
        removed == 0 -> "${uploadText}Downloaded $downloaded changes"
        else -> "${uploadText}Downloaded $downloaded changes, removed $removed"
    }
}

internal fun syncConflictMessage(count: Int): String =
    "$count sync conflict${if (count == 1) "" else "s"} need resolution"
