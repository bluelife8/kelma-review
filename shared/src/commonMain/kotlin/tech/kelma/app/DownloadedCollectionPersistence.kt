package tech.kelma.app

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import tech.kelma.db.KelmaQueries

internal fun loadDownloadedCollection(
    queries: KelmaQueries,
    json: Json,
    includeReviews: Boolean = true,
): SyncedCollection {
    val stringList = ListSerializer(String.serializer())
    val notes = queries.selectNotes {
            guid, notetypeId, fields, tags, checksum, modified, clientModified,
            markIntentId, markMarked, markModified, markClientModified ->
        guid to SyncNote(
            guid = guid,
            notetypeId = notetypeId,
            fields = json.decodeFromString(stringList, fields),
            tags = json.decodeFromString(stringList, tags),
            checksum = checksum,
            modifiedAt = modified,
            clientModifiedAt = clientModified,
            mark = markIntentId.takeIf(String::isNotBlank)?.let {
                SyncNoteMark(markMarked == 1L, it, markModified, markClientModified)
            },
        )
    }.executeAsList().toMap()
    val cards = queries.selectCards {
            cardId, noteGuid, deckName, ord, scheduling, studyState,
            studyStateModified, studyStateClientModified, resetThrough,
            resetModified, resetClientModified, dueOverride, dueModified,
            dueClientModified, flagValue, flagIntentId, flagModified, flagClientModified,
            modified, clientModified, createdAt ->
        cardId to SyncCard(
            cardId = cardId,
            noteGuid = noteGuid,
            deckName = deckName,
            ord = ord.toInt(),
            scheduling = json.parseToJsonElement(scheduling).jsonObject,
            studyState = studyState.asCardStudyState(),
            studyStateModifiedAt = studyStateModified,
            studyStateClientModifiedAt = studyStateClientModified,
            scheduleResetThroughReviewId = resetThrough,
            scheduleResetModifiedAt = resetModified,
            scheduleResetClientModifiedAt = resetClientModified,
            dueDateOverrideMillis = dueOverride,
            dueDateOverrideModifiedAt = dueModified,
            dueDateOverrideClientModifiedAt = dueClientModified,
            modifiedAt = modified,
            clientModifiedAt = clientModified,
            createdAt = createdAt.ifBlank { null },
            flag = flagIntentId.takeIf(String::isNotBlank)?.let {
                SyncCardFlag(flagValue.toInt(), it, flagModified, flagClientModified)
            },
        )
    }.executeAsList().toMap()
    // The immutable review history (revlog) is the largest table by far. Callers that only
    // need content/scheduling metadata (outbox reconcile and upload planning) skip it so a
    // single sync cycle does not repeatedly parse the entire history it never reads.
    val reviews = if (!includeReviews) {
        emptyMap()
    } else {
        queries.selectReviews {
                reviewId, sourceCardId, noteGuid, cardOrd, deckName, ease, interval,
                lastInterval, factor, takenMillis, reviewKind, checksum, modified ->
            reviewId to SyncReview(
                reviewId = reviewId,
                sourceCardId = sourceCardId,
                noteGuid = noteGuid,
                cardOrd = cardOrd.toInt(),
                deckName = deckName,
                ease = ease.toInt(),
                interval = interval.toInt(),
                lastInterval = lastInterval.toInt(),
                factor = factor.toInt(),
                takenMillis = takenMillis.toInt(),
                reviewKind = reviewKind.toInt(),
                checksum = checksum,
                modifiedAt = modified,
            )
        }.executeAsList().toMap()
    }
    val reviewRetractions = queries.selectReviewRetractions {
            reviewId, intentId, clientModifiedAt, modifiedAt ->
        reviewId to SyncReviewRetraction(reviewId, intentId, clientModifiedAt, modifiedAt)
    }.executeAsList().toMap()
    val studyDays = queries.selectStudyDays {
            day, deckName, newStudied, reviewStudied, learningStudied, milliseconds, modified ->
        val value = SyncStudyDay(
            day = day,
            deckName = deckName,
            newStudied = newStudied.toInt(),
            reviewStudied = reviewStudied.toInt(),
            learningStudied = learningStudied.toInt(),
            millisecondsStudied = milliseconds,
            modifiedAt = modified,
        )
        "$day\u0000$deckName" to value
    }.executeAsList().toMap()
    val notetypes = queries.selectNotetypes { id, name, definition, checksum, modified, clientModified ->
        id to SyncNotetype(
            notetypeId = id,
            name = name,
            definition = json.parseToJsonElement(definition).jsonObject,
            checksum = checksum,
            modifiedAt = modified,
            clientModifiedAt = clientModified,
        )
    }.executeAsList().toMap()
    val decks = queries.selectDecks { name, config, checksum, modified, clientModified ->
        name to SyncDeck(
            name = name,
            config = json.parseToJsonElement(config).jsonObject,
            checksum = checksum,
            modifiedAt = modified,
            clientModifiedAt = clientModified,
        )
    }.executeAsList().toMap()
    val media = queries.selectMediaMetadata { filename, modified, sizeBytes ->
        filename to SyncMediaFile(filename, modified, byteArrayOf(), sizeBytes)
    }.executeAsList().toMap()
    val syncState = queries.selectSyncState { serverTime, capabilities -> serverTime to capabilities }
        .executeAsOneOrNull()
    return SyncedCollection(
        notes = notes,
        cards = cards,
        reviews = reviews,
        reviewRetractions = reviewRetractions,
        studyDays = studyDays,
        notetypes = notetypes,
        deckRecords = decks,
        media = media,
        deckNames = queries.selectDeckNames().executeAsList().toSet(),
        serverTime = syncState?.first,
        capabilities = syncState?.second
            ?.let { json.decodeFromString(stringList, it).toSet() }
            .orEmpty(),
    )
}
