package tech.kelma.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal data class AppContentActions(
    val undoReview: suspend (String) -> ReviewCard?,
    val requestSync: () -> Unit,
    val redownloadCollection: () -> Unit,
    val signIn: (String, String) -> Unit,
    val selectAccount: (LocalAccountChoice) -> Unit,
    val signOut: () -> Unit,
    val openDecks: () -> Unit,
    val openAdd: () -> Unit,
    val openBrowse: () -> Unit,
    val openOptions: () -> Unit,
    val openPlugins: () -> Unit,
    val openStats: () -> Unit,
    val openSync: () -> Unit,
    val assignPreset: suspend (String, String?) -> String?,
    val createPreset: suspend (String, String, DeckOptions) -> String?,
    val clonePreset: suspend (String, String, String) -> String?,
    val renamePreset: suspend (String, String) -> String?,
    val deletePreset: suspend (String) -> String?,
    val applyAccountSchedulerProfile: suspend (DeckOptions, Boolean) -> String?,
    val applyCloudSchedulerProfile: suspend () -> String?,
    val saveStudyDayPolicy: suspend (String, Int) -> String?,
    val startSchedulerOptimization: () -> Unit,
    val cancelSchedulerOptimization: () -> Unit,
    val applySchedulerOptimizerCandidate: suspend (Boolean) -> String?,
    val discardSchedulerOptimizerCandidate: suspend () -> String?,
    val setPluginRendererAssignment: suspend (PluginRendererScope, String, String?) -> String?,
    val resolveSyncConflict: (SyncUploadConflict, Boolean) -> Unit,
)

@Composable
internal fun AppContent(
    state: AppState,
    store: PersistentCollectionStore,
    scope: CoroutineScope,
    appFocusRequester: FocusRequester,
    displayCollection: SyncedCollection,
    visibleDecks: List<DeckSummary>,
    decksLoading: Boolean,
    activeDeck: DeckSummary?,
    savedAccounts: List<LocalAccountChoice>,
    interchangeDocuments: CollectionDocumentIO,
    interchangeService: CollectionInterchangeService,
    luaPluginHost: LuaPluginHost,
    pluginCommands: PluginCommandRegistry,
    pluginRenderers: PluginRendererRegistry,
    actions: AppContentActions,
) {
    var token by state.token
    var collection by state.collection
    var localContent by state.localContent
    var localReviews by state.localReviews
    var schedulerProfile by state.schedulerProfile
    var studyDayPolicy by state.studyDayPolicy
    var schedulerOptimizer by state.schedulerOptimizer
    var pluginHostState by state.pluginHost
    var pluginRendererAssignments by state.pluginRendererAssignments
    var pluginRenderedCards by state.pluginRenderedCards
    var studyStats by state.studyStats
    val nowMillis by state.nowMillis
    var selectedDeck by state.selectedDeck
    var desktopStudyStarted by state.desktopStudyStarted
    var destination by state.destination
    var preferredAddDeck by state.preferredAddDeck
    var preferredOptionsDeck by state.preferredOptionsDeck
    var initialBrowseQuery by state.initialBrowseQuery
    var showSignIn by state.showSignIn
    var working by state.working
    var error by state.error
    var syncMessage by state.syncMessage
    var syncConflicts by state.syncConflicts
    var syncLogs by state.syncLogs
    val restored by state.restored
    val interchangeUiState = rememberCollectionInterchangeUiState()
    val browsePageLoader = remember(store, ProjectionIdentity(displayCollection), working) {
        BrowsePageLoader { request ->
            store.loadBrowsePage(displayCollection, request, rebuildIfDirty = !working)
        }
    }
    val syncedCardCount = remember(ProjectionIdentity(collection), ProjectionIdentity(displayCollection)) {
        collection.cards.keys.count(displayCollection.cards::containsKey)
    }
    val syncedMediaBytes = remember(ProjectionIdentity(collection)) {
        collection.media.values.sumOf(SyncMediaFile::sizeBytes)
    }
    val availableDeckNames = remember(ProjectionIdentity(displayCollection)) {
        (displayCollection.deckNames + displayCollection.deckRecords.keys)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    val accountDeckOptions = schedulerProfile.local.settings.asDeckOptions()
    val effectiveOptionsByDeck = remember(
        availableDeckNames,
        ProjectionIdentity(displayCollection.deckRecords),
        ProjectionIdentity(localContent.deckOptions),
        accountDeckOptions,
    ) {
        availableDeckNames.associateWith { deckName ->
            displayCollection.effectiveDeckOptions(deckName, localContent.deckOptions, accountDeckOptions)
        }
    }
    var pluginWorking by remember { mutableStateOf(false) }
    var pluginMessage by remember { mutableStateOf<String?>(null) }
    var pendingPluginInstall by remember { mutableStateOf<PluginPackage?>(null) }
    var showCommandPalette by remember { mutableStateOf(false) }
    var runningCommandId by remember { mutableStateOf<String?>(null) }
    var commandMessage by remember { mutableStateOf<String?>(null) }
    val completedReviewEvents = remember(luaPluginHost) { Channel<Rating>(Channel.UNLIMITED) }
    DisposableEffect(completedReviewEvents) {
        onDispose { completedReviewEvents.close() }
    }
    LaunchedEffect(completedReviewEvents) {
        for (rating in completedReviewEvents) {
            try {
                withContext(Dispatchers.Default) {
                    luaPluginHost.publish(
                        PluginEvent(
                            "review.completed",
                            mapOf("rating" to PluginValue.StringValue(rating.name)),
                        ),
                    )
                }
                pluginHostState = luaPluginHost.state()
            } catch (pluginFailure: Exception) {
                error = "Review saved; plugin event failed: ${pluginFailure.message ?: "unknown error"}"
            }
        }
    }
    val paletteCommands = remember(pluginHostState.runtimeGeneration) { pluginCommands.list() }
    val availableRendererIds = remember(pluginHostState.runtimeGeneration) {
        pluginRenderers.list().map(PluginRendererRegistration::rendererId).sorted()
    }

    val undoReview = actions.undoReview
    val requestSync = actions.requestSync
    val openDecks = actions.openDecks
    val openAdd = actions.openAdd
    val openBrowse = actions.openBrowse
    val openOptions = actions.openOptions
    val openPlugins = actions.openPlugins
    val openStats = actions.openStats
    val openSync = actions.openSync
    val assignPreset = actions.assignPreset
    val createPreset = actions.createPreset
    val clonePreset = actions.clonePreset
    val renamePreset = actions.renamePreset
    val deletePreset = actions.deletePreset
    val applyAccountSchedulerProfile = actions.applyAccountSchedulerProfile
    val applyCloudSchedulerProfile = actions.applyCloudSchedulerProfile
    val saveStudyDayPolicy = actions.saveStudyDayPolicy
    val startSchedulerOptimization = actions.startSchedulerOptimization
    val cancelSchedulerOptimization = actions.cancelSchedulerOptimization
    val applySchedulerOptimizerCandidate = actions.applySchedulerOptimizerCandidate
    val discardSchedulerOptimizerCandidate = actions.discardSchedulerOptimizerCandidate
    val setPluginRendererAssignment = actions.setPluginRendererAssignment
    val resolveSyncConflict = actions.resolveSyncConflict

    fun runPluginOperation(operation: suspend () -> PluginHostState) {
        if (pluginWorking || runningCommandId != null) return
        pluginWorking = true
        pluginMessage = null
        scope.launch {
            try {
                pluginHostState = withContext(Dispatchers.Default) { operation() }
            } catch (failure: Exception) {
                pluginMessage = failure.message ?: "Plugin operation failed"
            } finally {
                pluginWorking = false
            }
        }
    }

    fun updateRendererAssignment(scopeType: PluginRendererScope, targetId: String, rendererId: String?) {
        if (pluginWorking || runningCommandId != null) return
        pluginWorking = true
        pluginMessage = null
        scope.launch {
            pluginMessage = setPluginRendererAssignment(scopeType, targetId, rendererId)
            pluginWorking = false
        }
    }

    fun commandContext(): PluginCommandContext = PluginCommandContext(
        screen = when {
            showSignIn -> "sign-in"
            activeDeck != null && isDesktopApp && !desktopStudyStarted -> "deck-overview"
            activeDeck != null -> "review"
            else -> destination.pluginScreenName()
        },
        deckName = activeDeck?.id,
    )

    fun runCommand(commandId: String) {
        if (runningCommandId != null || pluginWorking) return
        runningCommandId = commandId
        commandMessage = null
        val context = commandContext()
        scope.launch {
            try {
                val result = if (isKelmaCommand(commandId)) {
                    when (commandId) {
                        OpenDecksCommand -> openDecks()
                        OpenAddCommand -> openAdd()
                        OpenBrowseCommand -> openBrowse()
                        OpenOptionsCommand -> openOptions()
                        OpenStatsCommand -> openStats()
                        OpenSyncLogCommand -> openSync()
                        OpenPluginsCommand -> openPlugins()
                        SyncNowCommand -> requestSync()
                    }
                    PluginValue.StringValue("Command completed")
                } else {
                    withContext(Dispatchers.Default) {
                        pluginCommands.invoke(
                            PluginCommandInvocation(commandId, context = context),
                        )
                    }
                }
                commandMessage = when (result) {
                    is PluginValue.StringValue -> result.value
                    else -> result.toBoundaryJson().toString()
                }.take(2_000)
                pluginMessage = commandMessage
            } catch (failure: Exception) {
                commandMessage = failure.message ?: "Command failed"
                pluginMessage = commandMessage
            } finally {
                pluginHostState = luaPluginHost.state()
                runningCommandId = null
            }
        }
    }

    CompositionLocalProvider(
        LocalOpenStats provides openStats,
        LocalBrowsePageLoader provides browsePageLoader,
    ) {
        KelmaTheme {
        Box(
            Modifier.fillMaxSize().then(
                if (!isDesktopApp) Modifier else Modifier
                    .focusRequester(appFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val commandModifier = event.isMetaPressed || event.isCtrlPressed
                        when {
                            event.type == KeyEventType.KeyDown && event.key == Key.K && commandModifier -> {
                                showCommandPalette = !showCommandPalette
                                true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.S && commandModifier -> {
                                requestSync()
                                true
                            }
                            else -> false
                        }
                    },
            ),
        ) {
        when {
            showSignIn -> SignInScreen(
                signingIn = working,
                error = error,
                accounts = savedAccounts,
                onSelectAccount = actions.selectAccount,
                onBack = {
                    showSignIn = false
                    error = null
                },
                onSignIn = actions.signIn,
            )
            destination == CollectionDestination.Sync -> SyncScreen(
                entries = syncLogs,
                signedIn = token != null,
                syncing = working,
                onDecks = openDecks,
                onAdd = openAdd,
                onBrowse = openBrowse,
                onOptions = openOptions,
                onSync = requestSync,
                onRedownloadCollection = actions.redownloadCollection,
                onClear = {
                    scope.launch {
                        withContext(Dispatchers.Default) { store.clearSyncLog() }
                        syncLogs = emptyList()
                    }
                },
            )
            destination == CollectionDestination.Add -> AddScreen(
                deckNames = availableDeckNames,
                syncing = working,
                initialDeckName = preferredAddDeck,
                onBack = openDecks,
                onSync = if (isDesktopApp) openSync else requestSync,
                onOpenSync = openSync,
                onBrowse = openBrowse,
                onOptions = openOptions,
                onAttach = { media ->
                    val saved = withContext(Dispatchers.Default) {
                        store.saveMediaAttachment(media.filename, media.mimeType, media.bytes)
                    }
                    localContent = saved.content
                    saved.filename
                },
                onSave = { draft ->
                    try {
                        localContent = withContext(Dispatchers.Default) { store.addLocalNote(draft) }.content
                        null
                    } catch (exception: Exception) {
                        exception.message ?: "Could not save the note"
                    }
                },
            )
            destination == CollectionDestination.Browse -> BrowseScreen(
                collection = displayCollection,
                schedules = localReviews.schedules,
                nowMillis = nowMillis,
                dueDateOverrides = localReviews.dueDateOverrides,
                syncing = working,
                initialQuery = initialBrowseQuery,
                loadMedia = store::loadDownloadedMedia,
                onBack = openDecks,
                onDecks = openDecks,
                onSync = if (isDesktopApp) openSync else requestSync,
                onOpenSync = openSync,
                onAdd = openAdd,
                onOptions = openOptions,
                onAttach = { media ->
                    val saved = withContext(Dispatchers.Default) {
                        store.saveMediaAttachment(media.filename, media.mimeType, media.bytes)
                    }
                    localContent = saved.content
                    saved.filename
                },
                onStudyDeck = { deckName ->
                    val deck = visibleDecks.firstOrNull { it.id == deckName } ?: DeckSummary(
                        id = deckName,
                        name = deckName,
                        cards = emptyList(),
                        newCount = 0,
                        learningCount = 0,
                        dueCount = 0,
                        queueLoaded = false,
                    )
                    selectedDeck = deck
                    desktopStudyStarted = !isDesktopApp
                    destination = destination.navigate(CollectionNavigationAction.OpenDecks)
                },
                onSaveEdit = { edit ->
                    try {
                        localContent = withContext(Dispatchers.Default) {
                            store.updateNoteFields(edit.noteGuid, edit.fields, edit.tags)
                        }
                        null
                    } catch (exception: Exception) {
                        exception.message ?: "Could not update the note"
                    }
                },
                onDeleteNote = { row ->
                    scope.launch {
                        try {
                            val updated = withContext(Dispatchers.Default) {
                                store.deleteLocalNote(row.noteGuid) to store.loadLocalReviews()
                            }
                            localContent = updated.first
                            localReviews = updated.second
                        } catch (exception: Exception) {
                            error = exception.message ?: "Could not delete the note"
                        }
                    }
                },
            )
            destination == CollectionDestination.Stats -> StatsScreen(
                stats = studyStats,
                syncing = working,
                onDecks = openDecks,
                onAdd = openAdd,
                onBrowse = openBrowse,
                onOptions = openOptions,
                onSync = openSync,
            )
            destination == CollectionDestination.Plugins -> PluginManagerScreen(
                state = pluginHostState,
                busy = pluginWorking || runningCommandId != null,
                message = pluginMessage,
                rendererAssignments = pluginRendererAssignments,
                rendererIds = availableRendererIds,
                deckNames = availableDeckNames,
                noteTypes = displayCollection.notetypes.values.map {
                    RendererNoteTypeTarget(it.notetypeId, it.name)
                },
                onDecks = openDecks,
                onAdd = openAdd,
                onBrowse = openBrowse,
                onOptions = openOptions,
                onSync = openSync,
                onInstall = {
                    if (!pluginWorking) {
                        scope.launch {
                            pluginWorking = true
                            pluginMessage = null
                            try {
                                val document = interchangeDocuments.openPlugin()
                                if (document != null) {
                                    pendingPluginInstall = withContext(Dispatchers.Default) {
                                        luaPluginHost.prepareInstall(document)
                                    }
                                }
                            } catch (failure: Exception) {
                                pluginMessage = failure.message ?: "Could not install plugin"
                            } finally {
                                pluginWorking = false
                            }
                        }
                    }
                },
                pendingInstall = pendingPluginInstall?.manifest,
                onConfirmInstall = {
                    pendingPluginInstall?.let { pluginPackage ->
                        pendingPluginInstall = null
                        runPluginOperation { luaPluginHost.install(pluginPackage) }
                    }
                },
                onDismissInstall = { pendingPluginInstall = null },
                onReload = { runPluginOperation(luaPluginHost::reload) },
                onSafeMode = { enabled -> runPluginOperation { luaPluginHost.setSafeMode(enabled) } },
                onEnabled = { pluginId, enabled ->
                    runPluginOperation { luaPluginHost.setEnabled(pluginId, enabled) }
                },
                onUninstall = { pluginId -> runPluginOperation { luaPluginHost.uninstall(pluginId) } },
                onRunCommand = ::runCommand,
                onAssignRenderer = { rendererScope, targetId, rendererId ->
                    updateRendererAssignment(rendererScope, targetId, rendererId)
                },
                onRemoveRenderer = { rendererScope, targetId ->
                    updateRendererAssignment(rendererScope, targetId, null)
                },
                onLoadLogs = { pluginId ->
                    withContext(Dispatchers.Default) { luaPluginHost.logs(pluginId) }
                },
            )
            destination == CollectionDestination.Options && isDesktopApp -> DesktopOptionsScreen(
                deckNames = availableDeckNames,
                optionsByDeck = effectiveOptionsByDeck,
                syncing = working,
                schedulerProfile = schedulerProfile,
                studyDayPolicy = studyDayPolicy,
                schedulerOptimizer = schedulerOptimizer,
                deckPresets = localContent.deckPresets,
                signedIn = token != null,
                initialDeckName = preferredOptionsDeck,
                onDecks = openDecks,
                onAdd = openAdd,
                onBrowse = openBrowse,
                onSync = openSync,
                onCommands = { showCommandPalette = true },
                onPlugins = openPlugins,
                onSave = { deckName, options ->
                    try {
                        val saved = withContext(Dispatchers.Default) {
                            store.saveDeckOptions(deckName, options) to store.loadLocalReviews(nowMillis)
                        }
                        localContent = saved.first
                        localReviews = saved.second
                        null
                    } catch (exception: Exception) {
                        exception.message ?: "Could not save deck options"
                    }
                },
                onApplyAccountProfile = applyAccountSchedulerProfile,
                onApplyCloudProfile = applyCloudSchedulerProfile,
                onSaveStudyDayPolicy = saveStudyDayPolicy,
                onStartOptimization = startSchedulerOptimization,
                onCancelOptimization = cancelSchedulerOptimization,
                onApplyOptimizerCandidate = applySchedulerOptimizerCandidate,
                onDiscardOptimizerCandidate = discardSchedulerOptimizerCandidate,
                onAssignPreset = assignPreset,
                onCreatePreset = createPreset,
                onClonePreset = clonePreset,
                onRenamePreset = renamePreset,
                onDeletePreset = deletePreset,
            )
            destination == CollectionDestination.Options -> MobileOptionsScreen(
                deckNames = availableDeckNames,
                optionsByDeck = effectiveOptionsByDeck,
                syncing = working,
                schedulerProfile = schedulerProfile,
                studyDayPolicy = studyDayPolicy,
                schedulerOptimizer = schedulerOptimizer,
                deckPresets = localContent.deckPresets,
                signedIn = token != null,
                initialDeckName = preferredOptionsDeck,
                onDecks = openDecks,
                onBrowse = openBrowse,
                onAdd = openAdd,
                onSyncLog = openSync,
                onSyncNow = requestSync,
                onPlugins = openPlugins,
                onSave = { deckName, options ->
                    try {
                        val saved = withContext(Dispatchers.Default) {
                            store.saveDeckOptions(deckName, options) to store.loadLocalReviews(nowMillis)
                        }
                        localContent = saved.first
                        localReviews = saved.second
                        null
                    } catch (exception: Exception) {
                        exception.message ?: "Could not save deck options"
                    }
                },
                onApplyAccountProfile = applyAccountSchedulerProfile,
                onApplyCloudProfile = applyCloudSchedulerProfile,
                onSaveStudyDayPolicy = saveStudyDayPolicy,
                onStartOptimization = startSchedulerOptimization,
                onCancelOptimization = cancelSchedulerOptimization,
                onApplyOptimizerCandidate = applySchedulerOptimizerCandidate,
                onDiscardOptimizerCandidate = discardSchedulerOptimizerCandidate,
                onAssignPreset = assignPreset,
                onCreatePreset = createPreset,
                onClonePreset = clonePreset,
                onRenamePreset = renamePreset,
                onDeletePreset = deletePreset,
            )
            activeDeck != null && isDesktopApp && !desktopStudyStarted -> DesktopDeckOverviewScreen(
                deck = activeDeck,
                syncing = working,
                onDecks = { selectedDeck = null },
                onSync = openSync,
                onAdd = openAdd,
                onBrowse = openBrowse,
                onOptions = openOptions,
                onStudy = { desktopStudyStarted = true },
            )
            activeDeck != null && !activeDeck.queueLoaded -> DeckQueueLoadingScreen(
                deckName = activeDeck.name,
                onBack = {
                    selectedDeck = null
                    desktopStudyStarted = false
                },
            )
            activeDeck != null -> ReviewScreen(
                deck = activeDeck,
                syncing = working,
                canUndo = localReviews.lastReviewDeck == activeDeck.id,
                options = effectiveOptionsByDeck[activeDeck.id] ?: accountDeckOptions,
                schedules = localReviews.schedules,
                dueDateOverrides = localReviews.dueDateOverrides,
                studyDayPolicy = localReviews.studyDayPolicy,
                loadMedia = store::loadDownloadedMedia,
                onSync = if (isDesktopApp) openSync else requestSync,
                onAdd = openAdd,
                onBrowse = openBrowse,
                onOptions = openOptions,
                onCardFlagged = { cardId, flag ->
                    try {
                        localContent = withContext(Dispatchers.Default) {
                            store.setCardFlag(cardId, flag)
                        }
                        null
                    } catch (exception: Exception) {
                        exception.message ?: "Could not save the card flag"
                    }
                },
                onCardBuried = { cardId ->
                    try {
                        localReviews = withContext(Dispatchers.Default) {
                            store.buryCard(cardId)
                        }
                        null
                    } catch (exception: Exception) {
                        exception.message ?: "Could not bury the card"
                    }
                },
                onNoteMarked = { cardId, marked ->
                    val card = displayCollection.cards[cardId]
                    if (card == null) {
                        "This card is no longer in the collection"
                    } else {
                        try {
                            localContent = withContext(Dispatchers.Default) {
                                store.setNoteMarked(card.noteGuid, marked)
                            }
                            null
                        } catch (exception: Exception) {
                            exception.message ?: "Could not update the note mark"
                        }
                    }
                },
                onNoteBuried = { cardId ->
                    val card = displayCollection.cards[cardId]
                    if (card == null) {
                        "This card is no longer in the collection"
                    } else {
                        try {
                            localReviews = withContext(Dispatchers.Default) {
                                store.buryNote(card.noteGuid)
                            }
                            null
                        } catch (exception: Exception) {
                            exception.message ?: "Could not bury the note"
                        }
                    }
                },
                onCardReset = { cardId ->
                    try {
                        localReviews = withContext(Dispatchers.Default) {
                            store.resetCard(cardId)
                        }
                        null
                    } catch (exception: Exception) {
                        exception.message ?: "Could not reset the card"
                    }
                },
                onCardDueDateSet = { cardId, dueAtMillis ->
                    try {
                        localReviews = withContext(Dispatchers.Default) {
                            store.setCardDueDate(cardId, dueAtMillis)
                        }
                        null
                    } catch (exception: Exception) {
                        exception.message ?: "Could not set the due date"
                    }
                },
                onCardSuspended = { cardId ->
                    val card = displayCollection.cards[cardId]
                    if (card == null) {
                        "This card is no longer in the collection"
                    } else {
                        try {
                            localContent = withContext(Dispatchers.Default) {
                                store.setCardsStudyState(listOf(card), CardStudyState.Suspended)
                            }
                            null
                        } catch (exception: Exception) {
                            exception.message ?: "Could not suspend the card"
                        }
                    }
                },
                onNoteSuspended = { cardId ->
                    val card = displayCollection.cards[cardId]
                    if (card == null) {
                        "This card is no longer in the collection"
                    } else {
                        try {
                            val noteCards = displayCollection.cards.values
                                .filter { it.noteGuid.isNotEmpty() && it.noteGuid == card.noteGuid }
                                .ifEmpty { listOf(card) }
                            localContent = withContext(Dispatchers.Default) {
                                store.setCardsStudyState(noteCards, CardStudyState.Suspended)
                            }
                            null
                        } catch (exception: Exception) {
                            exception.message ?: "Could not suspend the note"
                        }
                    }
                },
                onNoteCopied = { cardId ->
                    val card = displayCollection.cards[cardId]
                    if (card == null) {
                        "This card is no longer in the collection"
                    } else {
                        try {
                            localContent = withContext(Dispatchers.Default) {
                                store.createNoteCopy(card.noteGuid).content
                            }
                            null
                        } catch (exception: Exception) {
                            exception.message ?: "Could not create the note copy"
                        }
                    }
                },
                onNoteDeleted = { cardId ->
                    val card = displayCollection.cards[cardId]
                    if (card == null) {
                        "This card is no longer in the collection"
                    } else {
                        try {
                            val updated = withContext(Dispatchers.Default) {
                                store.deleteLocalNote(card.noteGuid) to store.loadLocalReviews()
                            }
                            localContent = updated.first
                            localReviews = updated.second
                            null
                        } catch (exception: Exception) {
                            exception.message ?: "Could not delete the note"
                        }
                    }
                },
                previewSchedule = { cardId, rating, reviewedAtMillis ->
                    displayCollection.cards[cardId]?.let { card ->
                        FsrsScheduler.review(
                            card = card.copy(scheduling = JsonObject(emptyMap())),
                            previous = localReviews.schedules[cardId],
                            rating = rating,
                            reviewedAtMillis = reviewedAtMillis,
                            serverLastReviewAtMillis = null,
                            options = effectiveOptionsByDeck[activeDeck.id] ?: accountDeckOptions,
                        ).alignedToStudyDay(studyDayPolicy)
                    }
                },
                onCardReviewed = { cardId, rating, durationMillis ->
                    val syncCard = displayCollection.cards[cardId]
                    if (syncCard == null) {
                        error = "This card is no longer in the collection"
                        null
                    } else {
                        try {
                            val previousReviews = localReviews
                            val updated = withContext(Dispatchers.Default) {
                                store.recordReviewIncrementally(
                                    syncCard,
                                    rating,
                                    currentSnapshot = previousReviews,
                                    options = effectiveOptionsByDeck[activeDeck.id] ?: accountDeckOptions,
                                    durationMillis = durationMillis,
                                ) to store.loadSchedulerOptimizer()
                            }
                            localReviews = updated.first.snapshot
                            schedulerOptimizer = updated.second
                            completedReviewEvents.trySend(rating)
                            updated.first.schedule
                        } catch (exception: Exception) {
                            error = exception.message ?: "Could not save the review"
                            null
                        }
                    }
                },
                onUndo = { undoReview(activeDeck.id) },
                onBack = {
                    selectedDeck = null
                    desktopStudyStarted = false
                },
            )
            else -> DeckListScreen(
                decks = visibleDecks,
                loading = decksLoading,
                signedIn = token != null,
                syncing = working || !restored,
                syncMessage = error ?: syncMessage,
                syncMessageIsError = error != null,
                studiedToday = localReviews.reviewedToday,
                syncedCardCount = syncedCardCount,
                localCardCount = localContent.cardCount,
                syncedMediaBytes = syncedMediaBytes,
                canUndo = localReviews.canUndo,
                confirmBeforeUndo = localReviews.lastReviewDeck?.let { deckName ->
                    effectiveOptionsByDeck[deckName]?.confirmBeforeUndo
                } ?: true,
                onAdd = openAdd,
                onCreateDeck = { name ->
                    try {
                        localContent = withContext(Dispatchers.Default) { store.createLocalDeck(name) }
                        null
                    } catch (exception: Exception) {
                        exception.message ?: "Could not create the deck"
                    }
                },
                deckManagement = DeckManagementActions(
                    onAddCards = { deckName ->
                        preferredAddDeck = deckName
                        destination = destination.navigate(CollectionNavigationAction.OpenAdd)
                    },
                    onBrowseCards = { deckName ->
                        initialBrowseQuery = browseQualifier("deck", deckName)
                        destination = destination.navigate(CollectionNavigationAction.OpenBrowse)
                    },
                    onOptions = { deckName ->
                        preferredOptionsDeck = deckName
                        destination = destination.navigate(CollectionNavigationAction.OpenOptions)
                    },
                    onExport = interchangeUiState::requestExport,
                    onRename = { oldName, newName ->
                        try {
                            val updated = withContext(Dispatchers.Default) {
                                Triple(
                                    store.renameLocalDeck(oldName, newName),
                                    store.loadLocalReviews(),
                                    store.loadPluginRendererAssignments(),
                                )
                            }
                            localContent = updated.first
                            localReviews = updated.second
                            pluginRendererAssignments = updated.third
                            null
                        } catch (exception: Exception) {
                            exception.message ?: "Could not rename the deck"
                        }
                    },
                    onDelete = { deckName ->
                        try {
                            val updated = withContext(Dispatchers.Default) {
                                Triple(
                                    store.deleteLocalDeck(deckName),
                                    store.loadLocalReviews(),
                                    store.loadPluginRendererAssignments(),
                                )
                            }
                            localContent = updated.first
                            localReviews = updated.second
                            pluginRendererAssignments = updated.third
                            null
                        } catch (exception: Exception) {
                            exception.message ?: "Could not delete the deck"
                        }
                    },
                ),
                onBrowse = openBrowse,
                onOptions = openOptions,
                onImportFile = {
                    interchangeUiState.requestImport(scope, interchangeDocuments) { error = it }
                },
                onExportCollection = { interchangeUiState.requestExport(null) },
                onUndo = {
                    val deckName = localReviews.lastReviewDeck
                    if (deckName != null && !working) {
                        working = true
                        scope.launch {
                            undoReview(deckName)
                            working = false
                        }
                    }
                },
                onOpenDeck = { deck ->
                    if (deck.newCount + deck.learningCount + deck.dueCount > 0) {
                        selectedDeck = deck
                        desktopStudyStarted = !isDesktopApp
                    }
                },
                onSignIn = {
                    error = null
                    showSignIn = true
                },
                onSync = requestSync,
                onOpenSync = openSync,
                onSignOut = actions.signOut,
            )
        }
        CollectionInterchangeHost(
            uiState = interchangeUiState,
            deckNames = availableDeckNames,
            collection = displayCollection,
            localContent = localContent,
            localReviews = localReviews,
            defaultDeckOptions = schedulerProfile.local.settings.asDeckOptions(),
            nowMillis = nowMillis,
            store = store,
            documents = interchangeDocuments,
            service = interchangeService,
            onImported = { imported ->
                localContent = imported.content
                localReviews = imported.reviews
                schedulerOptimizer = imported.optimizer
            },
            onMessage = { message ->
                error = null
                syncMessage = message
            },
        )
        syncConflicts.firstOrNull()?.let { conflict ->
            SyncConflictDialog(
                conflict = conflict,
                onKeepLocal = if (conflict.kind == "review") null else {
                    { resolveSyncConflict(conflict, true) }
                },
                onUseServer = { resolveSyncConflict(conflict, false) },
            )
        }
        if (isDesktopApp && showCommandPalette) {
            CommandPalette(
                commands = paletteCommands,
                runningCommandId = runningCommandId,
                message = commandMessage,
                onInvoke = ::runCommand,
                onDismiss = { showCommandPalette = false; commandMessage = null },
            )
        }
        }
        }
    }

}
