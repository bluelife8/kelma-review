package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Shape
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class BrowseNoteEdit(
    val noteGuid: String,
    val fields: List<String>,
    val tags: List<String>,
)

data class BrowseEditTarget(
    val row: BrowseCardRow,
    val fieldNames: List<String>,
    val values: List<String>,
)

/** Immutable snapshot the card browser renders from. */
internal data class BrowseUiState(
    val rows: List<BrowseCardRow>,
    val totalCount: Int,
    val query: TextFieldValue,
    val sorting: BrowseSorting,
    val selected: BrowseCardRow?,
    val selectedCard: ReviewCard?,
    val selectedEdit: BrowseEditTarget?,
    val decks: List<Pair<String, Int>>,
    val tags: List<Pair<String, Int>>,
    val nowMillis: Long,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
)

/** Callbacks the card browser invokes; shared by desktop and mobile. */
internal class BrowseActions(
    val onQueryChange: (TextFieldValue) -> Unit,
    val onApplyTerm: (String) -> Unit,
    val onSort: (BrowseSort) -> Unit,
    val onSelect: (Long?) -> Unit,
    val onLoadMore: () -> Unit,
    val onBack: () -> Unit,
    val onDecks: () -> Unit,
    val onSync: () -> Unit,
    val onOpenSync: () -> Unit,
    val onAdd: () -> Unit,
    val onOptions: () -> Unit,
    val onStudyDeck: (String) -> Unit,
    val onAttach: suspend (PickedMediaFile) -> String,
    val onSaveEdit: suspend (BrowseNoteEdit) -> String?,
    val onDelete: (BrowseCardRow) -> Unit,
)

@Composable
fun BrowseScreen(
    collection: SyncedCollection,
    schedules: Map<Long, LocalCardSchedule>,
    nowMillis: Long,
    dueDateOverrides: Map<Long, Long> = emptyMap(),
    syncing: Boolean,
    initialQuery: String = "",
    loadMedia: (String) -> ByteArray? = { null },
    onBack: () -> Unit,
    onDecks: () -> Unit,
    onSync: () -> Unit,
    onOpenSync: () -> Unit = onSync,
    onAdd: () -> Unit,
    onOptions: () -> Unit = {},
    onStudyDeck: (String) -> Unit,
    onAttach: suspend (PickedMediaFile) -> String = { it.filename },
    onSaveEdit: suspend (BrowseNoteEdit) -> String?,
    onDeleteNote: (BrowseCardRow) -> Unit,
) {
    var query by remember(initialQuery) {
        mutableStateOf(TextFieldValue(initialQuery, TextRange(initialQuery.length)))
    }
    var sorting by remember { mutableStateOf(BrowseSorting()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }

    val projection = rememberBrowseProjection(
        collection = collection,
        schedules = schedules,
        dueDateOverrides = dueDateOverrides,
        query = query.text,
        sorting = sorting,
        nowMillis = nowMillis,
    )
    val selected = selectedId?.let { id -> projection.rows.firstOrNull { it.cardId == id } }
    // Cards from reviewCard() carry media references without bytes; hydrate them
    // off the composition thread so the preview can decode images (the reviewer
    // hydrates the same way). Without this, browse renders "could not be decoded".
    val selectedCard by produceState<ReviewCard?>(null, collection, selected?.cardId) {
        val card = selected?.let { collection.reviewCard(it.cardId) }
        value = if (card != null && card.hasUnloadedMedia()) {
            withContext(Dispatchers.Default) { card.hydrateMedia(loadMedia) }
        } else {
            card
        }
    }
    val selectedEdit = remember(collection, selected?.noteGuid) {
        selected?.let { row ->
            val note = collection.notes[row.noteGuid] ?: return@let null
            val definition = collection.notetypes[note.notetypeId]?.definition ?: JsonObject(emptyMap())
            BrowseEditTarget(
                row = row,
                fieldNames = definition.fieldNames(note.fields.size),
                values = note.fields,
            )
        }
    }
    val state = BrowseUiState(
        rows = projection.rows,
        totalCount = projection.totalCount,
        query = query,
        sorting = sorting,
        selected = selected,
        selectedCard = selectedCard,
        selectedEdit = selectedEdit,
        decks = projection.decks,
        tags = projection.tags,
        nowMillis = nowMillis,
        loading = projection.loading,
        loadingMore = projection.loadingMore,
        hasMore = projection.hasMore,
    )
    val actions = BrowseActions(
        onQueryChange = { query = it },
        onApplyTerm = { term ->
            val merged = toggleQueryTerm(query.text, term)
            query = TextFieldValue(merged, TextRange(merged.length))
        },
        onSort = { field ->
            sorting = if (sorting.field == field) {
                sorting.copy(ascending = !sorting.ascending)
            } else {
                BrowseSorting(field, ascending = true)
            }
        },
        onSelect = { selectedId = it },
        onLoadMore = projection.onLoadMore,
        onBack = onBack,
        onDecks = onDecks,
        onSync = onSync,
        onOpenSync = onOpenSync,
        onAdd = onAdd,
        onOptions = onOptions,
        onStudyDeck = onStudyDeck,
        onAttach = onAttach,
        onSaveEdit = onSaveEdit,
        onDelete = onDeleteNote,
    )

    if (isDesktopApp) {
        DesktopBrowseScreen(state, actions, syncing)
    } else {
        MobileBrowseScreen(state, actions)
    }
}

/**
 * Builds the inline editor target for [cardId] from the displayed collection, returning null when
 * the card or its note is no longer available. The synthetic row carries only what the editor
 * reads (note identity, tags, and notetype name); faces are not rendered.
 */
internal fun SyncedCollection.noteEditTarget(cardId: Long): BrowseEditTarget? {
    val card = cards[cardId] ?: return null
    val note = notes[card.noteGuid] ?: return null
    val notetype = notetypes[note.notetypeId]
    val definition = notetype?.definition ?: JsonObject(emptyMap())
    val row = BrowseCardRow(
        cardId = card.cardId,
        noteGuid = note.guid,
        question = "",
        answer = "",
        deck = card.deckName,
        notetype = notetype?.name ?: "Basic",
        tags = note.tags,
        state = BrowseCardState.New,
        dueMillis = null,
        isLocal = card.cardId < 0,
        createdAtMillis = card.createdAtMillis(),
    )
    return BrowseEditTarget(
        row = row,
        fieldNames = definition.fieldNames(note.fields.size),
        values = note.fields,
    )
}

/**
 * Renders a card in the detail pane the same way the reviewer does: styled text, images, and
 * playable audio for both faces, separated by labeled dividers.
 */
@Composable
fun BrowseCardPreview(
    card: ReviewCard,
    labelColor: Color,
    dividerColor: Color,
    frontStyle: TextStyle,
    backStyle: TextStyle,
) {
    val audioPlayer = remember { createAudioPlayer() }
    DisposableEffect(audioPlayer) { onDispose { audioPlayer.close() } }
    LaunchedEffect(card.id) { audioPlayer.stop() }
    Column {
        Text("FRONT", color = labelColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(6.dp))
        RichOrFallbackCardFace(
            html = card.frontHtml,
            css = card.cardCss,
            text = card.front,
            audio = card.frontAudio,
            images = card.frontImages,
            blocks = card.frontBlocks,
            textStyle = frontStyle,
            desktop = isDesktopApp,
            onPlayAudio = audioPlayer::play,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = dividerColor)
        Text("BACK", color = labelColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(6.dp))
        RichOrFallbackCardFace(
            html = card.backHtml,
            css = card.cardCss,
            text = card.back,
            audio = card.backAudio,
            images = card.backImages,
            blocks = card.backBlocks,
            textStyle = backStyle,
            desktop = isDesktopApp,
            onPlayAudio = audioPlayer::play,
        )
    }
}

@Composable
internal fun BrowseInlineEditor(
    target: BrowseEditTarget,
    titleColor: Color,
    textSecondary: Color,
    accent: Color,
    surfaceColor: Color,
    borderColor: Color,
    shape: Shape,
    contentPadding: Dp,
    onAttach: suspend (PickedMediaFile) -> String,
    onSave: suspend (BrowseNoteEdit) -> String?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    var values by remember(target.row.noteGuid) {
        mutableStateOf(
            target.fieldNames.indices.map { index ->
                TextFieldValue(target.values.getOrElse(index) { "" })
            },
        )
    }
    var tags by remember(target.row.noteGuid) {
        mutableStateOf(target.row.tags.joinToString(", "))
    }
    var saving by remember(target.row.noteGuid) { mutableStateOf(false) }
    var focusedField by remember(target.row.noteGuid) { mutableStateOf(0) }
    var error by remember(target.row.noteGuid) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val mediaPicker = rememberMediaPicker()

    fun attach(kind: AttachmentKind) {
        if (saving) return
        scope.launch {
            try {
                val picked = mediaPicker.pick(kind) ?: return@launch
                val filename = normalizeMediaFilename(picked.filename)
                val savedFilename = onAttach(picked.copy(filename = filename))
                val value = values.getOrNull(focusedField) ?: return@launch
                val marker = if (kind == AttachmentKind.Image) {
                    "<img src=\"${savedFilename.replace("&", "&amp;").replace("\"", "&quot;")}\">"
                } else {
                    "[sound:$savedFilename]"
                }
                val start = value.selection.min
                val end = value.selection.max
                val text = value.text.replaceRange(start, end, marker)
                values = values.mapIndexed { index, previous ->
                    if (index == focusedField) TextFieldValue(text, TextRange(start + marker.length)) else previous
                }
                error = null
            } catch (exception: Exception) {
                error = exception.message ?: "Could not attach media"
            }
        }
    }

    fun save() {
        if (saving) return
        if (values.firstOrNull()?.text.isNullOrBlank()) {
            error = "The first field cannot be empty"
            return
        }
        saving = true
        error = null
        val edit = BrowseNoteEdit(
            noteGuid = target.row.noteGuid,
            fields = values.map { it.text },
            tags = tags.split(Regex("[,\\s]+"))
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct(),
        )
        scope.launch {
            val saveError = onSave(edit)
            saving = false
            if (saveError == null) onSaved() else error = saveError
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "EDIT NOTE",
                modifier = Modifier.weight(1f),
                color = titleColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            TextButton(onClick = { attach(AttachmentKind.Image) }, enabled = !saving) { Text("Image") }
            TextButton(onClick = { attach(AttachmentKind.Audio) }, enabled = !saving) { Text("Audio") }
            TextButton(onClick = onCancel, enabled = !saving) {
                Text("Cancel", color = textSecondary)
            }
            TextButton(onClick = ::save, enabled = !saving, modifier = Modifier.testTag("browse-edit-save")) {
                Text(if (saving) "Saving…" else "Save", color = accent, fontWeight = FontWeight.Bold)
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = surfaceColor,
            shape = shape,
            border = BorderStroke(1.dp, borderColor),
        ) {
            Column(Modifier.padding(contentPadding)) {
                Text(
                    "Source text · ${target.row.notetype}",
                    color = textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                target.fieldNames.forEachIndexed { index, name ->
                    OutlinedTextField(
                        value = values.getOrElse(index) { TextFieldValue() },
                        onValueChange = { value ->
                            values = values.mapIndexed { i, previous -> if (i == index) value else previous }
                            error = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .heightIn(min = 96.dp)
                            .onFocusChanged { if (it.isFocused) focusedField = index }
                            .testTag("browse-edit-field-$index"),
                        label = { Text(name) },
                        minLines = 3,
                    )
                }
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it; error = null },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("browse-edit-tags"),
                    label = { Text("Tags") },
                    singleLine = true,
                )
                error?.let {
                    Text(it, modifier = Modifier.padding(top = 8.dp), color = KelmaColors.Bad, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun DeleteNoteDialog(
    row: BrowseCardRow,
    surface: Color,
    textPrimary: Color,
    textSecondary: Color,
    accent: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = surface,
        title = { Text("Delete note?", color = textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "“${row.question}” and its cards, schedules, and review history on this device will be removed.",
                color = textSecondary,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = KelmaColors.Bad, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = accent, fontWeight = FontWeight.Medium)
            }
        },
    )
}
