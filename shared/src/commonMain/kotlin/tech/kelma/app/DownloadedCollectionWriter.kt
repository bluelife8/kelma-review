package tech.kelma.app

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaQueries

internal class DownloadedCollectionWriter(
    private val queries: KelmaQueries,
    private val json: Json,
    private val rebuildSchedules: (SyncedCollection, ScheduleProjectionChanges?) -> Unit,
) {
    private val stringList = ListSerializer(String.serializer())

    fun replace(
        collection: SyncedCollection,
        reconcileOutbox: () -> Unit,
        preserveMedia: Boolean = false,
    ) {
        queries.backfillLocalReviewPortableIdentities()
        clear(preserveMedia)
        queries.upsertServerTime(collection.serverTime)
        writeAll(collection, preserveMedia)
        finish(collection, reconcileOutbox, scheduleChanges = null)
    }

    fun replaceIncrementally(
        previous: SyncedCollection,
        collection: SyncedCollection,
        reconcileOutbox: () -> Unit,
    ) {
        queries.backfillLocalReviewPortableIdentities()
        queries.upsertServerTime(collection.serverTime)
        deleteRemoved(previous, collection)
        writeChanged(previous, collection)
        finish(collection, reconcileOutbox, previous.scheduleProjectionChanges(collection))
    }

    private fun writeAll(collection: SyncedCollection, preserveMedia: Boolean) {
        collection.notes.values.forEach(::writeNote)
        collection.cards.values.forEach(::writeCard)
        collection.reviews.values.forEach(::writeReview)
        collection.studyDays.values.forEach(::writeStudyDay)
        collection.notetypes.values.forEach(::writeNotetype)
        collection.deckRecords.values.forEach(::writeDeck)
        collection.deckNames.forEach(queries::insertDeckName)
        if (!preserveMedia) collection.media.values.forEach(::writeMedia)
    }

    private fun deleteRemoved(previous: SyncedCollection, collection: SyncedCollection) {
        previous.notes.keys.filterNot(collection.notes::containsKey).forEach(queries::deleteSyncNote)
        previous.cards.keys.filterNot(collection.cards::containsKey).forEach(queries::deleteSyncCard)
        previous.reviews.keys.filterNot(collection.reviews::containsKey).forEach(queries::deleteSyncReview)
        previous.studyDays.filterKeys { it !in collection.studyDays }.values.forEach { day ->
            queries.deleteSyncStudyDay(day.day, day.deckName)
        }
        previous.notetypes.keys.filterNot(collection.notetypes::containsKey).forEach(queries::deleteSyncNotetype)
        previous.deckRecords.keys.filterNot(collection.deckRecords::containsKey).forEach(queries::deleteSyncDeck)
        previous.deckNames.filterNot(collection.deckNames::contains).forEach(queries::deleteSyncDeckName)
        previous.media.keys.filterNot(collection.media::containsKey).forEach(queries::deleteSyncMedia)
    }

    private fun writeChanged(previous: SyncedCollection, collection: SyncedCollection) {
        collection.notes.forEach { (key, value) ->
            if (previous.notes[key] != value) writeNote(value)
        }
        collection.cards.forEach { (key, value) ->
            if (previous.cards[key] != value) writeCard(value)
        }
        collection.reviews.forEach { (key, value) ->
            if (previous.reviews[key] != value) writeReview(value)
        }
        collection.studyDays.forEach { (key, value) ->
            if (previous.studyDays[key] != value) writeStudyDay(value)
        }
        collection.notetypes.forEach { (key, value) ->
            if (previous.notetypes[key] != value) writeNotetype(value)
        }
        collection.deckRecords.forEach { (key, value) ->
            if (previous.deckRecords[key] != value) writeDeck(value)
        }
        collection.deckNames.filterNot(previous.deckNames::contains).forEach(queries::insertDeckName)
        collection.media.forEach { (key, value) ->
            if (!previous.media[key].contentEquals(value)) writeMedia(value)
        }
    }

    private fun finish(
        collection: SyncedCollection,
        reconcileOutbox: () -> Unit,
        scheduleChanges: ScheduleProjectionChanges?,
    ) {
        queries.pruneLocalReviewEvents()
        queries.pruneLocalCardStudyStates()
        queries.pruneLocalCardDueOverrides()
        queries.pruneLocalNoteOverrides()
        reconcileOutbox()
        rebuildSchedules(collection, scheduleChanges)
        queries.markBrowseIndexDirty()
    }

    private fun writeNote(note: SyncNote) {
        queries.insertNote(
            note.guid,
            note.notetypeId,
            json.encodeToString(stringList, note.fields),
            json.encodeToString(stringList, note.tags),
            note.checksum,
            note.modifiedAt,
            note.clientModifiedAt,
        )
    }

    private fun writeCard(card: SyncCard) {
        queries.insertCard(
            card.cardId,
            card.noteGuid,
            card.deckName,
            card.ord.toLong(),
            card.scheduling.toString(),
            card.studyState.name.lowercase(),
            card.studyStateModifiedAt,
            card.studyStateClientModifiedAt,
            card.scheduleResetThroughReviewId,
            card.scheduleResetModifiedAt,
            card.scheduleResetClientModifiedAt,
            card.dueDateOverrideMillis,
            card.dueDateOverrideModifiedAt,
            card.dueDateOverrideClientModifiedAt,
            card.modifiedAt,
            card.clientModifiedAt,
            card.createdAt.orEmpty(),
        )
    }

    private fun writeReview(review: SyncReview) {
        queries.insertReview(
            review.reviewId,
            review.sourceCardId,
            review.noteGuid,
            review.cardOrd.toLong(),
            review.deckName,
            review.ease.toLong(),
            review.interval.toLong(),
            review.lastInterval.toLong(),
            review.factor.toLong(),
            review.takenMillis.toLong(),
            review.reviewKind.toLong(),
            review.checksum,
            review.modifiedAt,
        )
    }

    private fun writeStudyDay(day: SyncStudyDay) {
        queries.insertStudyDay(
            day.day,
            day.deckName,
            day.newStudied.toLong(),
            day.reviewStudied.toLong(),
            day.learningStudied.toLong(),
            day.millisecondsStudied,
            day.modifiedAt,
        )
    }

    private fun writeNotetype(notetype: SyncNotetype) {
        queries.insertNotetype(
            notetype.notetypeId,
            notetype.name,
            notetype.definition.toString(),
            notetype.checksum,
            notetype.modifiedAt,
            notetype.clientModifiedAt,
        )
    }

    private fun writeDeck(deck: SyncDeck) {
        queries.insertDeck(
            deck.name,
            deck.config.toString(),
            deck.checksum,
            deck.modifiedAt,
            deck.clientModifiedAt,
        )
    }

    private fun writeMedia(file: SyncMediaFile) {
        queries.insertMedia(file.filename, file.modifiedAt, file.bytes, file.sizeBytes)
    }

    private fun SyncMediaFile?.contentEquals(other: SyncMediaFile): Boolean =
        this != null &&
            modifiedAt == other.modifiedAt &&
            sizeBytes == other.sizeBytes &&
            bytes.contentEquals(other.bytes)

    fun clear(preserveMedia: Boolean = false) {
        queries.clearSyncState()
        queries.clearNotes()
        queries.clearCards()
        queries.clearReviews()
        queries.clearStudyDays()
        queries.clearNotetypes()
        queries.clearDecks()
        queries.clearDeckNames()
        if (!preserveMedia) queries.clearMedia()
        queries.clearBrowseQueryTerms()
        queries.clearBrowseIndexTags()
        queries.clearBrowseIndexCards()
        queries.markBrowseIndexDirty()
    }
}
