package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.kelma.db.KelmaDatabase

@Suppress("UNUSED_PARAMETER")
private fun disabledPluginRuntimeFactory(
    pluginId: String,
    capabilities: Set<PluginCapability>,
    files: Map<String, ByteArray>,
    entrypoint: String,
    limits: PluginRuntimeLimits,
): PlatformLuaRuntime = error("External plugins are unavailable in this build")

@Composable
fun App(externalPluginsEnabled: Boolean = true) {
    val accountRegistryStorage = rememberLocalAccountRegistryStorage()
    val accountRegistry = remember(accountRegistryStorage) { LocalAccountRegistry(accountRegistryStorage) }
    var databaseName by remember { mutableStateOf(accountRegistry.activeDatabaseName()) }
    var pendingAccountSignIn by remember { mutableStateOf<PendingAccountSignIn?>(null) }
    var openingSavedAccount by remember { mutableStateOf(false) }
    val databaseDriver = rememberDatabaseDriver(databaseName)
    val credentialVault = rememberCredentialVault()
    val mediaCache = rememberMediaCache(databaseName.substringBeforeLast('.'))
    val syncClient = remember(mediaCache) { KelmaSyncClient(mediaCache = mediaCache) }
    val interchangePlatform = rememberCollectionInterchangePlatform()
    val interchangeService = remember(interchangePlatform.sqliteFiles) {
        CollectionInterchangeService(interchangePlatform.sqliteFiles)
    }
    val store = remember(databaseDriver, credentialVault, mediaCache) {
        PersistentCollectionStore(
            KelmaDatabase(databaseDriver),
            credentialVault = credentialVault,
            mediaCache = mediaCache,
        )
    }
    val pluginCommands = remember(externalPluginsEnabled) {
        PluginCommandRegistry().apply { registerKelmaCommands(externalPluginsEnabled) }
    }
    val pluginEvents = remember { PluginEventRegistry() }
    val pluginRenderers = remember { PluginRendererRegistry() }
    val luaPluginHost = remember(store, pluginCommands, pluginEvents, pluginRenderers, externalPluginsEnabled) {
        store.createLuaPluginHost(
            pluginCommands,
            pluginEvents,
            pluginRenderers,
            runtimeFactory = if (externalPluginsEnabled) {
                ::createPlatformLuaRuntime
            } else {
                ::disabledPluginRuntimeFactory
            },
        )
    }
    val scope = rememberCoroutineScope()
    val appFocusRequester = remember { FocusRequester() }
    val appState = remember { AppState() }
    var token by appState.token
    var collection by appState.collection
    var localContent by appState.localContent
    var localReviews by appState.localReviews
    var schedulerProfile by appState.schedulerProfile
    var studyDayPolicy by appState.studyDayPolicy
    var schedulerOptimizer by appState.schedulerOptimizer
    var pluginHostState by appState.pluginHost
    var pluginRendererAssignments by appState.pluginRendererAssignments
    var pluginRenderedCards by appState.pluginRenderedCards
    var studyStats by appState.studyStats
    var nowMillis by appState.nowMillis
    var selectedDeck by appState.selectedDeck
    var desktopStudyStarted by appState.desktopStudyStarted
    var destination by appState.destination
    var preferredAddDeck by appState.preferredAddDeck
    var preferredOptionsDeck by appState.preferredOptionsDeck
    var initialBrowseQuery by appState.initialBrowseQuery
    var showSignIn by appState.showSignIn
    var working by appState.working
    var error by appState.error
    var syncMessage by appState.syncMessage
    var syncConflicts by appState.syncConflicts
    var syncLogs by appState.syncLogs
    var restored by appState.restored

    DisposableEffect(syncClient) {
        onDispose { syncClient.close() }
    }
    DisposableEffect(databaseDriver, luaPluginHost) {
        onDispose {
            luaPluginHost.close()
            databaseDriver.close()
        }
    }
    LaunchedEffect(Unit) {
        if (isDesktopApp) appFocusRequester.requestFocus()
    }
    LaunchedEffect(store, pendingAccountSignIn) {
        val pending = pendingAccountSignIn
        suspend fun log(progress: SyncProgress) {
            syncLogs = withContext(Dispatchers.Default) { store.appendSyncLog(progress) }
        }
        suspend fun applyCompletedSync(completed: CompletedSyncCycle) {
            collection = completed.report.collection
            localContent = completed.localContent
            localReviews = completed.localReviews
            schedulerProfile = completed.schedulerProfile
            studyDayPolicy = completed.studyDayPolicy
            studyStats = withContext(Dispatchers.Default) { store.loadStudyStats(nowMillis) }
            syncConflicts = completed.conflicts
        }
        try {
            if (pending == null) {
                val saved = withContext(Dispatchers.Default) {
                    store.load(recoverOptimizerJobs = true)
                }
                token = saved.auth?.token
                collection = saved.collection
                localContent = saved.localContent
                localReviews = saved.localReviews
                schedulerProfile = saved.schedulerProfile
                studyDayPolicy = saved.studyDayPolicy
                schedulerOptimizer = saved.schedulerOptimizer
                syncConflicts = withContext(Dispatchers.Default) { store.loadSyncConflicts() }
                syncLogs = withContext(Dispatchers.Default) { store.loadSyncLog() }
                pluginRendererAssignments = pluginRendererAssignmentsForBuild(
                    externalPluginsEnabled,
                    withContext(Dispatchers.Default) { store.loadPluginRendererAssignments() },
                )
                saved.auth?.let { auth ->
                    accountRegistry.registerCurrent(auth.endpoint, auth.username, databaseName)
                    showSignIn = false
                    error = null
                    if (openingSavedAccount) {
                        destination = CollectionDestination.Sync
                        log(SyncProgress(phase = "START", message = "Opening saved account and syncing"))
                        val completed = runSyncCycle(syncClient, store, auth.token, saved.collection, ::log)
                        applyCompletedSync(completed)
                        schedulerOptimizer = withContext(Dispatchers.Default) {
                            store.loadSchedulerOptimizer()
                        }
                        syncMessage = completed.report.syncMessage(
                            firstSync = false,
                            uploaded = completed.pushed.uploadedCount,
                        ).takeIf { completed.conflicts.isEmpty() }
                        error = syncConflictMessage(completed.conflicts.size)
                            .takeIf { completed.conflicts.isNotEmpty() }
                    }
                } ?: if (openingSavedAccount) {
                    accountRegistry.deactivate()
                    showSignIn = true
                    error = "This saved account no longer has a token. Sign in once to restore it."
                    databaseName = GuestCollectionDatabaseName
                } else {
                    Unit
                }
            } else {
                syncLogs = withContext(Dispatchers.Default) { store.loadSyncLog() }
                log(SyncProgress(phase = "AUTH", message = "Authenticated; starting account sync"))
                val initialized = initializeAccountSession(store, syncClient, pending, ::log)
                token = pending.auth.token
                collection = initialized.collection
                localContent = initialized.localContent
                localReviews = initialized.localReviews
                schedulerProfile = initialized.schedulerProfile
                studyDayPolicy = initialized.studyDayPolicy
                schedulerOptimizer = initialized.schedulerOptimizer
                syncConflicts = initialized.conflicts
                syncLogs = initialized.syncLogs
                pluginRendererAssignments = initialized.pluginRendererAssignments
                pluginRenderedCards = emptyMap()
                showSignIn = false
                syncMessage = initialized.syncMessage
                error = initialized.error
            }
            if (externalPluginsEnabled) {
                try {
                    pluginHostState = withContext(Dispatchers.Default) { luaPluginHost.reload() }
                } catch (pluginFailure: Exception) {
                    error = "Collection opened; plugins did not start: ${pluginFailure.message ?: "unknown error"}"
                }
            }
        } catch (exception: Exception) {
            error = exception.message ?: if (pending == null) {
                "Could not open or sync the local collection"
            } else {
                "Could not initialize the local account"
            }
            runCatching {
                log(SyncProgress(SyncLogLevel.Error, "FAILED", error.orEmpty()))
            }
        } finally {
            pendingAccountSignIn = null
            openingSavedAccount = false
            restored = true
            working = false
        }
    }
    LaunchedEffect(syncMessage, error) {
        if (syncMessage != null && error == null) {
            delay(5_000)
            syncMessage = null
        }
    }
    LaunchedEffect(store, studyDayPolicy) {
        var studyDay = studyDayAt(nowMillis, studyDayPolicy)
        while (true) {
            delay(30_000)
            nowMillis = currentEpochMillis()
            val nextStudyDay = studyDayAt(nowMillis, studyDayPolicy)
            if (nextStudyDay != studyDay) {
                val refreshed = withContext(Dispatchers.Default) {
                    store.loadLocalReviews(nowMillis) to store.loadStudyStats(nowMillis)
                }
                localReviews = refreshed.first
                studyStats = refreshed.second
                studyDay = nextStudyDay
            }
        }
    }

    val displayCollection = remember(collection, localContent) {
        collection.withLocalContent(localContent)
    }
    LaunchedEffect(
        store,
        ProjectionIdentity(displayCollection),
        restored,
        working,
        destination,
        selectedDeck?.id,
    ) {
        if (!restored || working || destination != CollectionDestination.Decks || selectedDeck != null) {
            return@LaunchedEffect
        }
        delay(750)
        try {
            withContext(Dispatchers.Default) { store.prepareBrowseIndex(displayCollection) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = "Could not prepare Browse index: ${failure.message ?: "unknown error"}"
        }
    }
    val pendingSyncByDeck = remember(localContent.pendingSyncByDeck, localReviews.pendingSyncByDeck) {
        aggregatePendingDeckChanges(
            localContent.pendingSyncByDeck,
            localReviews.pendingSyncByDeck,
        )
    }
    val deckProjection = rememberBaseDeckProjection(
        collection = displayCollection,
        localContent = localContent,
        localReviews = localReviews,
        nowMillis = nowMillis,
        pendingSyncByDeck = pendingSyncByDeck,
        selectedDeckId = selectedDeck?.id,
        foreground = destination == CollectionDestination.Decks,
    )
    val baseVisibleDecks = deckProjection.decks
    val visibleDecks = baseVisibleDecks
    val savedAccounts = accountRegistry.accounts()
        .map { LocalAccountChoice(it.username, it.endpoint) }
        .sortedBy { it.username.lowercase() }
    val baseActiveDeck = selectedDeck?.let { selected ->
        baseVisibleDecks.firstOrNull { it.id == selected.id } ?: selected
    }
    val activeDeck = remember(ProjectionIdentity(baseActiveDeck), ProjectionIdentity(pluginRenderedCards)) {
        baseActiveDeck?.let { deck ->
            if (pluginRenderedCards.isEmpty()) {
                deck
            } else {
                deck.copy(cards = deck.cards.map { pluginRenderedCards[it.id]?.rendered ?: it })
            }
        }
    }
    val pluginRendererGeneration = pluginHostState.runtimeGeneration
    LaunchedEffect(
        ProjectionIdentity(baseActiveDeck?.cards),
        ProjectionIdentity(pluginRendererAssignments),
        pluginRendererGeneration,
    ) {
        val targetDeck = baseActiveDeck
        if (targetDeck == null) {
            pluginRenderedCards = emptyMap()
            return@LaunchedEffect
        }
        val rendered = withContext(Dispatchers.Default) {
            renderAssignedReviewCards(
                host = luaPluginHost,
                registry = pluginRenderers,
                assignments = pluginRendererAssignments,
                collection = displayCollection,
                cards = targetDeck.cards,
                runtimeGeneration = pluginRendererGeneration,
                existing = pluginRenderedCards,
            )
        }
        pluginRenderedCards = rendered.cards
        pluginHostState = luaPluginHost.state()
        rendered.failure?.let { error = "Card renderer failed; original card shown: $it" }
    }
    val undoReview: suspend (String) -> ReviewCard? = { deckName ->
        try {
            val updated = withContext(Dispatchers.Default) {
                store.undoLastReview(deckName) to store.loadSchedulerOptimizer()
            }
            val undone = updated.first
            if (undone == null) {
                null
            } else {
                localReviews = undone.snapshot
                schedulerOptimizer = updated.second
                displayCollection.reviewCard(undone.cardId)
            }
        } catch (exception: Exception) {
            error = exception.message ?: "Could not undo the review"
            null
        }
    }

    val requestSync: () -> Unit = sync@{
        val activeToken = token
        if (activeToken == null) {
            error = null
            showSignIn = true
            return@sync
        }
        if (working || !restored) return@sync
        working = true
        error = null
        syncMessage = null
        scope.launch {
            suspend fun log(progress: SyncProgress) {
                syncLogs = withContext(Dispatchers.Default) { store.appendSyncLog(progress) }
            }
            try {
                log(SyncProgress(phase = "START", message = "Manual sync requested"))
                val completed = runSyncCycle(syncClient, store, activeToken, collection, ::log)
                collection = completed.report.collection
                localReviews = completed.localReviews
                localContent = completed.localContent
                schedulerProfile = completed.schedulerProfile
                studyDayPolicy = completed.studyDayPolicy
                studyStats = withContext(Dispatchers.Default) { store.loadStudyStats(nowMillis) }
                schedulerOptimizer = withContext(Dispatchers.Default) { store.loadSchedulerOptimizer() }
                syncConflicts = completed.conflicts
                if (completed.conflicts.isEmpty()) {
                    syncMessage = completed.report.syncMessage(
                        uploaded = completed.pushed.uploadedCount,
                        firstSync = false,
                    )
                } else {
                    error = syncConflictMessage(completed.conflicts.size)
                }
            } catch (exception: Exception) {
                val saved = withContext(Dispatchers.Default) { store.load() }
                collection = saved.collection
                localContent = saved.localContent
                localReviews = saved.localReviews
                schedulerProfile = saved.schedulerProfile
                studyDayPolicy = saved.studyDayPolicy
                schedulerOptimizer = saved.schedulerOptimizer
                syncConflicts = withContext(Dispatchers.Default) { store.loadSyncConflicts() }
                error = exception.message ?: "Could not sync with KelmaSync"
                log(SyncProgress(SyncLogLevel.Error, "FAILED", error.orEmpty()))
            } finally {
                working = false
            }
        }
    }

    val redownloadCollection: () -> Unit = redownload@{
        if (working || !restored || token == null) return@redownload
        working = true
        error = null
        syncMessage = null
        scope.launch {
            var syncAfterReset = false
            try {
                val saved = withContext(Dispatchers.Default) {
                    store.resetDownloadedCollectionForRedownload()
                }
                collection = saved.collection
                localContent = saved.localContent
                localReviews = saved.localReviews
                schedulerProfile = saved.schedulerProfile
                studyDayPolicy = saved.studyDayPolicy
                schedulerOptimizer = saved.schedulerOptimizer
                syncConflicts = emptyList()
                syncLogs = withContext(Dispatchers.Default) {
                    store.appendSyncLog(
                        SyncProgress(
                            level = SyncLogLevel.Warning,
                            phase = "RESET",
                            message = "Downloaded collection cleared; starting a complete redownload",
                        ),
                    )
                }
                syncAfterReset = true
            } catch (exception: Exception) {
                error = exception.message ?: "Could not reset the downloaded collection"
            } finally {
                working = false
            }
            if (syncAfterReset) requestSync()
        }
    }

    val openDecks: () -> Unit = {
        destination = destination.navigate(CollectionNavigationAction.OpenDecks)
        selectedDeck = null
        desktopStudyStarted = false
        preferredAddDeck = null
        preferredOptionsDeck = null
        initialBrowseQuery = ""
    }
    val openAdd: () -> Unit = {
        preferredAddDeck = null
        destination = destination.navigate(CollectionNavigationAction.OpenAdd)
    }
    val openBrowse: () -> Unit = {
        initialBrowseQuery = ""
        destination = destination.navigate(CollectionNavigationAction.OpenBrowse)
    }
    val openOptions: () -> Unit = {
        preferredOptionsDeck = activeDeck?.id
        destination = destination.navigate(CollectionNavigationAction.OpenOptions)
    }
    val openPlugins: () -> Unit = {
        if (externalPluginsEnabled) {
            destination = destination.navigate(CollectionNavigationAction.OpenPlugins)
            selectedDeck = null
            desktopStudyStarted = false
        }
    }
    val openStats: () -> Unit = {
        destination = destination.navigate(CollectionNavigationAction.OpenStats)
        selectedDeck = null
        desktopStudyStarted = false
        scope.launch {
            studyStats = withContext(Dispatchers.Default) { store.loadStudyStats(nowMillis) }
        }
    }
    val openSync: () -> Unit = {
        destination = destination.navigate(CollectionNavigationAction.OpenSync)
        selectedDeck = null
        desktopStudyStarted = false
    }
    suspend fun persistPresetChange(operation: () -> LocalContentSnapshot): String? = try {
        val updated = withContext(Dispatchers.Default) {
            operation() to store.loadLocalReviews(nowMillis)
        }
        localContent = updated.first
        localReviews = updated.second
        null
    } catch (exception: Exception) {
        exception.message ?: "Could not update deck presets"
    }
    val assignPreset: suspend (String, String?) -> String? = { deckName, presetId ->
        persistPresetChange { store.assignDeckOptionsPreset(deckName, presetId) }
    }
    val createPreset: suspend (String, String, DeckOptions) -> String? = { deckName, name, options ->
        persistPresetChange { store.createDeckOptionsPreset(deckName, name, options) }
    }
    val clonePreset: suspend (String, String, String) -> String? = { deckName, presetId, name ->
        persistPresetChange { store.cloneDeckOptionsPreset(deckName, presetId, name) }
    }
    val renamePreset: suspend (String, String) -> String? = { presetId, name ->
        persistPresetChange { store.renameDeckOptionsPreset(presetId, name) }
    }
    val deletePreset: suspend (String) -> String? = { presetId ->
        persistPresetChange { store.deleteDeckOptionsPreset(presetId) }
    }
    val applyAccountSchedulerProfile: suspend (DeckOptions, Boolean) -> String? = { options, publish ->
        try {
            val updated = withContext(Dispatchers.Default) {
                val profile = store.applyAccountSchedulerProfile(
                    SchedulerProfileSettings.fromDeckOptions(options),
                    publishToCloud = publish,
                )
                profile to store.loadLocalReviews(nowMillis)
            }
            schedulerProfile = updated.first
            localReviews = updated.second
            null
        } catch (exception: Exception) {
            exception.message ?: "Could not apply the account scheduler profile"
        }
    }
    val applyCloudSchedulerProfile: suspend () -> String? = {
        try {
            val updated = withContext(Dispatchers.Default) {
                val profile = store.applyCloudSchedulerProfileLocally()
                profile to store.loadLocalReviews(nowMillis)
            }
            schedulerProfile = updated.first
            localReviews = updated.second
            null
        } catch (exception: Exception) {
            exception.message ?: "Could not apply the KelmaSync scheduler profile"
        }
    }
    val saveStudyDayPolicy: suspend (String, Int) -> String? = save@{ timezoneId, dayStartHour ->
        val activeToken = token ?: return@save "Sign in before changing the account study day"
        try {
            val candidate = studyDayPolicy.copy(
                timezoneId = timezoneId.trim(),
                dayStartHour = dayStartHour,
            ).validated().toCandidate()
            val saved = syncClient.putStudyDayPolicy(activeToken, candidate)
            withContext(Dispatchers.Default) {
                store.observeCloudStudyDayPolicy(saved)
            }
            studyDayPolicy = saved
            localReviews = withContext(Dispatchers.Default) { store.loadLocalReviews(nowMillis) }
            studyStats = withContext(Dispatchers.Default) { store.loadStudyStats(nowMillis) }
            null
        } catch (exception: Exception) {
            exception.message ?: "Could not save the account study-day policy"
        }
    }
    val startSchedulerOptimization: () -> Unit = start@{
        if (schedulerOptimizer.running) return@start
        scope.launch {
            try {
                schedulerOptimizer = withContext(Dispatchers.Default) {
                    store.prepareSchedulerOptimization()
                }
                val jobId = schedulerOptimizer.job?.jobId ?: return@launch
                val poller = launch {
                    while (true) {
                        delay(200)
                        schedulerOptimizer = withContext(Dispatchers.Default) {
                            store.loadSchedulerOptimizer()
                        }
                        if (!schedulerOptimizer.running) break
                    }
                }
                schedulerOptimizer = withContext(Dispatchers.Default) {
                    store.runSchedulerOptimization(jobId)
                }
                poller.cancel()
                error = null
            } catch (exception: Exception) {
                schedulerOptimizer = withContext(Dispatchers.Default) {
                    store.loadSchedulerOptimizer()
                }
                error = exception.message ?: "Could not optimize scheduler parameters"
            }
        }
    }
    val cancelSchedulerOptimization: () -> Unit = {
        schedulerOptimizer.job?.jobId?.let { jobId ->
            scope.launch {
                schedulerOptimizer = withContext(Dispatchers.Default) {
                    store.cancelSchedulerOptimization(jobId)
                }
            }
        }
    }
    val applySchedulerOptimizerCandidate: suspend (Boolean) -> String? = apply@{ publish ->
        val candidateId = schedulerOptimizer.pendingCandidate?.candidateId
            ?: return@apply "Optimizer candidate is no longer available"
        try {
            val applied = withContext(Dispatchers.Default) {
                val result = store.applySchedulerOptimizerCandidate(candidateId, publish)
                Triple(result.first, result.second, store.loadLocalReviews(nowMillis))
            }
            schedulerOptimizer = applied.first
            schedulerProfile = applied.second
            localReviews = applied.third
            null
        } catch (exception: Exception) {
            exception.message ?: "Could not apply optimizer candidate"
        }
    }
    val discardSchedulerOptimizerCandidate: suspend () -> String? = discard@{
        val candidateId = schedulerOptimizer.pendingCandidate?.candidateId
            ?: return@discard "Optimizer candidate is no longer available"
        try {
            schedulerOptimizer = withContext(Dispatchers.Default) {
                store.discardSchedulerOptimizerCandidate(candidateId)
            }
            null
        } catch (exception: Exception) {
            exception.message ?: "Could not discard optimizer candidate"
        }
    }
    AppBackgroundEffect {
        schedulerOptimizer.job?.takeIf { it.status == SchedulerOptimizerJobStatus.Running }?.let { job ->
            scope.launch {
                schedulerOptimizer = withContext(Dispatchers.Default) {
                    store.interruptSchedulerOptimization(job.jobId)
                }
            }
        }
    }
    val setPluginRendererAssignment: suspend (PluginRendererScope, String, String?) -> String? =
        { rendererScope, targetId, rendererId ->
            try {
                pluginRendererAssignments = withContext(Dispatchers.Default) {
                    store.setPluginRendererAssignment(rendererScope, targetId, rendererId)
                }
                pluginRenderedCards = emptyMap()
                null
            } catch (exception: Exception) {
                exception.message ?: "Could not save the renderer assignment"
            }
        }
    val signIn: (String, String) -> Unit = signIn@{ username, password ->
        if (working || !restored) return@signIn
        working = true
        error = null
        syncMessage = null
        scope.launch {
            try {
                val auth = syncClient.login(username, password)
                val targetDatabase = accountRegistry.activate(DefaultKelmaSyncEndpoint, username)
                appState.clearDisplayedAccount()
                token = auth.token
                pendingAccountSignIn = PendingAccountSignIn(username, auth)
                showSignIn = false
                destination = CollectionDestination.Sync
                restored = false
                databaseName = targetDatabase
            } catch (exception: Exception) {
                error = exception.message ?: "Could not connect to KelmaSync"
                working = false
            }
        }
    }
    val selectAccount: (LocalAccountChoice) -> Unit = selectAccount@{ account ->
        if (working || !restored) return@selectAccount
        val targetDatabase = accountRegistry.databaseName(account.endpoint, account.username)
        if (targetDatabase == null) {
            error = "This saved account is no longer available on this device."
            return@selectAccount
        }
        working = true
        error = null
        syncMessage = null
        accountRegistry.activate(account.endpoint, account.username)
        pendingAccountSignIn = null
        openingSavedAccount = true
        appState.clearDisplayedAccount()
        showSignIn = true
        restored = false
        databaseName = targetDatabase
    }
    val signOut: () -> Unit = signOut@{
        if (working || !restored) return@signOut
        working = true
        accountRegistry.deactivate()
        pendingAccountSignIn = null
        openingSavedAccount = false
        appState.clearDisplayedAccount()
        error = null
        syncMessage = null
        showSignIn = true
        restored = false
        databaseName = GuestCollectionDatabaseName
    }
    val resolveSyncConflict: (SyncUploadConflict, Boolean) -> Unit = { conflict, keepLocal ->
        scope.launch {
            try {
                val updated = resolveSyncConflict(store, conflict, keepLocal)
                localContent = updated.first
                localReviews = updated.second
                syncConflicts = updated.third
                schedulerProfile = withContext(Dispatchers.Default) { store.loadSchedulerProfile() }
                error = null
            } catch (exception: Exception) {
                error = exception.message ?: "Could not resolve the sync conflict"
            }
        }
    }

    AppContent(
        state = appState,
        store = store,
        accountRegistry = accountRegistry,
        scope = scope,
        appFocusRequester = appFocusRequester,
        displayCollection = displayCollection,
        visibleDecks = visibleDecks,
        decksLoading = deckProjection.loading,
        activeDeck = activeDeck,
        savedAccounts = savedAccounts,
        interchangeDocuments = interchangePlatform.documents,
        interchangeService = interchangeService,
        luaPluginHost = luaPluginHost,
        pluginCommands = pluginCommands,
        pluginRenderers = pluginRenderers,
        externalPluginsEnabled = externalPluginsEnabled,
        actions = AppContentActions(
            undoReview = undoReview,
            requestSync = requestSync,
            redownloadCollection = redownloadCollection,
            signIn = signIn,
            selectAccount = selectAccount,
            signOut = signOut,
            openDecks = openDecks,
            openAdd = openAdd,
            openBrowse = openBrowse,
            openOptions = openOptions,
            openPlugins = openPlugins,
            openStats = openStats,
            openSync = openSync,
            assignPreset = assignPreset,
            createPreset = createPreset,
            clonePreset = clonePreset,
            renamePreset = renamePreset,
            deletePreset = deletePreset,
            applyAccountSchedulerProfile = applyAccountSchedulerProfile,
            applyCloudSchedulerProfile = applyCloudSchedulerProfile,
            saveStudyDayPolicy = saveStudyDayPolicy,
            startSchedulerOptimization = startSchedulerOptimization,
            cancelSchedulerOptimization = cancelSchedulerOptimization,
            applySchedulerOptimizerCandidate = applySchedulerOptimizerCandidate,
            discardSchedulerOptimizerCandidate = discardSchedulerOptimizerCandidate,
            setPluginRendererAssignment = setPluginRendererAssignment,
            resolveSyncConflict = resolveSyncConflict,
        ),
    )
}
