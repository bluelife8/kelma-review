package tech.kelma.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun CardContent(
    session: ReviewSession,
    deck: DeckSummary,
    desktopLayout: Boolean,
    savingReview: Boolean,
    reviewError: String?,
    canUndo: Boolean,
    autoplayAudio: Boolean,
    ratingIntervals: Map<Rating, String>,
    loadMedia: (String) -> ByteArray? = { null },
    audioPlayer: AudioPlayer = remember { createAudioPlayer() },
    voiceRecorder: VoiceRecorder = rememberVoiceRecorder(),
    onReveal: () -> Unit,
    onRate: (Rating) -> Unit,
    onUndo: () -> Unit,
    onBuryCard: () -> Unit = {},
    onResetCard: () -> Unit = {},
    onSetDueDate: (Long) -> Unit = {},
    onMarkNote: () -> Unit = {},
    onBuryNote: () -> Unit = {},
    onSuspendCard: () -> Unit = {},
    onSuspendNote: () -> Unit = {},
    onCopyNote: () -> Unit = {},
    onDeleteNote: () -> Unit = {},
    onOptions: () -> Unit = {},
    onFlag: (ReviewFlag) -> Unit = {},
    onEditNote: () -> Unit = {},
    menuShortcut: ReviewMenuShortcut? = null,
) {
    val preparedCard = rememberPreparedReviewCard(session, desktopLayout, loadMedia)
    val card = preparedCard.card
    val richReview = shouldUseRichReviewCard()
    var moreMessage by remember(card.id) { mutableStateOf<String?>(null) }
    var moreMenuExpanded by remember(card.id) { mutableStateOf(false) }
    var infoCard by remember(card.id) { mutableStateOf<ReviewCard?>(null) }
    var resetConfirmationCard by remember(card.id) { mutableStateOf<ReviewCard?>(null) }
    var setDueDateCard by remember(card.id) { mutableStateOf<ReviewCard?>(null) }
    var copyConfirmationCard by remember(card.id) { mutableStateOf<ReviewCard?>(null) }
    var deleteConfirmationCard by remember(card.id) { mutableStateOf<ReviewCard?>(null) }
    var voiceDialogOpen by remember { mutableStateOf(false) }
    var voiceRecording by remember { mutableStateOf(false) }
    var voiceRecordingError by remember { mutableStateOf<String?>(null) }
    var recordedVoice by remember { mutableStateOf<VoiceRecording?>(null) }
    var autoAdvanceEnabled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun finishVoiceRecording() {
        val saved = voiceRecorder.stop()
        voiceRecording = false
        if (saved == null) {
            voiceRecordingError = "No voice recording was captured."
        } else {
            recordedVoice = saved
            voiceRecordingError = null
        }
    }
    DisposableEffect(audioPlayer, voiceRecorder) {
        onDispose {
            audioPlayer.close()
            voiceRecorder.close()
        }
    }
    AppBackgroundEffect {
        audioPlayer.stop()
        voiceRecorder.close()
        voiceRecording = false
    }
    LaunchedEffect(card, session.showingAnswer, autoplayAudio) {
        audioPlayer.stop()
        if (autoplayAudio) {
            val autoplay = if (session.showingAnswer) card.backAudio.firstOrNull() else card.frontAudio.firstOrNull()
            autoplay?.let(audioPlayer::play)
        }
    }
    val actionSurfaceOpen = moreMenuExpanded || voiceDialogOpen || infoCard != null ||
        resetConfirmationCard != null || setDueDateCard != null || copyConfirmationCard != null ||
        deleteConfirmationCard != null
    LaunchedEffect(autoAdvanceEnabled, card.id, session.showingAnswer, savingReview, actionSurfaceOpen) {
        if (!autoAdvanceEnabled || savingReview || actionSurfaceOpen) return@LaunchedEffect
        delay(if (session.showingAnswer) AutoAdvanceAnswerMillis else AutoAdvanceQuestionMillis)
        if (session.showingAnswer) onRate(Rating.Good) else onReveal()
    }
    LaunchedEffect(voiceRecording) {
        if (!voiceRecording) return@LaunchedEffect
        delay(MaximumVoiceRecordingMillis)
        if (voiceRecording) finishVoiceRecording()
    }
    val onMoreAction: (ReviewMoreAction) -> Unit = { action ->
        when (action) {
            ReviewMoreAction.BuryCard -> onBuryCard()
            ReviewMoreAction.ResetCard -> resetConfirmationCard = card
            ReviewMoreAction.SetDueDate -> setDueDateCard = card
            ReviewMoreAction.EditNote -> onEditNote()
            ReviewMoreAction.SuspendCard -> onSuspendCard()
            ReviewMoreAction.MarkNote -> onMarkNote()
            ReviewMoreAction.BuryNote -> onBuryNote()
            ReviewMoreAction.SuspendNote -> onSuspendNote()
            ReviewMoreAction.CreateCopy -> copyConfirmationCard = card
            ReviewMoreAction.DeleteNote -> deleteConfirmationCard = card
            ReviewMoreAction.Options -> onOptions()
            ReviewMoreAction.CardInfo -> infoCard = card
            ReviewMoreAction.PreviousCardInfo -> {
                infoCard = session.previousReviewedCard
                if (infoCard == null) moreMessage = "There is no previous card in this session."
            }
            ReviewMoreAction.ReplayAudio -> {
                val audio = if (session.showingAnswer) card.backAudio else card.frontAudio
                audio.firstOrNull()?.let {
                    audioPlayer.play(it)
                    moreMessage = null
                } ?: run { moreMessage = "This card has no audio on the current side." }
            }
            ReviewMoreAction.PauseAudio -> {
                moreMessage = if (audioPlayer.pause()) null else "There is no audio playing."
            }
            ReviewMoreAction.AudioBackFive -> {
                moreMessage = if (audioPlayer.seekBy(-5_000L)) null else "Play audio before seeking."
            }
            ReviewMoreAction.AudioForwardFive -> {
                moreMessage = if (audioPlayer.seekBy(5_000L)) null else "Play audio before seeking."
            }
            ReviewMoreAction.RecordOwnVoice -> {
                moreMessage = null
                voiceRecordingError = null
                voiceDialogOpen = true
            }
            ReviewMoreAction.ReplayOwnVoice -> {
                recordedVoice?.let {
                    audioPlayer.play(CardMedia(it.filename, it.bytes))
                    moreMessage = null
                } ?: run { moreMessage = "Record your voice first." }
            }
            ReviewMoreAction.AutoAdvance -> {
                autoAdvanceEnabled = !autoAdvanceEnabled
                moreMessage = null
            }
            else -> moreMessage = "${action.label} is not available yet."
        }
    }
    LaunchedEffect(menuShortcut) {
        when (val command = menuShortcut?.command) {
            is ReviewMenuCommand.Action -> onMoreAction(command.action)
            is ReviewMenuCommand.Flag -> onFlag(command.flag)
            null -> Unit
        }
    }

    if (desktopLayout) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                StudyCard(
                    card,
                    session.showingAnswer,
                    true,
                    audioPlayer::play,
                    preparedCard = preparedCard.documents,
                    forceFallback = actionSurfaceOpen,
                )
            }
            (reviewError ?: moreMessage)?.let {
                Text(it, color = KelmaColors.Bad, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider(color = KelmaDesktopColors.Border)
            DesktopReviewFooter(
                deck = deck,
                showingAnswer = session.showingAnswer,
                savingReview = savingReview,
                canUndo = canUndo,
                ratingIntervals = ratingIntervals,
                cardFlag = card.flag,
                noteMarked = card.noteMarked,
                autoAdvanceEnabled = autoAdvanceEnabled,
                onReveal = onReveal,
                onRate = onRate,
                onUndo = onUndo,
                moreExpanded = moreMenuExpanded,
                onMoreExpandedChange = { moreMenuExpanded = it },
                onMoreAction = onMoreAction,
                onFlag = onFlag,
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (card.flag in 1..7) {
                    Text(
                        "⚑",
                        modifier = Modifier.padding(end = 6.dp),
                        color = reviewFlagColor(card.flag),
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = "${session.currentIndex + 1} / ${session.cards.size}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("mobile-review-card")
                    .then(
                        when {
                            savingReview || richReview -> Modifier
                            !session.showingAnswer -> Modifier.clickable(onClick = onReveal)
                            else -> Modifier.pointerInput(card.id) {
                                detectTapGestures { offset ->
                                    onRate(if (offset.x < size.width / 2f) Rating.Again else Rating.Good)
                                }
                            }
                        },
                    ),
            ) {
                StudyCard(
                    card = card,
                    showingAnswer = session.showingAnswer,
                    desktopLayout = false,
                    onPlayAudio = audioPlayer::play,
                    onCardTap = { fraction ->
                        if (!savingReview) {
                            if (session.showingAnswer) {
                                onRate(if (fraction < 0.5f) Rating.Again else Rating.Good)
                            } else {
                                onReveal()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    preparedCard = preparedCard.documents,
                )
            }
            (reviewError ?: moreMessage)?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    color = KelmaColors.Bad,
                    fontSize = 12.sp,
                )
            }
            MobileReviewControls(
                showingAnswer = session.showingAnswer,
                savingReview = savingReview,
                ratingIntervals = ratingIntervals,
                onReveal = onReveal,
                onRate = onRate,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
    if (voiceDialogOpen) {
        VoiceRecordingDialog(
            recording = voiceRecording,
            hasRecording = recordedVoice != null,
            error = voiceRecordingError,
            onStart = {
                voiceRecordingError = null
                scope.launch {
                    val error = voiceRecorder.start()
                    voiceRecording = error == null
                    voiceRecordingError = error
                }
            },
            onStop = { scope.launch { finishVoiceRecording() } },
            onDismiss = {
                if (voiceRecording) voiceRecorder.close()
                voiceRecording = false
                voiceDialogOpen = false
                voiceRecordingError = null
            },
        )
    }
    setDueDateCard?.let {
        SetDueDateDialog(
            initialDate = formatDueDate(currentEpochMillis()),
            onDismiss = { setDueDateCard = null },
            onConfirm = { dueAtMillis ->
                setDueDateCard = null
                onSetDueDate(dueAtMillis)
            },
        )
    }
    copyConfirmationCard?.let {
        AlertDialog(
            onDismissRequest = { copyConfirmationCard = null },
            title = { Text("Create Copy") },
            text = { Text("Create a new note with the same fields, tags, cards, and decks?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        copyConfirmationCard = null
                        onCopyNote()
                    },
                ) { Text("Create Copy") }
            },
            dismissButton = {
                TextButton(onClick = { copyConfirmationCard = null }) { Text("Cancel") }
            },
        )
    }
    deleteConfirmationCard?.let {
        AlertDialog(
            onDismissRequest = { deleteConfirmationCard = null },
            title = { Text("Delete Note") },
            text = { Text("Delete this note and all of its cards? This action will synchronize.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmationCard = null
                        onDeleteNote()
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmationCard = null }) { Text("Cancel") }
            },
        )
    }
    resetConfirmationCard?.let {
        AlertDialog(
            onDismissRequest = { resetConfirmationCard = null },
            title = { Text("Reset Card") },
            text = {
                Text(
                    "Reset this card to New? Its review history will be kept, " +
                        "but this device will schedule it as a new card.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetConfirmationCard = null
                        onResetCard()
                    },
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirmationCard = null }) { Text("Cancel") }
            },
        )
    }
    infoCard?.let { shownCard ->
        AlertDialog(
            onDismissRequest = { infoCard = null },
            title = { Text("Card Info") },
            text = {
                Text(
                    "Deck: ${deck.name}\n" +
                        "Card ID: ${shownCard.id}\n" +
                        "Position: ${session.cards.indexOfFirst { it.id == shownCard.id } + 1} " +
                        "of ${session.cards.size}",
                )
            },
            confirmButton = {
                TextButton(onClick = { infoCard = null }) { Text("Close") }
            },
        )
    }
}

@Composable
internal fun CompletionContent(
    cardCount: Int,
    canUndo: Boolean,
    showUndo: Boolean,
    savingReview: Boolean,
    nextRepeatDueAtMillis: Long?,
    nowMillis: Long,
    onUndo: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (nextRepeatDueAtMillis == null) "Deck complete" else "Learning step pending",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (nextRepeatDueAtMillis == null) {
                "$cardCount cards reviewed"
            } else {
                "Next card in ${formatLearningWait(nextRepeatDueAtMillis - nowMillis)}"
            },
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(
            modifier = Modifier.padding(top = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showUndo && canUndo) {
                TextButton(enabled = !savingReview, onClick = onUndo) {
                    Text(if (savingReview) "Working…" else "Undo last review")
                }
            }
            Button(onClick = onBack) {
                Text("Back to decks")
            }
        }
    }
}

private fun formatLearningWait(waitMillis: Long): String {
    val totalSeconds = (waitMillis.coerceAtLeast(0L) + 999L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes == 0L) "${seconds}s" else "$minutes:${seconds.toString().padStart(2, '0')}"
}

internal fun formatReviewInterval(schedule: LocalCardSchedule, reviewedAtMillis: Long): String {
    val intervalMillis = (schedule.dueAtMillis - reviewedAtMillis).coerceAtLeast(1_000L)
    val seconds = (intervalMillis + 999L) / 1_000L
    if (seconds < 60L) return "${seconds}s"
    if (seconds < 3_600L) return "${(seconds + 59L) / 60L}m"
    if (seconds < 86_400L) return "${(seconds + 3_599L) / 3_600L}h"

    val days = schedule.scheduledDays.takeIf { it > 0 }
        ?: ((seconds + 86_399L) / 86_400L).toInt()
    if (days < 30) return "${days}d"
    if (days < 365) return formatTenths((days * 10 + 15) / 30, "mo")
    return formatTenths((days * 10 + 182) / 365, "y")
}

private fun formatTenths(value: Int, suffix: String): String =
    if (value % 10 == 0) "${value / 10}$suffix" else "${value / 10}.${value % 10}$suffix"

private const val AutoAdvanceQuestionMillis = 3_000L
private const val AutoAdvanceAnswerMillis = 5_000L
private const val MaximumVoiceRecordingMillis = 120_000L
