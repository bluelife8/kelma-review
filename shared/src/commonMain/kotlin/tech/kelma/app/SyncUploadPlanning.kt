package tech.kelma.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PendingReviewEvent(
    val eventId: Long,
    val cardId: Long,
    val noteGuid: String,
    val cardOrd: Int,
    val deckName: String,
    val rating: Rating,
    val reviewedAtMillis: Long,
    val studyDay: Long,
    val durationMillis: Long,
    val before: LocalCardSchedule?,
    val after: LocalCardSchedule,
    val wasNew: Boolean,
    val reviewId: Long,
)

data class PendingNoteSyncRow(
    val guid: String,
    val operation: String,
    val baseChecksum: String,
    val modifiedAtMillis: Long,
    val forceOverride: Boolean,
)

data class PendingDeckSyncRow(
    val sourceName: String,
    val operation: String,
    val targetName: String?,
    val baseChecksum: String,
    val modifiedAtMillis: Long,
    val forceOverride: Boolean,
)

internal fun buildSyncUploadPlan(
    raw: SyncedCollection,
    local: LocalContentSnapshot,
    reviewEvents: List<PendingReviewEvent>,
    noteRows: List<PendingNoteSyncRow>,
    deckRows: List<PendingDeckSyncRow>,
): SyncUploadPlan {
    val displayed = raw.withLocalContent(local)
    val displayedCardsByNote = displayed.cards.values.groupBy(SyncCard::noteGuid)
    val rawCardsByNote = raw.cards.values.groupBy(SyncCard::noteGuid)
    val localCardsByNote = local.cards.values.groupBy(SyncCard::noteGuid)
    val uploadableReviewEvents = reviewEvents.filter { it.noteGuid in raw.notes }
    val reviews = uploadableReviewEvents.map { event ->
        val card = displayedCardsByNote[event.noteGuid]?.firstOrNull { it.ord == event.cardOrd }
            ?: displayed.cards[event.cardId]
        ReviewPushBody(
            reviewId = event.reviewId,
            sourceCardId = card?.cardId ?: event.cardId,
            noteGuid = event.noteGuid,
            cardOrd = event.cardOrd,
            deckName = card?.deckName ?: event.deckName,
            ease = event.rating.ordinal + 1,
            interval = event.after.scheduledDays,
            lastInterval = event.before?.scheduledDays ?: 0,
            factor = ((5.0 - event.after.difficulty) * 500 + 2_500).toInt().coerceIn(1_300, 3_500),
            takenMillis = event.durationMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            reviewKind = when {
                event.wasNew || event.before?.phase == ReviewPhase.Learning -> 0
                event.before?.phase == ReviewPhase.Relearning -> 2
                else -> 1
            },
        )
    }
    val notes = noteRows.mapNotNull { row ->
        if (row.operation == "delete") {
            val cardIds = rawCardsByNote[row.guid].orEmpty().map(SyncCard::cardId)
            return@mapNotNull PendingNoteUpload(
                guid = row.guid,
                operation = row.operation,
                body = null,
                notetype = null,
                deck = null,
                cards = emptyList(),
                deleteRequest = BatchDeleteRequest(notes = listOf(row.guid), cards = cardIds),
                forceOverride = row.forceOverride,
            )
        }
        val note = displayed.notes[row.guid] ?: return@mapNotNull null
        val modifiedAt = epochMillisToRfc3339(row.modifiedAtMillis)
        val localCards = localCardsByNote[row.guid].orEmpty()
        val notetype = displayed.notetypes[note.notetypeId]
        val notetypePush = notetype?.takeIf { note.notetypeId !in raw.notetypes }?.let {
            it.notetypeId to NotetypePushBody(it.name, it.definition, modifiedAt)
        }
        val deckName = localCards.firstOrNull()?.deckName
        val deckPush = deckName?.takeIf { name -> name !in raw.deckRecords }?.let { name ->
            name to DeckPushBody(deckConfig(displayed, name, local.deckOptions[name]), modifiedAt, "")
        }
        PendingNoteUpload(
            guid = row.guid,
            operation = row.operation,
            body = NotePushBody(note.notetypeId, note.fields, note.tags, modifiedAt, row.baseChecksum),
            notetype = notetypePush,
            deck = deckPush,
            cards = localCards.map { card ->
                card.cardId to CardPushBody(
                    card.noteGuid,
                    card.deckName,
                    card.ord,
                    JsonObject(emptyMap()),
                    modifiedAt,
                    createdAt = card.creationTimestamp(),
                )
            },
            forceOverride = row.forceOverride,
        )
    }
    val decks = deckRows.map { row ->
        buildDeckUpload(row, raw, displayed, rawCardsByNote, local.deckOptions)
    }
    return SyncUploadPlan(reviews = reviews, notes = notes, decks = decks)
}

private fun buildDeckUpload(
    row: PendingDeckSyncRow,
    raw: SyncedCollection,
    displayed: SyncedCollection,
    rawCardsByNote: Map<String, List<SyncCard>>,
    localOptions: Map<String, DeckOptions>,
): PendingDeckUpload {
    val modifiedAt = if (row.operation == "rename") {
        raw.serverTime?.takeIf(String::isNotBlank) ?: epochMillisToRfc3339(row.modifiedAtMillis)
    } else {
        epochMillisToRfc3339(row.modifiedAtMillis)
    }
    if (row.operation == "delete") {
        val cards = raw.cards.values.filter { it.deckName.isDeckOrDescendantOf(row.sourceName) }
        val cardIds = cards.mapTo(mutableSetOf(), SyncCard::cardId)
        val deletedNotes = cards.map(SyncCard::noteGuid).distinct().filter { guid ->
            rawCardsByNote[guid].orEmpty().all { it.cardId in cardIds }
        }
        return PendingDeckUpload(
            sourceName = row.sourceName,
            operation = row.operation,
            targetName = null,
            targetBody = null,
            cards = emptyList(),
            deleteRequest = BatchDeleteRequest(
                notes = deletedNotes,
                cards = cardIds.toList(),
                decks = raw.deckRecords.keys.filter { it.isDeckOrDescendantOf(row.sourceName) },
            ),
            forceOverride = row.forceOverride,
        )
    }
    val target = row.targetName ?: row.sourceName
    val movedCards = if (row.operation == "rename") {
        raw.cards.values.filter { it.deckName.isDeckOrDescendantOf(row.sourceName) }.map { card ->
            val displayedCard = displayed.cards[card.cardId] ?: card.copy(
                deckName = target + card.deckName.substring(row.sourceName.length),
            )
            card.cardId to CardPushBody(
                displayedCard.noteGuid,
                displayedCard.deckName,
                displayedCard.ord,
                displayedCard.scheduling,
                newestTimestamp(modifiedAt, card.clientModifiedAt),
                createdAt = card.creationTimestamp(),
            )
        }
    } else {
        emptyList()
    }
    val delete = if (row.operation == "rename") {
        BatchDeleteRequest(decks = raw.deckRecords.keys.filter { it.isDeckOrDescendantOf(row.sourceName) })
    } else {
        null
    }
    val additionalDecks = if (row.operation == "rename") {
        raw.deckRecords.values.filter {
            it.name != row.sourceName && it.name.isDeckOrDescendantOf(row.sourceName)
        }.map { deck ->
            val renamed = target + deck.name.substring(row.sourceName.length)
            renamed to DeckPushBody(
                deckConfigFrom(deck.config, localOptions[renamed]),
                modifiedAt,
                deck.checksum,
            )
        }
    } else {
        emptyList()
    }
    return PendingDeckUpload(
        sourceName = row.sourceName,
        operation = row.operation,
        targetName = target,
        targetBody = DeckPushBody(
            config = if (row.operation == "rename") {
                deckConfigFrom(raw.deckRecords[row.sourceName]?.config, localOptions[target])
            } else {
                deckConfig(displayed, target, localOptions[target])
            },
            clientModifiedAt = modifiedAt,
            baseChecksum = row.baseChecksum,
        ),
        additionalDecks = additionalDecks,
        cards = movedCards,
        deleteRequest = delete,
        forceOverride = row.forceOverride,
    )
}

private fun newestTimestamp(first: String, second: String): String {
    val firstMillis = rfc3339ToEpochMillis(first) ?: return second.ifBlank { first }
    val secondMillis = rfc3339ToEpochMillis(second) ?: return first
    return if (secondMillis > firstMillis) second else first
}

private fun deckConfig(
    collection: SyncedCollection,
    deckName: String,
    options: DeckOptions? = null,
): JsonObject = deckConfigFrom(collection.deckRecords[deckName]?.config, options)

private fun deckConfigFrom(source: JsonObject?, options: DeckOptions? = null): JsonObject = buildJsonObject {
    source?.forEach { (key, value) ->
        if (key != LegacySyncedDeckOptionsKey) put(key, value)
    }
    options?.let {
        put("newLimit", it.newCardsPerDay)
        put("reviewLimit", it.maximumReviewsPerDay)
    }
}

private const val LegacySyncedDeckOptionsKey = "kelma_options"

internal fun LocalCardSchedule.toSchedulingJson(): JsonObject = buildJsonObject {
    put("queue", when (phase) {
        ReviewPhase.Learning -> 1
        ReviewPhase.Review -> 2
        ReviewPhase.Relearning -> 3
    })
    put("due_at_ms", dueAtMillis)
    put("due", dueAtMillis / 1_000L)
    put("ivl", scheduledDays)
    put("reps", repetitions)
    put("lapses", lapses)
    put("stability", stability)
    put("difficulty", difficulty)
    put("factor", ((5.0 - difficulty) * 500 + 2_500).toInt().coerceIn(1_300, 3_500))
}

internal fun rfc3339ToEpochMillis(timestamp: String): Long? {
    if (timestamp.length < 20 || timestamp[4] != '-' || timestamp[7] != '-' ||
        timestamp[10] != 'T' || timestamp[13] != ':' || timestamp[16] != ':' || !timestamp.endsWith('Z')) {
        return null
    }
    val year = timestamp.substring(0, 4).toIntOrNull() ?: return null
    val month = timestamp.substring(5, 7).toIntOrNull() ?: return null
    val day = timestamp.substring(8, 10).toIntOrNull() ?: return null
    val hour = timestamp.substring(11, 13).toIntOrNull() ?: return null
    val minute = timestamp.substring(14, 16).toIntOrNull() ?: return null
    val second = timestamp.substring(17, 19).toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
    val adjustedYear = year - if (month <= 2) 1 else 0
    val era = (if (adjustedYear >= 0) adjustedYear else adjustedYear - 399) / 400
    val yearOfEra = adjustedYear - era * 400
    val adjustedMonth = month + if (month > 2) -3 else 9
    val dayOfYear = (153 * adjustedMonth + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    val epochDay = era * 146_097L + dayOfEra - 719_468L
    val fraction = timestamp.substring(19, timestamp.length - 1)
    val millis = if (fraction.startsWith('.')) {
        fraction.drop(1).take(3).padEnd(3, '0').toIntOrNull() ?: return null
    } else {
        0
    }
    return epochDay * MillisPerDay + hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
}

internal fun epochMillisToRfc3339(epochMillis: Long): String {
    val secondsOfDay = ((epochMillis / 1_000L) % 86_400L + 86_400L) % 86_400L
    val hours = secondsOfDay / 3_600L
    val minutes = secondsOfDay % 3_600L / 60L
    val seconds = secondsOfDay % 60L
    val millis = ((epochMillis % 1_000L) + 1_000L) % 1_000L
    return "${formatDueDate(epochMillis)}T${hours.twoDigits()}:${minutes.twoDigits()}:" +
        "${seconds.twoDigits()}.${millis.toString().padStart(3, '0')}Z"
}

private fun Long.twoDigits(): String = toString().padStart(2, '0')
