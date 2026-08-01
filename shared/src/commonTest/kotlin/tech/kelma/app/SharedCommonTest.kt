package tech.kelma.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedCommonTest {
    private val cards = listOf(
        ReviewCard(id = 1, front = "猫", back = "cat"),
        ReviewCard(id = 2, front = "犬", back = "dog"),
    )

    @Test
    fun revealingAnswerPreservesCurrentCard() {
        val session = ReviewSession(cards).revealAnswer()

        assertTrue(session.showingAnswer)
        assertEquals(cards.first(), session.currentCard)
    }

    @Test
    fun buryingCardOrNoteAdvancesWithoutRecordingReview() {
        val noteCards = listOf(
            ReviewCard(1, "one", "one", noteGuid = "note-a"),
            ReviewCard(2, "two", "two", noteGuid = "note-a"),
            ReviewCard(3, "three", "three", noteGuid = "note-b"),
        )

        val afterCard = ReviewSession(noteCards).buryCurrentCard()
        val afterNote = ReviewSession(noteCards).buryCurrentNote()

        assertEquals(2L, afterCard.currentCard?.id)
        assertEquals(3L, afterNote.currentCard?.id)
        assertEquals(listOf(3L), afterNote.cards.map(ReviewCard::id))
        assertNull(afterCard.lastRating)
        assertNull(afterNote.lastRating)
    }

    @Test
    fun answeringAdvancesQueueAndRecordsRating() {
        val session = ReviewSession(cards)
            .revealAnswer()
            .answer(Rating.Good)

        assertFalse(session.showingAnswer)
        assertEquals(cards[1], session.currentCard)
        assertEquals(Rating.Good, session.lastRating)
    }

    @Test
    fun sessionCompletesAfterEveryCardIsAnswered() {
        val completed = cards.fold(ReviewSession(cards)) { session, _ ->
            session.revealAnswer().answer(Rating.Good)
        }

        assertTrue(completed.isComplete)
        assertEquals(null, completed.currentCard)
    }

    @Test
    fun rendererRefreshReplacesQueuedContentWithoutResettingReviewState() {
        val answered = ReviewSession(cards).revealAnswer().answer(Rating.Good)
        val refreshed = answered.refreshCards(
            listOf(cards[0], cards[1].copy(frontHtml = "<strong>rendered</strong>")),
        )

        assertEquals(1, refreshed.currentIndex)
        assertFalse(refreshed.showingAnswer)
        assertEquals("<strong>rendered</strong>", refreshed.currentCard?.frontHtml)
    }

    @Test
    fun spaceRevealsThenRatesGood() {
        val revealed = ReviewSession(cards).applyShortcut(ReviewShortcut.Space)
        val answered = revealed.applyShortcut(ReviewShortcut.Space)

        assertTrue(revealed.showingAnswer)
        assertEquals(Rating.Good, answered.lastRating)
        assertEquals(1, answered.currentIndex)
    }

    @Test
    fun numberShortcutsMapToRatingButtons() {
        val shortcuts = listOf(
            ReviewShortcut.One to Rating.Again,
            ReviewShortcut.Two to Rating.Hard,
            ReviewShortcut.Three to Rating.Good,
            ReviewShortcut.Four to Rating.Easy,
        )

        shortcuts.forEach { (shortcut, expectedRating) ->
            val answered = ReviewSession(cards)
                .revealAnswer()
                .applyShortcut(shortcut)
            assertEquals(expectedRating, answered.lastRating)
        }
    }

    @Test
    fun syncDeltaCreatesBrowsableDecks() {
        val report = SyncedCollection().apply(
            manifest = SyncManifest(
                notes = listOf(ManifestEntry(guid = "note-1")),
                cards = listOf(ManifestEntry(cardId = 42)),
                decks = listOf(ManifestEntry(name = "Japanese")),
                serverTime = "2026-07-25T12:00:00Z",
            ),
            pulled = BatchPullResponse(
                notes = listOf(SyncNote(guid = "note-1", fields = listOf("猫", "cat"))),
                cards = listOf(
                    SyncCard(cardId = 42, noteGuid = "note-1", deckName = "Japanese"),
                ),
                decks = listOf(SyncDeck("Japanese")),
            ),
        )

        val deck = report.collection.asDecks().single()
        assertEquals("Japanese", deck.name)
        assertEquals("猫", deck.cards.single().front)
        assertEquals("cat", deck.cards.single().back)
        assertEquals(3, report.downloaded)
    }

    @Test
    fun localScheduleHidesCardsUntilTheyAreDue() {
        val now = 1_000_000L
        val collection = SyncedCollection(
            notes = mapOf(
                "note-1" to SyncNote(guid = "note-1", fields = listOf("front", "back")),
            ),
            cards = mapOf(42L to SyncCard(42, "note-1", "Deck")),
            deckNames = setOf("Deck"),
            serverTime = "2026-07-25T12:00:00Z",
        )
        val future = LocalCardSchedule(
            cardId = 42,
            phase = ReviewPhase.Review,
            dueAtMillis = now + MillisPerDay,
            stability = 1.0,
            difficulty = 5.0,
            scheduledDays = 1,
            repetitions = 1,
            lapses = 0,
            lastReviewAtMillis = now,
        )

        assertTrue(collection.asDecks(mapOf(42L to future), now).single().cards.isEmpty())
        assertEquals(1, collection.asDecks(emptyMap(), now).single().cards.size)
        assertEquals(1, collection.asDecks(mapOf(42L to future), now + MillisPerDay).single().cards.size)
    }

    @Test
    fun buriedCardsAreExcludedBeforeTheDeckQueueIsBuilt() {
        val collection = SyncedCollection(
            notes = mapOf("note-1" to SyncNote("note-1", fields = listOf("front", "back"))),
            cards = mapOf(42L to SyncCard(42, "note-1", "Deck")),
            deckNames = setOf("Deck"),
        )

        val deck = collection.asDecks(buriedCardIds = setOf(42L)).single()

        assertTrue(deck.cards.isEmpty())
        assertEquals(0, deck.newCount)
        assertEquals(0, deck.learningCount)
        assertEquals(0, deck.dueCount)
    }

    @Test
    fun downloadedSchedulingDoesNotControlTheLocalQueue() {
        val reviewedAt = 1_800_000_000_000L
        val scheduling = Json.parseToJsonElement(
            """{"queue":2,"ivl":30,"reps":4}""",
        ).jsonObject
        val collection = SyncedCollection(
            notes = mapOf("note-1" to SyncNote("note-1", fields = listOf("front", "back"))),
            cards = mapOf(42L to SyncCard(42, "note-1", "Deck", scheduling = scheduling)),
            reviews = mapOf(
                reviewedAt to SyncReview(reviewedAt, noteGuid = "note-1", cardOrd = 0),
            ),
            deckNames = setOf("Deck"),
        )

        assertEquals(1, collection.asDecks(nowMillis = reviewedAt).single().cards.size)
        assertEquals(1, collection.asDecks(nowMillis = reviewedAt + 30 * MillisPerDay).single().cards.size)
        val browseRow = collection.browseRows(emptyMap()).single()
        assertEquals(BrowseCardState.New, browseRow.state)
        assertNull(browseRow.dueMillis)
    }

    @Test
    fun deckCountsOnlyCardsThatAreCurrentlyDue() {
        val now = 1_000_000L
        val notes = (1L..4L).associate { id ->
            "note-$id" to SyncNote("note-$id", fields = listOf("front $id", "back $id"))
        }
        val collection = SyncedCollection(
            notes = notes,
            cards = mapOf(
                1L to SyncCard(1, "note-1", "Deck"),
                2L to SyncCard(2, "note-2", "Deck"),
                3L to SyncCard(3, "note-3", "Deck"),
                4L to SyncCard(
                    4,
                    "note-4",
                    "Deck",
                    scheduling = Json.parseToJsonElement("""{"queue":-1}""").jsonObject,
                ),
            ),
            deckNames = setOf("Deck"),
        )
        val schedules = mapOf(
            2L to LocalCardSchedule(2, ReviewPhase.Learning, now, 1.0, 5.0, 0, 1, 0, now),
            3L to LocalCardSchedule(3, ReviewPhase.Review, now + MillisPerDay, 1.0, 5.0, 1, 1, 0, now),
        )

        val deck = collection.asDecks(schedules, now).single()
        assertEquals(3, deck.cards.size)
        assertEquals(2, deck.newCount)
        assertEquals(1, deck.learningCount)
        assertEquals(0, deck.dueCount)
    }

    @Test
    fun fsrsSchedulesLearningAndGraduatedIntervals() {
        val card = SyncCard(42, "note-1", "Deck")
        val now = 1_000_000L

        val again = FsrsScheduler.review(card, null, Rating.Again, now)
        val good = FsrsScheduler.review(card, null, Rating.Good, now)
        val easy = FsrsScheduler.review(card, null, Rating.Easy, now)

        assertEquals(ReviewPhase.Learning, again.phase)
        assertEquals(now + 60_000L, again.dueAtMillis)
        assertEquals(0.212, again.stability, 0.0000001)
        assertEquals(0, again.lapses)
        assertEquals(ReviewPhase.Learning, good.phase)
        assertEquals(2.3065, good.stability, 0.0000001)
        assertEquals(now + 10 * 60_000L, good.dueAtMillis)
        assertEquals(now + 8 * MillisPerDay, easy.dueAtMillis)
        assertTrue(easy.stability > good.stability)

        val secondStep = FsrsScheduler.review(card, again, Rating.Good, again.dueAtMillis)
        assertEquals(ReviewPhase.Learning, secondStep.phase)
        assertEquals(1, secondStep.step)
        val graduated = FsrsScheduler.review(card, secondStep, Rating.Good, secondStep.dueAtMillis)
        assertEquals(ReviewPhase.Review, graduated.phase)
        assertTrue(graduated.stability >= secondStep.stability)
    }

    @Test
    fun failedReviewEntersRelearningAndIncrementsLapses() {
        val card = SyncCard(42, "note-1", "Deck")
        val learning = FsrsScheduler.review(card, null, Rating.Good, 1_000_000L)
        val firstReview = FsrsScheduler.review(card, learning, Rating.Good, learning.dueAtMillis)
        val failed = FsrsScheduler.review(card, firstReview, Rating.Again, firstReview.dueAtMillis)

        assertEquals(ReviewPhase.Relearning, failed.phase)
        assertEquals(firstReview.dueAtMillis + 10 * 60_000L, failed.dueAtMillis)
        assertEquals(1, failed.lapses)
        assertEquals(3, failed.repetitions)
    }

    @Test
    fun undoRestoresThePreviousSessionCard() {
        val answered = ReviewSession(cards).revealAnswer().answer(Rating.Good)
        val restored = answered.restoreLastAnswer(cards.first())

        assertEquals(0, restored.currentIndex)
        assertEquals(cards.first(), restored.currentCard)
        assertTrue(restored.showingAnswer)
    }

    @Test
    fun cardFacesResolveDownloadedAudioAndImages() {
        val collection = SyncedCollection(
            notes = mapOf(
                "note-1" to SyncNote(
                    guid = "note-1",
                    fields = listOf(
                        "<b>to run</b><br>[sound:run.mp3]<img src=\"run.jpg\">",
                        "laufen",
                    ),
                ),
            ),
            cards = mapOf(42L to SyncCard(42, "note-1", "German")),
            media = mapOf(
                "run.mp3" to SyncMediaFile("run.mp3", "", byteArrayOf(1)),
                "run.jpg" to SyncMediaFile("run.jpg", "", byteArrayOf(2)),
            ),
            deckNames = setOf("German"),
        )

        val card = collection.asDecks().single().cards.single()
        assertEquals("to run", card.front)
        assertFalse(card.front.contains("[sound:"))
        assertEquals("run.mp3", card.frontAudio.single().filename)
        assertEquals("run.jpg", card.frontImages.single().filename)
        assertTrue(buildInlineCardText(card.frontBlocks).text.startsWith("to run "))
    }

    @Test
    fun diskBackedCardMediaHydratesOnlyReferencedFiles() {
        val collection = SyncedCollection(
            notes = mapOf(
                "note-ref" to SyncNote(
                    guid = "note-ref",
                    fields = listOf("word [sound:word.mp3]<img src=\"word.jpg\">", "answer"),
                ),
            ),
            cards = mapOf(8L to SyncCard(8L, "note-ref", "Refs")),
            media = mapOf(
                "word.mp3" to SyncMediaFile("word.mp3", "v1", byteArrayOf(), 2),
                "word.jpg" to SyncMediaFile("word.jpg", "v1", byteArrayOf(), 3),
                "unused.jpg" to SyncMediaFile("unused.jpg", "v1", byteArrayOf(), 4),
            ),
            deckNames = setOf("Refs"),
        )
        val requested = mutableListOf<String>()

        val hydrated = collection.asDecks().single().cards.single().hydrateMedia { filename ->
            requested += filename
            when (filename) {
                "word.mp3" -> byteArrayOf(1, 2)
                "word.jpg" -> byteArrayOf(3, 4, 5)
                else -> null
            }
        }

        assertEquals(setOf("word.mp3", "word.jpg"), requested.toSet())
        assertFalse("unused.jpg" in requested)
        assertContentEquals(byteArrayOf(1, 2), hydrated.frontAudio.single().bytes)
        assertContentEquals(byteArrayOf(3, 4, 5), hydrated.frontImages.single().bytes)
        assertContentEquals(
            byteArrayOf(3, 4, 5),
            hydrated.frontBlocks.filterIsInstance<CardImageBlock>().single().media?.bytes,
        )
    }

    @Test
    fun audioStaysWithPrecedingTextWhileFollowingLineBreakSurvivesTokenization() {
        val collection = SyncedCollection(
            notes = mapOf(
                "note-lines" to SyncNote(
                    guid = "note-lines",
                    fields = listOf("line one\n[sound:clip.mp3]\nline two", "answer"),
                ),
            ),
            cards = mapOf(7L to SyncCard(7L, "note-lines", "Lines")),
            media = mapOf("clip.mp3" to SyncMediaFile("clip.mp3", "", byteArrayOf(1))),
            deckNames = setOf("Lines"),
        )

        val card = collection.asDecks().single().cards.single()
        val textBlocks = card.frontBlocks.filterIsInstance<CardTextBlock>()
        assertEquals("line one\nline two", card.front)
        assertTrue(textBlocks.first().trailingLineBreak)
        assertTrue(textBlocks.last().leadingLineBreak)
        val inlineText = buildInlineCardText(card.frontBlocks).text
        assertTrue(inlineText.startsWith("line one "))
        assertTrue(inlineText.endsWith("\nline two"))
    }

    @Test
    fun notetypeTemplatesControlCardSidesAndMediaOrder() {
        val definition = Json.parseToJsonElement(
            """{
              "flds":[{"name":"Front","ord":0},{"name":"Back","ord":1}],
              "tmpls":[{
                "name":"Reverse","ord":0,
                "qfmt":"{{Back}}",
                "afmt":"{{FrontSide}}<hr id=answer>{{Front}}"
              }]
            }""",
        ).jsonObject
        val collection = SyncedCollection(
            notes = mapOf(
                "note-1" to SyncNote(
                    guid = "note-1",
                    notetypeId = 10,
                    fields = listOf("cat [sound:cat.mp3]", "<img src=\"cat.jpg\">gato"),
                ),
            ),
            cards = mapOf(42L to SyncCard(42, "note-1", "Spanish", ord = 0)),
            notetypes = mapOf(10L to SyncNotetype(10, "Reverse", definition)),
            media = mapOf(
                "cat.mp3" to SyncMediaFile("cat.mp3", "", byteArrayOf(1)),
                "cat.jpg" to SyncMediaFile("cat.jpg", "", byteArrayOf(2)),
            ),
            deckNames = setOf("Spanish"),
        )

        val card = collection.asDecks().single().cards.single()
        assertEquals("gato", card.front)
        assertEquals("cat", card.back)
        assertTrue(card.frontBlocks.first() is CardImageBlock)
        assertTrue(card.frontBlocks.last() is CardTextBlock)
        assertTrue(card.backBlocks.last() is CardAudioBlock)
        assertEquals("cat.mp3", card.backAudio.single().filename)
    }

    @Test
    fun nestedAndInvertedTemplateSectionsRenderPortably() {
        val definition = Json.parseToJsonElement(
            """{
              "flds":[{"name":"Front","ord":0},{"name":"Back","ord":1}],
              "tmpls":[{
                "name":"Conditional","ord":0,
                "qfmt":"{{#Front}}shown {{#Back}}{{Back}}{{/Back}}{{/Front}}{{^Front}}missing{{/Front}}",
                "afmt":"{{FrontSide}}"
              }]
            }""",
        ).jsonObject
        val collection = SyncedCollection(
            notes = mapOf("note-1" to SyncNote("note-1", 10, listOf("yes", "answer"))),
            cards = mapOf(42L to SyncCard(42, "note-1", "Conditional")),
            notetypes = mapOf(10L to SyncNotetype(10, "Conditional", definition)),
            deckNames = setOf("Conditional"),
        )

        assertEquals("shown answer", collection.asDecks().single().cards.single().front)
    }

    @Test
    fun syncUploadTimestampIsUtcRfc3339() {
        assertEquals("1970-01-01T00:00:00.000Z", epochMillisToRfc3339(0))
        assertEquals("2027-01-15T08:00:00.123Z", epochMillisToRfc3339(1_800_000_000_123L))
        assertEquals(0L, rfc3339ToEpochMillis("1970-01-01T00:00:00Z"))
        assertEquals(1_800_000_000_123L, rfc3339ToEpochMillis("2027-01-15T08:00:00.123456Z"))
    }

    @Test
    fun deckExportIncludesOnlyTheSelectedTreeWithHistoryAndReferencedMedia() {
        val collection = SyncedCollection(
            notes = mapOf(
                "one" to SyncNote("one", fields = listOf("<img src=\"cat.jpg\">", "cat")),
                "two" to SyncNote("two", fields = listOf("other", "note")),
            ),
            cards = mapOf(
                1L to SyncCard(1, "one", "Languages::German"),
                2L to SyncCard(2, "two", "Other"),
            ),
            notetypes = mapOf(0L to SyncNotetype(0, "Basic")),
            media = mapOf(
                "cat.jpg" to SyncMediaFile("cat.jpg", "now", byteArrayOf(0, 1, 2)),
                "unused.jpg" to SyncMediaFile("unused.jpg", "now", byteArrayOf(3)),
            ),
            deckNames = setOf("Languages", "Languages::German", "Other"),
        )
        val schedule = LocalCardSchedule(1, ReviewPhase.Review, 10, 2.0, 5.0, 2, 1, 0, 1)

        val file = collection.exportDeck(
            "Languages",
            optionsByDeck = mapOf(
                "Languages" to DeckOptions(newCardsPerDay = 7),
                "Languages::German" to DeckOptions(newCardsPerDay = 3),
                "Other" to DeckOptions(newCardsPerDay = 1),
            ),
            schedules = mapOf(1L to schedule),
            exportedAtMillis = 99,
            localReviews = listOf(ImmutableReviewExport(90, "one", 0, Rating.Good, 500)),
        )
        val export = Json.decodeFromString<KelmaJsonExport>(file.content)

        assertEquals("Languages.kelma.json", file.suggestedName)
        assertEquals(listOf(1L), export.cards.map(SyncCard::cardId))
        assertEquals(listOf("one"), export.notes.map(SyncNote::guid))
        assertEquals(2, export.version)
        assertEquals(listOf(90L), export.reviews.map(KelmaJsonReview::reviewId))
        assertFalse("\"schedules\"" in file.content)
        assertEquals(setOf("Languages", "Languages::German"), export.deckOptions.keys)
        assertEquals(7, export.deckOptions.getValue("Languages").newCardsPerDay)
        assertEquals(3, export.deckOptions.getValue("Languages::German").newCardsPerDay)
        assertEquals(listOf("cat.jpg"), export.media.map(KelmaExportMedia::filename))
        assertEquals("AAEC", export.media.single().base64)
    }

    @Test
    fun downloadedDeckOverridesRenameTreesAndHideDeletedTrees() {
        val collection = SyncedCollection(
            cards = mapOf(
                1L to SyncCard(1, "one", "Languages::German"),
                2L to SyncCard(2, "two", "Archived"),
            ),
            deckNames = setOf("Languages", "Languages::German", "Archived"),
        )
        val displayed = collection.withDeckOverrides(
            mapOf("Languages" to "Study", "Archived" to null),
        )

        assertEquals(setOf("Study", "Study::German"), displayed.deckNames)
        assertEquals("Study::German", displayed.cards.getValue(1).deckName)
        assertEquals(null, displayed.cards[2])
    }

    @Test
    fun clozeTemplateRendersQuestionAndAnswerForCardOrdinal() {
        val definition = Json.parseToJsonElement(
            """{
              "flds":[{"name":"Text","ord":0}],
              "tmpls":[{"name":"Cloze","ord":0,"qfmt":"{{cloze:Text}}","afmt":"{{cloze:Text}}"}]
            }""",
        ).jsonObject
        val collection = SyncedCollection(
            notes = mapOf(
                "note-1" to SyncNote(
                    guid = "note-1",
                    notetypeId = 10,
                    fields = listOf("A {{c1::cat::animal}} sleeps."),
                ),
            ),
            cards = mapOf(42L to SyncCard(42, "note-1", "Cloze")),
            notetypes = mapOf(10L to SyncNotetype(10, "Cloze", definition)),
            deckNames = setOf("Cloze"),
        )

        val card = collection.asDecks().single().cards.single()
        assertEquals("A [animal] sleeps.", card.front)
        assertEquals("A cat sleeps.", card.back)
    }

    @Test
    fun fullPullStoresHistoryCountersNotetypesAndMedia() {
        val studyDay = SyncStudyDay(day = 20_000, deckName = "Japanese", reviewStudied = 2)
        val report = SyncedCollection().apply(
            manifest = SyncManifest(
                reviews = listOf(ManifestEntry(reviewId = 9001)),
                studyDays = listOf(studyDay),
                notetypes = listOf(ManifestEntry(notetypeId = 1001)),
                media = listOf(ManifestEntry(filename = "cat.jpg")),
                serverTime = "2026-07-25T12:00:00Z",
            ),
            pulled = BatchPullResponse(
                reviews = listOf(SyncReview(reviewId = 9001, noteGuid = "note-1")),
                notetypes = listOf(SyncNotetype(notetypeId = 1001, name = "Basic")),
            ),
            downloadedMedia = mapOf(
                "cat.jpg" to SyncMediaFile("cat.jpg", "2026-07-25T11:00:00Z", byteArrayOf(1, 2, 3)),
            ),
        )

        assertEquals(1, report.collection.reviews.size)
        assertEquals(studyDay, report.collection.studyDays.values.single())
        assertEquals("Basic", report.collection.notetypes.getValue(1001).name)
        assertContentEquals(byteArrayOf(1, 2, 3), report.collection.media.getValue("cat.jpg").bytes)
        assertEquals(4, report.downloaded)
    }

    @Test
    fun pendingDeckChangesAggregateDescendantsAndDeduplicateCards() {
        val content = mapOf(
            "Languages" to PendingDeckChanges(addedCardIds = setOf(1)),
            "Languages::French" to PendingDeckChanges(changedCardIds = setOf(2)),
        )
        val reviews = mapOf(
            "Languages::French" to PendingDeckChanges(changedCardIds = setOf(2, 3)),
        )

        val pending = aggregatePendingDeckChanges(content, reviews)

        assertEquals(1, pending.getValue("Languages").added)
        assertEquals(2, pending.getValue("Languages").changed)
        assertEquals(0, pending.getValue("Languages::French").added)
        assertEquals(2, pending.getValue("Languages::French").changed)
    }

    @Test
    fun cardTombstoneRemovesDownloadedCard() {
        val collection = SyncedCollection(
            notes = mapOf(
                "note-1" to SyncNote(guid = "note-1", fields = listOf("front", "back")),
            ),
            cards = mapOf(42L to SyncCard(42, "note-1", "Deck")),
            deckNames = setOf("Deck"),
        )
        val report = collection.apply(
            manifest = SyncManifest(
                tombstones = listOf(SyncTombstone("card", "42")),
                serverTime = "2026-07-25T12:01:00Z",
            ),
            pulled = BatchPullResponse(),
        )

        assertTrue(report.collection.cards.isEmpty())
        assertEquals(1, report.removed)
    }
}
