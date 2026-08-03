package tech.kelma.app

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncedDeckOptionsTest {
    @Test
    fun syncedAnkiLimitsOverrideOnlyTheFallbackDailyLimits() {
        val fallback = DeckOptions(
            newCardsPerDay = 30,
            maximumReviewsPerDay = 300,
            desiredRetention = 0.95,
        )
        val collection = collectionWithLimits(newLimit = 16, reviewLimit = 84)

        val options = collection.effectiveDeckOptions("Deck", emptyMap(), fallback)

        assertEquals(16, options.newCardsPerDay)
        assertEquals(84, options.maximumReviewsPerDay)
        assertEquals(0.95, options.desiredRetention)
    }

    @Test
    fun explicitLocalOptionsTakePriorityOverSyncedAnkiLimits() {
        val collection = collectionWithLimits(newLimit = 16, reviewLimit = 84)
        val local = DeckOptions(newCardsPerDay = 5, maximumReviewsPerDay = 7)

        val options = collection.effectiveDeckOptions("Deck", mapOf("Deck" to local))

        assertEquals(local, options)
    }

    @Test
    fun missingOrInvalidSyncedLimitsPreserveFallbackValues() {
        val fallback = DeckOptions(newCardsPerDay = 23, maximumReviewsPerDay = 321)
        val collection = SyncedCollection(
            deckRecords = mapOf(
                "Deck" to SyncDeck(
                    name = "Deck",
                    config = buildJsonObject {
                        put("newLimit", -1)
                        put("reviewLimit", 10_000)
                    },
                ),
            ),
        )

        assertEquals(fallback, collection.effectiveDeckOptions("Deck", emptyMap(), fallback))
    }

    @Test
    fun deckProjectionAppliesSyncedLimitsAndTodaysConsumption() {
        val now = 1_000_000L
        val notes = (1L..9L).associate { id ->
            "note-$id" to SyncNote("note-$id", fields = listOf("front $id", "back $id"))
        }
        val cards = (1L..9L).associateWith { id -> SyncCard(id, "note-$id", "Deck") }
        val schedules = (6L..9L).associateWith { id ->
            LocalCardSchedule(
                cardId = id,
                phase = ReviewPhase.Review,
                dueAtMillis = now,
                stability = 1.0,
                difficulty = 5.0,
                scheduledDays = 1,
                repetitions = 1,
                lapses = 0,
                lastReviewAtMillis = now - MillisPerDay,
            )
        }
        val collection = SyncedCollection(
            notes = notes,
            cards = cards,
            deckNames = setOf("Deck"),
            deckRecords = collectionWithLimits(newLimit = 2, reviewLimit = 4).deckRecords,
        )

        val deck = collection.asDecks(
            localSchedules = schedules,
            nowMillis = now,
            studiedTodayByDeck = mapOf("Deck" to DeckStudyCounts(newCards = 1, reviews = 2)),
        ).single()

        assertEquals(1, deck.newCount)
        assertEquals(0, deck.dueCount)
    }
}

private fun collectionWithLimits(newLimit: Int, reviewLimit: Int): SyncedCollection = SyncedCollection(
    deckRecords = mapOf(
        "Deck" to SyncDeck(
            name = "Deck",
            config = buildJsonObject {
                put("newLimit", newLimit)
                put("reviewLimit", reviewLimit)
            },
        ),
    ),
)
