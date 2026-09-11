package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReviewCardInfoModelsTest {
    @Test
    fun cardInfoCombinesEffectiveScheduleFsrsNoteAndHistoryDetails() {
        val card = SyncCard(
            cardId = 42L,
            noteGuid = "note-42",
            deckName = "Languages::French",
            ord = 1,
            createdAt = epochMillisToRfc3339(500L),
        )
        val note = SyncNote(
            guid = card.noteGuid,
            notetypeId = 7L,
            tags = listOf("verbs", "marked", "A1"),
        )
        val schedule = LocalCardSchedule(
            cardId = card.cardId,
            phase = ReviewPhase.Review,
            dueAtMillis = 20L * MillisPerDay,
            stability = 12.345,
            difficulty = 6.7,
            scheduledDays = 12,
            repetitions = 8,
            lapses = 2,
            lastReviewAtMillis = 1_000L,
        )
        val history = listOf(
            ReviewHistoryInfo("old", 1_000L, Rating.Hard, "3d", 900, false),
            ReviewHistoryInfo("new", 2_000L, Rating.Good, "12d", 700, true),
        )

        val info = buildReviewCardInfo(
            cardId = card.cardId,
            collection = SyncedCollection(
                notes = mapOf(note.guid to note),
                cards = mapOf(card.cardId to card),
                notetypes = mapOf(7L to SyncNotetype(7L, "Basic (and reversed card)")),
            ),
            localReviews = LocalReviewSnapshot(
                schedules = mapOf(card.cardId to schedule),
                dueDateOverrides = mapOf(card.cardId to 25L * MillisPerDay),
            ),
            effectiveFlags = mapOf(card.cardId to ReviewFlag.Purple.value),
            options = DeckOptions(desiredRetention = 0.93),
            schedulerProfileVersion = 4L,
            cloudSchedulerProfileVersion = 3L,
            schedulerProfileStatus = "Pending upload",
            recentReviews = history,
        )

        assertNotNull(info)
        assertEquals("Review", info.state)
        assertEquals(25L * MillisPerDay, info.dueAtMillis)
        assertEquals(12.345, info.stability)
        assertEquals("FSRS-6", info.scheduler)
        assertEquals(4L, info.schedulerProfileVersion)
        assertEquals(ReviewFlag.Purple.value, info.flag)
        assertTrue(info.noteMarked)
        assertEquals(listOf("A1", "marked", "verbs"), info.tags)
        assertEquals(listOf("new", "old"), info.recentReviews.map(ReviewHistoryInfo::key))
    }

    @Test
    fun storedIntervalsSupportLearningSecondsAndLongReviewIntervals() {
        assertEquals("10m", formatStoredReviewInterval(-600))
        assertEquals("21d", formatStoredReviewInterval(21))
        assertEquals("2mo", formatStoredReviewInterval(60))
    }
}
