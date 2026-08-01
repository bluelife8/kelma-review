package tech.kelma.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class ReviewMenuShortcut(
    val sequence: Long,
    val command: ReviewMenuCommand,
)

@Composable
fun ReviewScreen(
    deck: DeckSummary,
    syncing: Boolean,
    canUndo: Boolean,
    options: DeckOptions = DeckOptions(),
    schedules: Map<Long, LocalCardSchedule> = emptyMap(),
    dueDateOverrides: Map<Long, Long> = emptyMap(),
    studyDayPolicy: AccountStudyDayPolicy = AccountStudyDayPolicy(dayStartHour = 0),
    loadMedia: (String) -> ByteArray? = { null },
    onSync: () -> Unit,
    onAdd: () -> Unit = {},
    onBrowse: () -> Unit = {},
    onOptions: () -> Unit = {},
    onCardFlagged: suspend (Long, Int) -> String? = { _, _ -> null },
    onCardBuried: suspend (Long) -> String? = { null },
    onCardReset: suspend (Long) -> String? = { null },
    onCardDueDateSet: suspend (Long, Long) -> String? = { _, _ -> null },
    onCardSuspended: suspend (Long) -> String? = { null },
    onNoteMarked: suspend (Long, Boolean) -> String? = { _, _ -> null },
    onNoteBuried: suspend (Long) -> String? = { null },
    onNoteSuspended: suspend (Long) -> String? = { null },
    onNoteCopied: suspend (Long) -> String? = { null },
    onNoteDeleted: suspend (Long) -> String? = { null },
    previewSchedule: (Long, Rating, Long) -> LocalCardSchedule? = { _, _, _ -> null },
    onCardReviewed: suspend (Long, Rating, Long) -> LocalCardSchedule?,
    onUndo: suspend () -> ReviewCard?,
    onBack: () -> Unit,
) {
    var session by remember(deck.id, studyDayPolicy) {
        mutableStateOf(
            ReviewSession.start(
                deck.cards,
                schedules,
                currentEpochMillis(),
                dueDateOverrides,
                studyDayPolicy,
            ),
        )
    }
    var buriedCardIds by remember(deck.id) { mutableStateOf<Set<Long>>(emptySet()) }
    var buriedNoteGuids by remember(deck.id) { mutableStateOf<Set<String>>(emptySet()) }
    var savingReview by remember(deck.id) { mutableStateOf(false) }
    var reviewError by remember(deck.id) { mutableStateOf<String?>(null) }
    var showUndoConfirmation by remember(deck.id) { mutableStateOf(false) }
    var cardStartedAt by remember(deck.id) { mutableLongStateOf(currentEpochMillis()) }
    var queueClockMillis by remember(deck.id) { mutableLongStateOf(currentEpochMillis()) }
    var ratingIntervals by remember(deck.id) { mutableStateOf<Map<Rating, String>>(emptyMap()) }
    var menuShortcutSequence by remember(deck.id) { mutableLongStateOf(0L) }
    var menuShortcut by remember(deck.id) { mutableStateOf<ReviewMenuShortcut?>(null) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val reveal = reveal@{
        if (savingReview || syncing || session.showingAnswer || session.isComplete) return@reveal
        val card = session.currentCard ?: return@reveal
        val previewedAtMillis = currentEpochMillis()
        ratingIntervals = Rating.entries.mapNotNull { rating ->
            previewSchedule(card.id, rating, previewedAtMillis)?.let { schedule ->
                rating to formatReviewInterval(schedule, previewedAtMillis)
            }
        }.toMap()
        session = session.revealAnswer()
    }
    val rate: (Rating) -> Unit = rate@{ rating ->
        val card = session.currentCard ?: return@rate
        if (savingReview || syncing || !session.showingAnswer) return@rate
        val duration = (currentEpochMillis() - cardStartedAt).coerceAtLeast(0)
        savingReview = true
        reviewError = null
        scope.launch {
            try {
                val schedule = onCardReviewed(card.id, rating, duration)
                if (schedule != null) {
                    val now = currentEpochMillis()
                    session = session.answer(rating, schedule, now)
                    queueClockMillis = now
                    cardStartedAt = now
                } else {
                    reviewError = "The review was not saved. Please try again."
                }
            } finally {
                savingReview = false
            }
        }
    }
    val setFlag: (ReviewFlag) -> Unit = flag@{ flag ->
        val card = session.currentCard ?: return@flag
        val previousFlag = card.flag
        session = session.mapCards { queued ->
            if (queued.id == card.id) queued.copy(flag = flag.value) else queued
        }
        reviewError = null
        scope.launch {
            onCardFlagged(card.id, flag.value)?.let { error ->
                session = session.mapCards { queued ->
                    if (queued.id == card.id && queued.flag == flag.value) {
                        queued.copy(flag = previousFlag)
                    } else {
                        queued
                    }
                }
                reviewError = error
            }
        }
    }
    val buryCard = bury@{
        val card = session.currentCard ?: return@bury
        if (savingReview || syncing) return@bury
        savingReview = true
        reviewError = null
        scope.launch {
            try {
                val error = onCardBuried(card.id)
                if (error == null) {
                    val now = currentEpochMillis()
                    session = session.buryCurrentCard(now)
                    queueClockMillis = now
                    cardStartedAt = now
                } else {
                    reviewError = error
                }
            } catch (exception: Exception) {
                reviewError = exception.message ?: "Could not bury the card"
            } finally {
                savingReview = false
            }
        }
    }
    val resetCard = reset@{
        val card = session.currentCard ?: return@reset
        if (savingReview || syncing) return@reset
        savingReview = true
        reviewError = null
        scope.launch {
            try {
                val error = onCardReset(card.id)
                if (error == null) {
                    val now = currentEpochMillis()
                    session = session.buryCurrentCard(now)
                    queueClockMillis = now
                    cardStartedAt = now
                } else {
                    reviewError = error
                }
            } catch (exception: Exception) {
                reviewError = exception.message ?: "Could not reset the card"
            } finally {
                savingReview = false
            }
        }
    }
    val setDueDate: (Long) -> Unit = setDue@{ dueAtMillis ->
        val card = session.currentCard ?: return@setDue
        if (savingReview || syncing) return@setDue
        savingReview = true
        reviewError = null
        scope.launch {
            try {
                val error = onCardDueDateSet(card.id, dueAtMillis)
                if (error == null) {
                    val now = currentEpochMillis()
                    buriedCardIds = buriedCardIds + card.id
                    session = session.buryCurrentCard(now)
                    queueClockMillis = now
                    cardStartedAt = now
                } else {
                    reviewError = error
                }
            } catch (exception: Exception) {
                reviewError = exception.message ?: "Could not set the due date"
            } finally {
                savingReview = false
            }
        }
    }
    val markNote = markNote@{
        val card = session.currentCard ?: return@markNote
        if (savingReview || syncing) return@markNote
        val marked = !card.noteMarked
        session = session.mapCards { queued ->
            if (queued.noteGuid == card.noteGuid) queued.copy(noteMarked = marked) else queued
        }
        reviewError = null
        scope.launch {
            val error = try {
                onNoteMarked(card.id, marked)
            } catch (exception: Exception) {
                exception.message ?: "Could not update the note mark"
            }
            if (error != null) {
                session = session.mapCards { queued ->
                    if (queued.noteGuid == card.noteGuid && queued.noteMarked == marked) {
                        queued.copy(noteMarked = !marked)
                    } else {
                        queued
                    }
                }
                reviewError = error
            }
        }
    }
    val buryNote = bury@{
        val card = session.currentCard ?: return@bury
        if (savingReview || syncing) return@bury
        val removedIds = session.cards
            .filter { it.noteGuid.isNotEmpty() && it.noteGuid == card.noteGuid }
            .mapTo(mutableSetOf(), ReviewCard::id)
            .ifEmpty { mutableSetOf(card.id) }
        savingReview = true
        reviewError = null
        scope.launch {
            try {
                val error = onNoteBuried(card.id)
                if (error == null) {
                    val now = currentEpochMillis()
                    if (card.noteGuid.isNotEmpty()) buriedNoteGuids = buriedNoteGuids + card.noteGuid
                    buriedCardIds = buriedCardIds + removedIds
                    session = session.buryCurrentNote(now)
                    queueClockMillis = now
                    cardStartedAt = now
                } else {
                    reviewError = error
                }
            } catch (exception: Exception) {
                reviewError = exception.message ?: "Could not bury the note"
            } finally {
                savingReview = false
            }
        }
    }
    val copyNote = copyNote@{
        val card = session.currentCard ?: return@copyNote
        if (savingReview || syncing) return@copyNote
        savingReview = true
        reviewError = null
        scope.launch {
            try {
                val error = onNoteCopied(card.id)
                if (error == null) reviewError = null else reviewError = error
            } catch (exception: Exception) {
                reviewError = exception.message ?: "Could not create the note copy"
            } finally {
                savingReview = false
            }
        }
    }
    val deleteNote = deleteNote@{
        val card = session.currentCard ?: return@deleteNote
        if (savingReview || syncing) return@deleteNote
        savingReview = true
        reviewError = null
        scope.launch {
            try {
                val error = onNoteDeleted(card.id)
                if (error == null) {
                    val now = currentEpochMillis()
                    session = session.buryCurrentNote(now)
                    queueClockMillis = now
                    cardStartedAt = now
                } else {
                    reviewError = error
                }
            } catch (exception: Exception) {
                reviewError = exception.message ?: "Could not delete the note"
            } finally {
                savingReview = false
            }
        }
    }
    val suspendCard = suspendCard@{
        val card = session.currentCard ?: return@suspendCard
        if (savingReview || syncing) return@suspendCard
        savingReview = true
        reviewError = null
        scope.launch {
            try {
                val error = onCardSuspended(card.id)
                if (error == null) {
                    val now = currentEpochMillis()
                    session = session.buryCurrentCard(now)
                    queueClockMillis = now
                    cardStartedAt = now
                } else {
                    reviewError = error
                }
            } catch (exception: Exception) {
                reviewError = exception.message ?: "Could not suspend the card"
            } finally {
                savingReview = false
            }
        }
    }
    val suspendNote = suspendNote@{
        val card = session.currentCard ?: return@suspendNote
        if (savingReview || syncing) return@suspendNote
        savingReview = true
        reviewError = null
        scope.launch {
            try {
                val error = onNoteSuspended(card.id)
                if (error == null) {
                    val now = currentEpochMillis()
                    session = session.buryCurrentNote(now)
                    queueClockMillis = now
                    cardStartedAt = now
                } else {
                    reviewError = error
                }
            } catch (exception: Exception) {
                reviewError = exception.message ?: "Could not suspend the note"
            } finally {
                savingReview = false
            }
        }
    }
    val undo = undo@{
        if (savingReview || syncing || !canUndo) return@undo
        savingReview = true
        reviewError = null
        scope.launch {
            try {
                val card = onUndo()
                if (card != null) {
                    session = session.restoreLastAnswer(card)
                    cardStartedAt = currentEpochMillis()
                } else {
                    reviewError = "There is no review to undo in this deck."
                }
            } finally {
                savingReview = false
            }
        }
    }
    val requestUndo = requestUndo@{
        if (savingReview || syncing || !canUndo) return@requestUndo
        if (options.confirmBeforeUndo) showUndoConfirmation = true else undo()
    }

    LaunchedEffect(deck.id) {
        if (isDesktopApp) focusRequester.requestFocus()
    }
    LaunchedEffect(
        deck.cards,
        deck.newCount,
        deck.learningCount,
        deck.dueCount,
        schedules,
        dueDateOverrides,
        buriedCardIds,
        buriedNoteGuids,
    ) {
        val visibleCards = deck.cards.filter { card ->
            card.id !in buriedCardIds && card.noteGuid !in buriedNoteGuids
        }
        val now = currentEpochMillis()
        session = session.reconcileLearningQueue(visibleCards, schedules, now, dueDateOverrides)
        queueClockMillis = now
    }
    LaunchedEffect(session.learningQueue, session.currentCard?.id) {
        while (session.isWaiting) {
            val now = currentEpochMillis()
            queueClockMillis = now
            val advanced = session.advanceTime(now)
            if (advanced.currentCard != null) {
                session = advanced
                break
            }
            val nextDue = session.nextLearningDueAtMillis ?: break
            delay((nextDue - now).coerceIn(1L, 1_000L))
        }
    }
    LaunchedEffect(session.currentCard?.id, session.showingAnswer) {
        cardStartedAt = currentEpochMillis()
        if (isDesktopApp) {
            delay(1L)
            focusRequester.requestFocus()
        }
    }
    val nextRepeatDueAtMillis = session.nextLearningDueAtMillis
    val dispatchMenuCommand: (ReviewMenuCommand) -> Unit = { command ->
        menuShortcutSequence++
        menuShortcut = ReviewMenuShortcut(menuShortcutSequence, command)
    }

    Surface(
        modifier = Modifier.fillMaxSize().then(
            if (!isDesktopApp) Modifier else Modifier
                .onPreviewKeyEvent { event ->
                    if (savingReview || syncing) return@onPreviewKeyEvent false
                    event.toReviewMenuCommand()?.let { command ->
                        dispatchMenuCommand(command)
                        return@onPreviewKeyEvent true
                    }
                    val shortcut = event.toReviewShortcut() ?: return@onPreviewKeyEvent false
                    if (!session.showingAnswer) {
                        if (shortcut == ReviewShortcut.Space) reveal() else return@onPreviewKeyEvent false
                    } else {
                        rate(shortcut.answerRating())
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
        ),
    ) {
        if (isDesktopApp) {
            Column(modifier = Modifier.safeContentPadding().fillMaxSize()) {
                DesktopTopToolbar(
                    onDecks = { if (!savingReview) onBack() },
                    onSync = { if (!savingReview) onSync() },
                    onAdd = { if (!savingReview) onAdd() },
                    onBrowse = { if (!savingReview) onBrowse() },
                    onOptions = { if (!savingReview) onOptions() },
                    syncing = syncing || savingReview,
                )
                ReviewBody(
                    session = session,
                    deck = deck,
                    desktopLayout = true,
                    savingReview = savingReview || syncing,
                    reviewError = reviewError,
                    canUndo = canUndo,
                    autoplayAudio = options.autoplayAudio,
                    ratingIntervals = ratingIntervals,
                    loadMedia = loadMedia,
                    nextRepeatDueAtMillis = nextRepeatDueAtMillis,
                    queueClockMillis = queueClockMillis,
                    onReveal = reveal,
                    onRate = rate,
                    onUndo = requestUndo,
                    onBuryCard = buryCard,
                    onResetCard = resetCard,
                    onSetDueDate = setDueDate,
                    onMarkNote = markNote,
                    onBuryNote = buryNote,
                    onSuspendCard = suspendCard,
                    onSuspendNote = suspendNote,
                    onCopyNote = copyNote,
                    onDeleteNote = deleteNote,
                    onOptions = onOptions,
                    onFlag = setFlag,
                    menuShortcut = menuShortcut,
                    onBack = onBack,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(
                modifier = Modifier.safeContentPadding().fillMaxSize(),
            ) {
                ReviewHeader(
                    deck = deck,
                    undoEnabled = canUndo && !savingReview && !syncing,
                    moreEnabled = session.currentCard != null && !savingReview && !syncing,
                    currentFlag = session.currentCard?.flag ?: 0,
                    currentNoteMarked = session.currentCard?.noteMarked == true,
                    onBack = { if (!savingReview) onBack() },
                    onUndo = requestUndo,
                    onMenuCommand = dispatchMenuCommand,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                ReviewBody(
                    session = session,
                    deck = deck,
                    desktopLayout = false,
                    savingReview = savingReview || syncing,
                    reviewError = reviewError,
                    canUndo = canUndo,
                    autoplayAudio = options.autoplayAudio,
                    ratingIntervals = ratingIntervals,
                    loadMedia = loadMedia,
                    nextRepeatDueAtMillis = nextRepeatDueAtMillis,
                    queueClockMillis = queueClockMillis,
                    onReveal = reveal,
                    onRate = rate,
                    onUndo = requestUndo,
                    onBuryCard = buryCard,
                    onResetCard = resetCard,
                    onSetDueDate = setDueDate,
                    onMarkNote = markNote,
                    onBuryNote = buryNote,
                    onSuspendCard = suspendCard,
                    onSuspendNote = suspendNote,
                    onCopyNote = copyNote,
                    onDeleteNote = deleteNote,
                    onOptions = onOptions,
                    onFlag = setFlag,
                    menuShortcut = menuShortcut,
                    onBack = onBack,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (showUndoConfirmation) {
            UndoReviewConfirmationDialog(
                onConfirm = {
                    showUndoConfirmation = false
                    undo()
                },
                onDismiss = { showUndoConfirmation = false },
            )
        }
    }
}

@Composable
private fun ReviewBody(
    session: ReviewSession,
    deck: DeckSummary,
    desktopLayout: Boolean,
    savingReview: Boolean,
    reviewError: String?,
    canUndo: Boolean,
    autoplayAudio: Boolean,
    ratingIntervals: Map<Rating, String>,
    loadMedia: (String) -> ByteArray?,
    nextRepeatDueAtMillis: Long?,
    queueClockMillis: Long,
    onReveal: () -> Unit,
    onRate: (Rating) -> Unit,
    onUndo: () -> Unit,
    onBuryCard: () -> Unit,
    onResetCard: () -> Unit,
    onSetDueDate: (Long) -> Unit,
    onMarkNote: () -> Unit,
    onBuryNote: () -> Unit,
    onSuspendCard: () -> Unit,
    onSuspendNote: () -> Unit,
    onCopyNote: () -> Unit,
    onDeleteNote: () -> Unit,
    onOptions: () -> Unit,
    onFlag: (ReviewFlag) -> Unit,
    menuShortcut: ReviewMenuShortcut?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        if (session.currentCard == null) {
            CompletionContent(
                cardCount = session.reviewedCount,
                canUndo = canUndo,
                showUndo = desktopLayout,
                savingReview = savingReview,
                nextRepeatDueAtMillis = nextRepeatDueAtMillis,
                nowMillis = queueClockMillis,
                onUndo = onUndo,
                onBack = onBack,
            )
        } else {
            CardContent(
                session = session,
                deck = deck,
                desktopLayout = desktopLayout,
                savingReview = savingReview,
                reviewError = reviewError,
                canUndo = canUndo,
                autoplayAudio = autoplayAudio,
                ratingIntervals = ratingIntervals,
                loadMedia = loadMedia,
                onReveal = onReveal,
                onRate = onRate,
                onUndo = onUndo,
                onBuryCard = onBuryCard,
                onResetCard = onResetCard,
                onSetDueDate = onSetDueDate,
                onMarkNote = onMarkNote,
                onBuryNote = onBuryNote,
                onSuspendCard = onSuspendCard,
                onSuspendNote = onSuspendNote,
                onCopyNote = onCopyNote,
                onDeleteNote = onDeleteNote,
                onOptions = onOptions,
                onFlag = onFlag,
                menuShortcut = menuShortcut,
            )
        }
    }
}

private fun ReviewShortcut.answerRating(): Rating = when (this) {
    ReviewShortcut.Space, ReviewShortcut.Three -> Rating.Good
    ReviewShortcut.One -> Rating.Again
    ReviewShortcut.Two -> Rating.Hard
    ReviewShortcut.Four -> Rating.Easy
}

private fun KeyEvent.toReviewMenuCommand(): ReviewMenuCommand? {
    if (type != KeyEventType.KeyUp) return null
    return reviewMenuCommandForKey(
        key = key,
        command = isMetaPressed || isCtrlPressed,
        alt = isAltPressed,
        shift = isShiftPressed,
    )
}

internal fun reviewMenuCommandForKey(
    key: Key,
    command: Boolean = false,
    alt: Boolean = false,
    shift: Boolean = false,
): ReviewMenuCommand? {
    if (command && !alt && !shift) {
        val flag = when (key) {
            Key.Zero -> ReviewFlag.None
            Key.One -> ReviewFlag.Red
            Key.Two -> ReviewFlag.Orange
            Key.Three -> ReviewFlag.Green
            Key.Four -> ReviewFlag.Blue
            Key.Five -> ReviewFlag.Pink
            Key.Six -> ReviewFlag.Turquoise
            Key.Seven -> ReviewFlag.Purple
            else -> null
        }
        if (flag != null) return ReviewMenuCommand.Flag(flag)
    }
    val action = when {
        command && alt && key == Key.N -> ReviewMoreAction.ResetCard
        command && shift && key == Key.D -> ReviewMoreAction.SetDueDate
        command && alt && key == Key.I -> ReviewMoreAction.PreviousCardInfo
        command && alt && key == Key.E -> ReviewMoreAction.CreateCopy
        command && key == Key.Backspace -> ReviewMoreAction.DeleteNote
        !command && !alt && shift && key == Key.Two -> ReviewMoreAction.SuspendCard
        !command && !alt && shift && key == Key.Eight -> ReviewMoreAction.MarkNote
        !command && !alt && shift && key == Key.One -> ReviewMoreAction.SuspendNote
        !command && !alt && shift && key == Key.V -> ReviewMoreAction.RecordOwnVoice
        !command && !alt && shift && key == Key.A -> ReviewMoreAction.AutoAdvance
        !command && !alt && !shift && key == Key.Minus -> ReviewMoreAction.BuryCard
        !command && !alt && !shift && key == Key.Equals -> ReviewMoreAction.BuryNote
        !command && !alt && !shift && key == Key.O -> ReviewMoreAction.Options
        !command && !alt && !shift && key == Key.I -> ReviewMoreAction.CardInfo
        !command && !alt && !shift && key == Key.R -> ReviewMoreAction.ReplayAudio
        !command && !alt && !shift && key == Key.Five -> ReviewMoreAction.PauseAudio
        !command && !alt && !shift && key == Key.Six -> ReviewMoreAction.AudioBackFive
        !command && !alt && !shift && key == Key.Seven -> ReviewMoreAction.AudioForwardFive
        !command && !alt && !shift && key == Key.V -> ReviewMoreAction.ReplayOwnVoice
        else -> null
    }
    return action?.let(ReviewMenuCommand::Action)
}

private fun KeyEvent.toReviewShortcut(): ReviewShortcut? {
    if (type != KeyEventType.KeyUp) return null
    return when (key) {
        Key.Spacebar -> ReviewShortcut.Space
        Key.One -> ReviewShortcut.One
        Key.Two -> ReviewShortcut.Two
        Key.Three -> ReviewShortcut.Three
        Key.Four -> ReviewShortcut.Four
        else -> null
    }
}
