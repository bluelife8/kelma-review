package tech.kelma.app

private const val EarliestAnkiCardIdMillis = 946_684_800_000L
private const val MaximumCreationClockSkewMillis = 5L * 60L * 1_000L

/** Returns the original creation time carried explicitly or by a plausible Anki millisecond card ID. */
internal fun SyncCard.createdAtMillis(nowMillis: Long = currentEpochMillis()): Long? =
    createdAt?.let(::rfc3339ToEpochMillis)
        ?: inferredAnkiCardCreatedAtMillis(cardId, nowMillis)

internal fun SyncCard.creationTimestamp(nowMillis: Long = currentEpochMillis()): String? =
    createdAtMillis(nowMillis)?.let(::epochMillisToRfc3339)

internal fun inferredAnkiCardCreatedAtMillis(cardId: Long, nowMillis: Long): Long? {
    val latest = if (nowMillis > Long.MAX_VALUE - MaximumCreationClockSkewMillis) {
        Long.MAX_VALUE
    } else {
        nowMillis + MaximumCreationClockSkewMillis
    }
    return cardId.takeIf { it in EarliestAnkiCardIdMillis..latest }
}
