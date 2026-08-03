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

internal data class SyncedDailyLimits(
    val newCardsPerDay: Int?,
    val maximumReviewsPerDay: Int?,
)

internal fun JsonObject?.syncedDailyLimits(): SyncedDailyLimits = SyncedDailyLimits(
    newCardsPerDay = this?.validDailyLimit("newLimit"),
    maximumReviewsPerDay = this?.validDailyLimit("reviewLimit"),
)

internal fun DeckOptions.withSyncedAnkiDailyLimits(config: JsonObject?): DeckOptions {
    val limits = config.syncedDailyLimits()
    if (limits.newCardsPerDay == null && limits.maximumReviewsPerDay == null) return this
    return copy(
        newCardsPerDay = limits.newCardsPerDay ?: newCardsPerDay,
        maximumReviewsPerDay = limits.maximumReviewsPerDay ?: maximumReviewsPerDay,
    )
}

private fun JsonObject.validDailyLimit(name: String): Int? =
    (get(name) as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..9_999 }
