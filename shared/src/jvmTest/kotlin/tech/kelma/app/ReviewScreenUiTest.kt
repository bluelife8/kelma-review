package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred

@OptIn(ExperimentalTestApi::class)
class ReviewScreenUiTest {
    @Test
    fun desktopSpaceAndNumberShortcutsRemainFunctional() = runComposeUiTest {
        val rated = AtomicReference<Rating?>(null)
        val card = ReviewCard(
            id = 8,
            front = "shortcut front",
            back = "shortcut back",
            frontAudio = listOf(CardMedia("voice.mp3", byteArrayOf(1))),
            frontHtml = "shortcut front [sound:voice.mp3]",
            backHtml = "shortcut back",
        )
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary("Keys", "Keys", listOf(card), 1, 0, 0),
                    syncing = false,
                    canUndo = false,
                    options = DeckOptions(autoplayAudio = false),
                    onSync = {},
                    previewSchedule = { cardId, rating, reviewedAtMillis ->
                        val days = when (rating) {
                            Rating.Again -> 0
                            Rating.Hard -> 2
                            Rating.Good -> 4
                            Rating.Easy -> 7
                        }
                        committedSchedule(
                            cardId = cardId,
                            phase = if (rating == Rating.Again) ReviewPhase.Learning else ReviewPhase.Review,
                            dueAtMillis = reviewedAtMillis + if (days == 0) 60_000L else days * MillisPerDay,
                            step = if (rating == Rating.Again) 0 else null,
                            scheduledDays = days,
                        )
                    },
                    onCardReviewed = { cardId, rating, _ ->
                        rated.set(rating)
                        committedSchedule(cardId)
                    },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }
        waitForIdle()

        onRoot().performKeyInput { keyDown(Key.Spacebar); keyUp(Key.Spacebar) }
        onNodeWithText("shortcut back").assertIsDisplayed()
        onNodeWithText("1m").assertIsDisplayed()
        onNodeWithText("4d").assertIsDisplayed()
        onRoot().performKeyInput { keyDown(Key.Three); keyUp(Key.Three) }
        waitUntil(timeoutMillis = 5_000) { rated.get() != null }

        assertEquals(Rating.Good, rated.get())
    }

    @Test
    fun mobileCardKeepsAnswerControlsVisibleWithOversizedContent() = runComposeUiTest {
        val rated = AtomicReference<Rating?>(null)
        val card = ReviewCard(9, "long front ".repeat(200), "back")
        val deck = DeckSummary("Mobile", "Mobile", listOf(card), 1, 0, 0)
        setContent {
            var session by remember { mutableStateOf(ReviewSession(deck.cards)) }
            KelmaTheme {
                androidx.compose.foundation.layout.Box(Modifier.size(390.dp, 700.dp)) {
                    CardContent(
                        session = session,
                        deck = deck,
                        desktopLayout = false,
                        savingReview = false,
                        reviewError = null,
                        canUndo = false,
                        autoplayAudio = false,
                        ratingIntervals = emptyMap(),
                        onReveal = { session = session.revealAnswer() },
                        onRate = { rated.set(it) },
                        onUndo = {},
                    )
                }
            }
        }

        onNodeWithText("Show answer").assertIsDisplayed()
        onNodeWithTag("mobile-review-card").performClick()
        onNodeWithText("Tap card: left = Again · right = Good").assertIsDisplayed()
        onNodeWithText("Again").assertIsDisplayed()
        onNodeWithText("Hard").assertIsDisplayed()
        onNodeWithText("Easy").assertIsDisplayed()
        onNodeWithText("Good").assertIsDisplayed()

        val cardNode = onNodeWithTag("mobile-review-card")
        val width = cardNode.fetchSemanticsNode().boundsInRoot.width
        cardNode.performTouchInput { click(Offset(width * 0.25f, center.y)) }
        assertEquals(Rating.Again, rated.get())
        cardNode.performTouchInput { click(Offset(width * 0.75f, center.y)) }
        assertEquals(Rating.Good, rated.get())
    }

    @Test
    fun againUsesLearnAheadWhenTheRegularQueueIsEmpty() = runComposeUiTest {
        val card = ReviewCard(10, "repeat front", "repeat back")
        val reviewCount = AtomicInteger()
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary("Learning", "Learning", listOf(card), 1, 0, 0),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    onCardReviewed = { cardId, _, _ ->
                        reviewCount.incrementAndGet()
                        committedSchedule(
                            cardId = cardId,
                            phase = ReviewPhase.Learning,
                            dueAtMillis = currentEpochMillis() + 60_000L,
                            step = 0,
                        )
                    },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }

        onNodeWithText("Show Answer").performClick()
        waitForIdle()
        onRoot().performKeyInput { keyDown(Key.One); keyUp(Key.One) }
        waitUntil(timeoutMillis = 5_000) {
            reviewCount.get() == 1 && runCatching {
                onNodeWithText("Show Answer").assertIsDisplayed()
            }.isSuccess
        }
        onNodeWithText("repeat front").assertIsDisplayed()
    }

    @Test
    fun desktopMoreMenuOpensOptionsAndBuriesCurrentCard() = runComposeUiTest {
        val optionsOpened = AtomicBoolean(false)
        val savedFlag = AtomicInteger(0)
        val buriedCard = AtomicReference<Long?>(null)
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary(
                        id = "More",
                        name = "More",
                        cards = listOf(
                            ReviewCard(1, "first card", "first answer", noteGuid = "note-1"),
                            ReviewCard(2, "second card", "second answer", noteGuid = "note-2"),
                        ),
                        newCount = 2,
                        learningCount = 0,
                        dueCount = 0,
                    ),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    onOptions = { optionsOpened.set(true) },
                    onCardFlagged = { _, flag -> savedFlag.set(flag); null },
                    onCardBuried = { cardId -> buriedCard.set(cardId); null },
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-Options").performClick()
        assertTrue(optionsOpened.get())
        onRoot().performKeyInput {
            keyDown(Key.MetaLeft)
            keyDown(Key.Three)
            keyUp(Key.Three)
            keyUp(Key.MetaLeft)
        }
        waitUntil { savedFlag.get() == ReviewFlag.Green.value }
        onNodeWithText("⚑").assertIsDisplayed()
        onRoot().performKeyInput {
            keyDown(Key.MetaLeft)
            keyDown(Key.Zero)
            keyUp(Key.Zero)
            keyUp(Key.MetaLeft)
        }
        waitUntil { savedFlag.get() == ReviewFlag.None.value }
        onNodeWithText("⚑").assertDoesNotExist()

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-BuryCard").performClick()
        waitUntil { buriedCard.get() == 1L }
        onNodeWithText("second card").assertIsDisplayed()
    }

    @Test
    fun failedCardFlagSaveRollsBackTheOptimisticFlag() = runComposeUiTest {
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary(
                        id = "Flag failure",
                        name = "Flag failure",
                        cards = listOf(ReviewCard(1, "card", "answer", noteGuid = "note-1")),
                        newCount = 1,
                        learningCount = 0,
                        dueCount = 0,
                    ),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    onCardFlagged = { _, _ -> "Could not save flag" },
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-FlagCard").performClick()
        onNodeWithTag("review-flag-Red").performClick()

        onNodeWithText("Could not save flag").assertIsDisplayed()
        onNodeWithText("⚑").assertDoesNotExist()
    }

    @Test
    fun resetCardRequiresConfirmationAndAdvancesAfterSaving() = runComposeUiTest {
        val resetCard = AtomicReference<Long?>(null)
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary(
                        id = "Reset",
                        name = "Reset",
                        cards = listOf(
                            ReviewCard(1, "first card", "answer", noteGuid = "note-1"),
                            ReviewCard(2, "second card", "answer", noteGuid = "note-2"),
                        ),
                        newCount = 2,
                        learningCount = 0,
                        dueCount = 0,
                    ),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    onCardReset = { cardId -> resetCard.set(cardId); null },
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-ResetCard").performClick()
        onNodeWithText("Reset Card").assertIsDisplayed()
        onNodeWithText("Cancel").performClick()
        assertEquals(null, resetCard.get())
        onNodeWithText("first card").assertIsDisplayed()

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-ResetCard").performClick()
        onNodeWithText("Reset").performClick()

        waitUntil { resetCard.get() == 1L }
        onNodeWithText("second card").assertIsDisplayed()
    }

    @Test
    fun setDueDateValidatesAndAdvancesAfterSaving() = runComposeUiTest {
        val changed = AtomicReference<Pair<Long, Long>?>(null)
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary(
                        id = "Due",
                        name = "Due",
                        cards = listOf(
                            ReviewCard(1, "first card", "answer", noteGuid = "note-1"),
                            ReviewCard(2, "second card", "answer", noteGuid = "note-2"),
                        ),
                        newCount = 2,
                        learningCount = 0,
                        dueCount = 0,
                    ),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    onCardDueDateSet = { cardId, dueAtMillis ->
                        changed.set(cardId to dueAtMillis)
                        null
                    },
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-SetDueDate").performClick()
        onNodeWithText("Set Due Date").assertIsDisplayed()
        onNodeWithTag("set-due-date-input").performTextReplacement("2028-02-29")
        onNodeWithText("Set").performClick()

        val expectedDueAt = requireNotNull(parseDueDateMillis("2028-02-29"))
        waitUntil { changed.get() == (1L to expectedDueAt) }
        onNodeWithText("second card").assertIsDisplayed()
    }

    @Test
    fun failedCardBuryKeepsTheCurrentCard() = runComposeUiTest {
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary(
                        id = "Bury failure",
                        name = "Bury failure",
                        cards = listOf(ReviewCard(1, "current card", "answer", noteGuid = "note-1")),
                        newCount = 1,
                        learningCount = 0,
                        dueCount = 0,
                    ),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    onCardBuried = { "Could not save bury" },
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-BuryCard").performClick()

        onNodeWithText("Could not save bury").assertIsDisplayed()
        onNodeWithText("current card").assertIsDisplayed()
    }

    @Test
    fun desktopMoreMenuSuspendsCardsAndNotes() = runComposeUiTest {
        val suspendedCard = AtomicReference<Long?>(null)
        val suspendedNote = AtomicReference<Long?>(null)
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary(
                        id = "Suspend",
                        name = "Suspend",
                        cards = listOf(
                            ReviewCard(1, "first card", "first answer", noteGuid = "note-1"),
                            ReviewCard(2, "second sibling", "second answer", noteGuid = "note-1"),
                            ReviewCard(3, "third card", "third answer", noteGuid = "note-2"),
                        ),
                        newCount = 3,
                        learningCount = 0,
                        dueCount = 0,
                    ),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    onCardSuspended = { cardId -> suspendedCard.set(cardId); null },
                    onNoteSuspended = { cardId -> suspendedNote.set(cardId); null },
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-SuspendNote").performClick()
        waitUntil(timeoutMillis = 5_000) { suspendedNote.get() == 1L }
        onNodeWithText("third card").assertIsDisplayed()

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-SuspendCard").performClick()
        waitUntil(timeoutMillis = 5_000) { suspendedCard.get() == 3L }
        onNodeWithText("Deck complete").assertIsDisplayed()
    }

    @Test
    fun desktopMoreMenuMarksAndPersistentlyBuriesNotes() = runComposeUiTest {
        val marked = AtomicReference<Pair<Long, Boolean>?>(null)
        val buried = AtomicReference<Long?>(null)
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary(
                        id = "Note actions",
                        name = "Note actions",
                        cards = listOf(
                            ReviewCard(1, "first card", "answer", noteGuid = "note-1"),
                            ReviewCard(2, "sibling", "answer", noteGuid = "note-1"),
                            ReviewCard(3, "next note", "answer", noteGuid = "note-2"),
                        ),
                        newCount = 3,
                        learningCount = 0,
                        dueCount = 0,
                    ),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    onNoteMarked = { cardId, value -> marked.set(cardId to value); null },
                    onNoteBuried = { cardId -> buried.set(cardId); null },
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-MarkNote").performClick()
        waitUntil { marked.get() == (1L to true) }
        onNodeWithText("More").performClick()
        onNodeWithText("Unmark Note").assertIsDisplayed().performClick()
        waitUntil { marked.get() == (1L to false) }

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-BuryNote").performClick()
        waitUntil { buried.get() == 1L }
        onNodeWithText("next note").assertIsDisplayed()
    }

    @Test
    fun createCopyAndDeleteNoteRequireConfirmation() = runComposeUiTest {
        val copied = AtomicReference<Long?>(null)
        val deleted = AtomicReference<Long?>(null)
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary(
                        id = "Copy delete",
                        name = "Copy delete",
                        cards = listOf(
                            ReviewCard(1, "first note", "answer", noteGuid = "note-1"),
                            ReviewCard(2, "second note", "answer", noteGuid = "note-2"),
                        ),
                        newCount = 2,
                        learningCount = 0,
                        dueCount = 0,
                    ),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    onNoteCopied = { cardId -> copied.set(cardId); null },
                    onNoteDeleted = { cardId -> deleted.set(cardId); null },
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-CreateCopy").performClick()
        onAllNodesWithText("Create Copy").onFirst().assertIsDisplayed()
        onNodeWithText("Cancel").performClick()
        assertEquals(null, copied.get())
        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-CreateCopy").performClick()
        onAllNodesWithText("Create Copy").onLast().performClick()
        waitUntil { copied.get() == 1L }
        onNodeWithText("first note").assertIsDisplayed()

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-DeleteNote").performClick()
        onNodeWithText("Delete Note").assertIsDisplayed()
        onNodeWithText("Cancel").performClick()
        assertEquals(null, deleted.get())
        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-DeleteNote").performClick()
        onNodeWithText("Delete").performClick()
        waitUntil { deleted.get() == 1L }
        onNodeWithText("second note").assertIsDisplayed()
    }

    @Test
    fun audioActionsReportMissingMediaAndOpenVoiceRecorder() = runComposeUiTest {
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary(
                        id = "Audio",
                        name = "Audio",
                        cards = listOf(ReviewCard(1, "question", "answer", noteGuid = "note")),
                        newCount = 1,
                        learningCount = 0,
                        dueCount = 0,
                    ),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-AudioBackFive").performClick()
        onNodeWithText("Play audio before seeking.").assertIsDisplayed()
        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-PauseAudio").performClick()
        onNodeWithText("There is no audio playing.").assertIsDisplayed()
        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-ReplayOwnVoice").performClick()
        onNodeWithText("Record your voice first.").assertIsDisplayed()

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-RecordOwnVoice").performClick()
        onNodeWithText("Record Own Voice").assertIsDisplayed()
        onNodeWithTag("voice-record-start").assertIsDisplayed()
        onNodeWithText("Close").performClick()
    }

    @Test
    fun audioPlaybackSeekingAndVoiceRecordingUseThePlatformControllers() = runComposeUiTest {
        val played = mutableListOf<String>()
        val seeks = mutableListOf<Long>()
        var paused = 0
        val player = object : AudioPlayer {
            override fun play(media: CardMedia) { played += media.filename }
            override fun pause(): Boolean { paused++; return true }
            override fun seekBy(offsetMillis: Long): Boolean { seeks += offsetMillis; return true }
            override fun stop() = Unit
            override fun close() = Unit
        }
        val recorder = object : VoiceRecorder {
            override var isRecording = false
            override suspend fun start(): String? { isRecording = true; return null }
            override suspend fun stop(): VoiceRecording? {
                isRecording = false
                return VoiceRecording("own-voice.wav", byteArrayOf(1, 2, 3))
            }
            override fun close() { isRecording = false }
        }
        setContent {
            KelmaTheme {
                CardContent(
                    session = ReviewSession(
                        listOf(
                            ReviewCard(
                                1,
                                "audio question",
                                "answer",
                                frontAudio = listOf(CardMedia("card.mp3", byteArrayOf(1))),
                            ),
                        ),
                    ),
                    deck = DeckSummary("Audio controls", "Audio controls", emptyList(), 1, 0, 0),
                    desktopLayout = true,
                    savingReview = false,
                    reviewError = null,
                    canUndo = false,
                    autoplayAudio = false,
                    ratingIntervals = emptyMap(),
                    audioPlayer = player,
                    voiceRecorder = recorder,
                    onReveal = {},
                    onRate = {},
                    onUndo = {},
                )
            }
        }

        fun action(action: ReviewMoreAction) {
            onNodeWithText("More").performClick()
            onNodeWithTag("review-more-${action.name}").performClick()
        }
        action(ReviewMoreAction.ReplayAudio)
        action(ReviewMoreAction.PauseAudio)
        action(ReviewMoreAction.AudioBackFive)
        action(ReviewMoreAction.AudioForwardFive)
        runOnIdle {
            assertEquals(listOf("card.mp3"), played)
            assertEquals(1, paused)
            assertEquals(listOf(-5_000L, 5_000L), seeks)
        }

        action(ReviewMoreAction.RecordOwnVoice)
        onNodeWithTag("voice-record-start").performClick()
        waitUntil { recorder.isRecording }
        onNodeWithTag("voice-record-stop").performClick()
        waitUntil { !recorder.isRecording }
        onNodeWithText("Close").performClick()
        action(ReviewMoreAction.ReplayOwnVoice)
        runOnIdle { assertEquals(listOf("card.mp3", "own-voice.wav"), played) }
    }

    @Test
    fun autoAdvanceRevealsThenRatesGoodAndCanBeDisabled() = runComposeUiTest {
        val rated = AtomicReference<Rating?>(null)
        mainClock.autoAdvance = false
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary(
                        id = "Auto",
                        name = "Auto",
                        cards = listOf(
                            ReviewCard(1, "auto question", "auto answer", noteGuid = "note-1"),
                            ReviewCard(2, "second question", "second answer", noteGuid = "note-2"),
                        ),
                        newCount = 2,
                        learningCount = 0,
                        dueCount = 0,
                    ),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    onCardReviewed = { cardId, rating, reviewedAt ->
                        rated.set(rating)
                        LocalCardSchedule(
                            cardId = cardId,
                            phase = ReviewPhase.Review,
                            dueAtMillis = reviewedAt + MillisPerDay,
                            stability = 1.0,
                            difficulty = 5.0,
                            scheduledDays = 1,
                            repetitions = 1,
                            lapses = 0,
                            lastReviewAtMillis = reviewedAt,
                        )
                    },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }

        onNodeWithText("More").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("review-more-AutoAdvance").performClick()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        waitForIdle()

        mainClock.advanceTimeBy(4_000L)
        onNodeWithText("auto answer").assertIsDisplayed()
        mainClock.advanceTimeBy(5_000L)
        runOnIdle { assertEquals(Rating.Good, rated.get()) }
        onNodeWithText("second question").assertIsDisplayed()

        onNodeWithText("More").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithText("Disable Auto Advance").performClick()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        waitForIdle()
        mainClock.advanceTimeBy(3_001L)
        onNodeWithText("second question").assertIsDisplayed()
        onNodeWithText("second answer").assertDoesNotExist()
    }

    @Test
    fun answerWaitsForCommitThenUndoRestoresTheCard() = runComposeUiTest {
        val first = ReviewCard(1, "front one", "back one")
        val second = ReviewCard(2, "front two", "back two")
        val commitGate = CompletableDeferred<Unit>()
        val commitStarted = AtomicBoolean(false)
        val undoCalled = AtomicBoolean(false)
        var recordedRating: Rating? = null

        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary(
                        id = "Acceptance",
                        name = "Acceptance",
                        cards = listOf(first, second),
                        newCount = 2,
                        learningCount = 0,
                        dueCount = 0,
                    ),
                    syncing = false,
                    canUndo = true,
                    onSync = {},
                    onCardReviewed = { cardId, rating, _ ->
                        assertEquals(1L, cardId)
                        recordedRating = rating
                        commitStarted.set(true)
                        commitGate.await()
                        committedSchedule(cardId)
                    },
                    onUndo = {
                        undoCalled.set(true)
                        first
                    },
                    onBack = {},
                )
            }
        }

        onNodeWithText("front one").assertIsDisplayed()
        onNodeWithText("Show Answer").performClick()
        onNodeWithText("back one").assertIsDisplayed()
        onNodeWithText("Good").performClick()
        waitUntil(timeoutMillis = 5_000) { commitStarted.get() }
        onNodeWithText("Working…").assertIsDisplayed()
        onNodeWithText("front one").assertIsDisplayed()

        commitGate.complete(Unit)
        waitUntil(timeoutMillis = 5_000) {
            runCatching { onNodeWithText("front two").assertIsDisplayed() }.isSuccess
        }
        assertEquals(Rating.Good, recordedRating)

        onNodeWithText("Undo").performClick()
        onNodeWithText("Undo last review?").assertIsDisplayed()
        assertEquals(false, undoCalled.get())
        onNodeWithText("Undo review").performClick()
        waitUntil(timeoutMillis = 5_000) { undoCalled.get() }
        onNodeWithText("front one").assertIsDisplayed()
        onNodeWithText("back one").assertIsDisplayed()
    }

    @Test
    fun editActionOpensInlineEditorAndSaveClosesIt() = runComposeUiTest {
        val saved = AtomicReference<BrowseNoteEdit?>(null)
        val collection = SyncedCollection(
            notes = mapOf(
                "n1" to SyncNote("n1", NotetypeCatalog.BasicId, listOf("bonjour", "hello"), listOf("french")),
            ),
            cards = mapOf(1L to SyncCard(1L, "n1", "French")),
            notetypes = NotetypeCatalog.definitions,
            deckNames = setOf("French"),
        )
        val card = ReviewCard(1, "bonjour", "hello", noteGuid = "n1")
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary("French", "French", listOf(card), 0, 0, 1),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    noteEditTarget = { cardId -> collection.noteEditTarget(cardId) },
                    onSaveNoteEdit = { edit ->
                        saved.set(edit)
                        null
                    },
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }
        waitForIdle()

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-EditNote").performClick()

        onNodeWithTag("review-note-editor").assertIsDisplayed()
        onNodeWithText("EDIT NOTE").assertIsDisplayed()
        onNodeWithText("Show Answer").assertDoesNotExist()
        onNodeWithTag("browse-edit-field-0").assertTextContains("bonjour")
        onNodeWithTag("browse-edit-field-1").performTextReplacement("hi")
        onNodeWithTag("browse-edit-tags").performTextReplacement("french, greetings")
        onNodeWithTag("browse-edit-save").performClick()
        waitUntil(timeoutMillis = 5_000) { saved.get() != null }

        assertEquals("n1", saved.get()?.noteGuid)
        assertEquals(listOf("bonjour", "hi"), saved.get()?.fields)
        assertEquals(listOf("french", "greetings"), saved.get()?.tags)
        onNodeWithTag("review-note-editor").assertDoesNotExist()
        onNodeWithText("Show Answer").assertIsDisplayed()
    }

    @Test
    fun editShortcutOpensEditorAndMenuKeysStayQuietWhileEditing() = runComposeUiTest {
        val optionsOpened = AtomicBoolean(false)
        val saved = AtomicReference<BrowseNoteEdit?>(null)
        val collection = SyncedCollection(
            notes = mapOf(
                "n1" to SyncNote("n1", NotetypeCatalog.BasicId, listOf("bonjour", "hello"), emptyList()),
            ),
            cards = mapOf(1L to SyncCard(1L, "n1", "French")),
            notetypes = NotetypeCatalog.definitions,
            deckNames = setOf("French"),
        )
        val card = ReviewCard(1, "bonjour", "hello", noteGuid = "n1")
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary("French", "French", listOf(card), 0, 0, 1),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    onOptions = { optionsOpened.set(true) },
                    noteEditTarget = { cardId -> collection.noteEditTarget(cardId) },
                    onSaveNoteEdit = { edit ->
                        saved.set(edit)
                        null
                    },
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }
        waitForIdle()

        onRoot().performKeyInput { keyDown(Key.E); keyUp(Key.E) }
        onNodeWithTag("review-note-editor").assertIsDisplayed()

        // Plain-letter menu shortcuts must not fire or queue while the editor is open.
        onRoot().performKeyInput { keyDown(Key.O); keyUp(Key.O) }
        onNodeWithText("Cancel").performClick()
        waitForIdle()

        assertFalse(optionsOpened.get())
        assertEquals(null, saved.get())
        onNodeWithTag("review-note-editor").assertDoesNotExist()
        onNodeWithText("Show Answer").assertIsDisplayed()
    }

    @Test
    fun editActionWithoutAvailableNoteShowsError() = runComposeUiTest {
        val card = ReviewCard(1, "front", "back", noteGuid = "missing")
        setContent {
            KelmaTheme {
                ReviewScreen(
                    deck = DeckSummary("Deck", "Deck", listOf(card), 0, 0, 1),
                    syncing = false,
                    canUndo = false,
                    onSync = {},
                    noteEditTarget = { null },
                    onCardReviewed = { _, _, _ -> null },
                    onUndo = { null },
                    onBack = {},
                )
            }
        }
        waitForIdle()

        onNodeWithText("More").performClick()
        onNodeWithTag("review-more-EditNote").performClick()

        onNodeWithText("This card's note is not available.").assertIsDisplayed()
        onNodeWithTag("review-note-editor").assertDoesNotExist()
    }
}

private fun committedSchedule(
    cardId: Long,
    phase: ReviewPhase = ReviewPhase.Review,
    dueAtMillis: Long = Long.MAX_VALUE,
    step: Int? = null,
    scheduledDays: Int = if (phase == ReviewPhase.Review) 1 else 0,
): LocalCardSchedule = LocalCardSchedule(
    cardId = cardId,
    phase = phase,
    dueAtMillis = dueAtMillis,
    stability = 1.0,
    difficulty = 5.0,
    scheduledDays = scheduledDays,
    repetitions = 1,
    lapses = 0,
    lastReviewAtMillis = currentEpochMillis(),
    step = step,
)
