package tech.kelma.app

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy

/** Shared mutable session state used by the workflow coordinator and screen host. */
internal class AppState {
    val token = mutableStateOf<String?>(null)
    val collection = mutableStateOf(SyncedCollection())
    val localContent = mutableStateOf(LocalContentSnapshot())
    val localReviews = mutableStateOf(LocalReviewSnapshot(), referentialEqualityPolicy())
    val schedulerProfile = mutableStateOf(SchedulerProfileState())
    val studyDayPolicy = mutableStateOf(AccountStudyDayPolicy.systemDefault())
    val schedulerOptimizer = mutableStateOf(SchedulerOptimizerState())
    val pluginHost = mutableStateOf(PluginHostState())
    val pluginRendererAssignments = mutableStateOf(PluginRendererAssignmentState())
    val pluginRenderedCards = mutableStateOf<Map<Long, PluginRenderedCard>>(emptyMap())
    val studyStats = mutableStateOf(StudyStats())
    val nowMillis = mutableLongStateOf(currentEpochMillis())
    val selectedDeck = mutableStateOf<DeckSummary?>(null)
    val desktopStudyStarted = mutableStateOf(false)
    val destination = mutableStateOf(CollectionDestination.Decks)
    val preferredAddDeck = mutableStateOf<String?>(null)
    val preferredOptionsDeck = mutableStateOf<String?>(null)
    val initialBrowseQuery = mutableStateOf("")
    val showSignIn = mutableStateOf(false)
    val working = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)
    val syncMessage = mutableStateOf<String?>(null)
    val syncConflicts = mutableStateOf<List<SyncUploadConflict>>(emptyList())
    val syncLogs = mutableStateOf<List<SyncLogEntry>>(emptyList())
    val restored = mutableStateOf(false)

    fun clearDisplayedAccount() {
        token.value = null
        collection.value = SyncedCollection()
        localContent.value = LocalContentSnapshot()
        localReviews.value = LocalReviewSnapshot()
        schedulerProfile.value = SchedulerProfileState()
        studyDayPolicy.value = AccountStudyDayPolicy.systemDefault()
        schedulerOptimizer.value = SchedulerOptimizerState()
        pluginRendererAssignments.value = PluginRendererAssignmentState()
        pluginRenderedCards.value = emptyMap()
        studyStats.value = StudyStats()
        selectedDeck.value = null
        desktopStudyStarted.value = false
        destination.value = destination.value.navigate(CollectionNavigationAction.OpenDecks)
        syncConflicts.value = emptyList()
        syncLogs.value = emptyList()
    }
}
