package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CollectionInterchangeUiState {
    var exportDeckName by mutableStateOf<String?>(null)
    var showExportDialog by mutableStateOf(false)
    var importDocument by mutableStateOf<InterchangeDocument?>(null)

    fun requestExport(deckName: String?) {
        exportDeckName = deckName
        showExportDialog = true
    }

    fun requestImport(
        scope: CoroutineScope,
        documents: CollectionDocumentIO,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            try {
                importDocument = documents.open()
            } catch (exception: Exception) {
                onError(exception.message ?: "Could not open the import file")
            }
        }
    }
}

@Composable
internal fun rememberCollectionInterchangeUiState(): CollectionInterchangeUiState = remember {
    CollectionInterchangeUiState()
}

@Composable
internal fun CollectionInterchangeHost(
    uiState: CollectionInterchangeUiState,
    deckNames: List<String>,
    collection: SyncedCollection,
    localContent: LocalContentSnapshot,
    localReviews: LocalReviewSnapshot,
    defaultDeckOptions: DeckOptions,
    nowMillis: Long,
    store: PersistentCollectionStore,
    documents: CollectionDocumentIO,
    service: CollectionInterchangeService,
    onImported: (ImportedCollectionState) -> Unit,
    onMessage: (String) -> Unit,
) {
    if (uiState.showExportDialog) {
        CollectionExportDialog(
            deckNames = deckNames,
            initialDeckName = uiState.exportDeckName,
            onDismiss = { uiState.showExportDialog = false },
            onExport = { options ->
                try {
                    val export = withContext(Dispatchers.Default) {
                        val effectiveDeckOptions = collection.deckNames.associateWith { deckName ->
                            localContent.deckOptions[deckName] ?: defaultDeckOptions
                        } + localContent.deckOptions
                        service.export(
                            collection = if (options.includeMedia) {
                                store.hydrateMediaForExport(collection)
                            } else {
                                collection
                            },
                            options = options,
                            deckOptions = effectiveDeckOptions,
                            presets = localContent.deckPresets,
                            schedules = localReviews.schedules,
                            localReviews = store.loadLocalReviewExports(),
                        )
                    }
                    if (documents.save(export.filename, export.mimeType, export.bytes)) {
                        onMessage("Exported ${export.filename}")
                    }
                    null
                } catch (exception: Exception) {
                    exception.message ?: "Could not export the collection"
                }
            },
        )
    }
    uiState.importDocument?.let { document ->
        CollectionImportDialog(
            document = document,
            deckNames = deckNames,
            initialTextKind = if (document.filename.endsWith(".txt", ignoreCase = true)) {
                service.detectTextKind(document)
            } else {
                TextImportKind.Notes
            },
            onDismiss = { uiState.importDocument = null },
            onPreview = { kind, deck ->
                withContext(Dispatchers.Default) { service.previewImport(document, kind, deck) }
            },
            onImport = { plan ->
                try {
                    val imported = withContext(Dispatchers.Default) {
                        val report = store.importCollection(plan)
                        ImportedCollectionState(
                            report = report,
                            content = store.loadLocalContent(),
                            reviews = store.loadLocalReviews(nowMillis),
                            optimizer = store.loadSchedulerOptimizer(),
                        )
                    }
                    onImported(imported)
                    onMessage(imported.report.message)
                    null
                } catch (exception: Exception) {
                    exception.message ?: "Could not import the collection"
                }
            },
        )
    }
}
