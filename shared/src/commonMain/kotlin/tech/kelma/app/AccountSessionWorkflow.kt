package tech.kelma.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PendingAccountSignIn(
    val username: String,
    val auth: LoginResponse,
)

internal data class InitializedAccountSession(
    val collection: SyncedCollection,
    val localContent: LocalContentSnapshot,
    val localReviews: LocalReviewSnapshot,
    val schedulerProfile: SchedulerProfileState,
    val studyDayPolicy: AccountStudyDayPolicy,
    val schedulerOptimizer: SchedulerOptimizerState,
    val conflicts: List<SyncUploadConflict>,
    val syncLogs: List<SyncLogEntry>,
    val pluginRendererAssignments: PluginRendererAssignmentState,
    val syncMessage: String?,
    val error: String?,
)

internal suspend fun initializeAccountSession(
    store: PersistentCollectionStore,
    syncClient: KelmaSyncService,
    pending: PendingAccountSignIn,
    onProgress: suspend (SyncProgress) -> Unit = {},
): InitializedAccountSession {
    val savedAuth = StoredSyncAuth(
        token = pending.auth.token,
        clientId = pending.auth.clientId,
        endpoint = DefaultKelmaSyncEndpoint,
        username = pending.username,
    )
    val saved = withContext(Dispatchers.Default) { store.load(recoverOptimizerJobs = true) }
    withContext(Dispatchers.Default) { store.saveSignedInState(savedAuth, saved.collection) }
    val completed = runSyncCycle(
        syncClient,
        store,
        pending.auth.token,
        saved.collection,
        onProgress,
    )
    val conflicts = completed.conflicts
    return InitializedAccountSession(
        collection = completed.report.collection,
        localContent = completed.localContent,
        localReviews = completed.localReviews,
        schedulerProfile = completed.schedulerProfile,
        studyDayPolicy = completed.studyDayPolicy,
        schedulerOptimizer = withContext(Dispatchers.Default) { store.loadSchedulerOptimizer() },
        conflicts = conflicts,
        syncLogs = withContext(Dispatchers.Default) { store.loadSyncLog() },
        pluginRendererAssignments = withContext(Dispatchers.Default) {
            store.loadPluginRendererAssignments()
        },
        syncMessage = completed.report.syncMessage(
            firstSync = saved.collection.serverTime == null,
            uploaded = completed.pushed.uploadedCount,
        ).takeIf { conflicts.isEmpty() },
        error = syncConflictMessage(conflicts.size).takeIf { conflicts.isNotEmpty() },
    )
}
