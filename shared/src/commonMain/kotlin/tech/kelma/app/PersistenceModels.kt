package tech.kelma.app

data class StoredSyncAuth(
    val token: String,
    val clientId: String,
    val endpoint: String,
    val username: String,
)

data class StoredAppState(
    val auth: StoredSyncAuth?,
    val collection: SyncedCollection,
    val localContent: LocalContentSnapshot,
    val localReviews: LocalReviewSnapshot,
    val schedulerProfile: SchedulerProfileState = SchedulerProfileState(),
    val studyDayPolicy: AccountStudyDayPolicy = AccountStudyDayPolicy.systemDefault(),
    val schedulerOptimizer: SchedulerOptimizerState = SchedulerOptimizerState(),
)
