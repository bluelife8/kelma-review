package tech.kelma.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal fun SyncedCollection.effectiveDeckOptions(
    deckName: String,
    localOptions: Map<String, DeckOptions>,
    fallback: DeckOptions = DeckOptions(),
): DeckOptions = localOptions[deckName]
    ?: fallback.withSyncedAnkiDailyLimits(deckRecords[deckName]?.config)

internal fun DeckOptions.withSyncedAnkiDailyLimits(config: JsonObject?): DeckOptions {
    if (config == null) return this
    val newLimit = config.validDailyLimit("newLimit")
    val reviewLimit = config.validDailyLimit("reviewLimit")
    if (newLimit == null && reviewLimit == null) return this
    return copy(
        newCardsPerDay = newLimit ?: newCardsPerDay,
        maximumReviewsPerDay = reviewLimit ?: maximumReviewsPerDay,
    )
}

private fun JsonObject.validDailyLimit(name: String): Int? =
    (get(name) as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..9_999 }
