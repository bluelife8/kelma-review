package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardCreationTimeTest {
    @Test
    fun explicitCreationTimeWinsAndPlausibleAnkiIdsBackfillLegacyCards() {
        val now = 1_800_000_000_000L
        val explicit = SyncCard(
            cardId = -1,
            noteGuid = "explicit",
            deckName = "Deck",
            createdAt = "2020-01-02T03:04:05.000Z",
        )
        val ankiId = 1_704_067_200_123L

        assertEquals(1_577_934_245_000L, explicit.createdAtMillis(now))
        assertEquals(ankiId, SyncCard(ankiId, "anki", "Deck").createdAtMillis(now))
        assertNull(SyncCard(-2, "unknown", "Deck").createdAtMillis(now))
        assertNull(SyncCard(42, "arbitrary", "Deck").createdAtMillis(now))
    }
}
