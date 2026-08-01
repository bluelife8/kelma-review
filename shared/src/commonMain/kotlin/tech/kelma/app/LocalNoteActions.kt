package tech.kelma.app

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaDatabase

internal class LocalNoteActions(
    private val database: KelmaDatabase,
    private val json: Json,
    private val loadCollection: () -> SyncedCollection,
    private val loadLocalContent: () -> LocalContentSnapshot,
) {
    private val queries = database.kelmaQueries
    private val stringList = ListSerializer(String.serializer())

    fun bury(
        noteGuid: String,
        nowMillis: Long,
        studyDayPolicy: AccountStudyDayPolicy,
    ): LocalReviewSnapshot {
        requireVisibleNote(noteGuid)
        queries.upsertLocalNoteBury(noteGuid, studyDayAt(nowMillis, studyDayPolicy))
        return loadLocalReviewSnapshot(queries, nowMillis, studyDayPolicy)
    }

    fun createCopy(noteGuid: String, nowMillis: Long, copyGuid: String): AddedLocalNote {
        val displayed = requireVisibleNote(noteGuid)
        val source = displayed.notes.getValue(noteGuid)
        val sourceCards = displayed.cards.values.filter { it.noteGuid == noteGuid }
        require(sourceCards.isNotEmpty()) { "This note has no cards to copy" }
        database.transaction {
            queries.insertLocalNote(
                copyGuid,
                source.notetypeId,
                json.encodeToString(stringList, source.fields),
                json.encodeToString(stringList, source.tags),
                nowMillis,
            )
            sourceCards.forEach { card ->
                deckHierarchyNames(card.deckName).forEach { queries.insertLocalDeck(it, nowMillis) }
                queries.insertLocalCard(
                    localCardId(copyGuid, card.ord),
                    copyGuid,
                    card.deckName,
                    card.ord.toLong(),
                    nowMillis,
                )
            }
            queries.upsertLocalNoteSync(copyGuid, "upsert", "", nowMillis)
            queries.markBrowseIndexDirty()
        }
        val firstCard = sourceCards.minWith(compareBy<SyncCard> { it.ord }.thenBy { it.cardId })
        return AddedLocalNote(localCardId(copyGuid, firstCard.ord), copyGuid, loadLocalContent())
    }

    fun delete(noteGuid: String, nowMillis: Long): LocalContentSnapshot {
        val displayed = requireVisibleNote(noteGuid)
        val cardIds = displayed.cards.values
            .filter { it.noteGuid == noteGuid }
            .mapTo(mutableSetOf(), SyncCard::cardId)
        database.transaction {
            val downloaded = queries.countSyncNote(noteGuid).executeAsOne() > 0
            queries.deleteLocalCardStudyStatesForNote(noteGuid)
            queries.deleteLocalCardResetsForNote(noteGuid)
            queries.deleteLocalCardDueOverridesForNote(noteGuid)
            queries.deleteLocalNoteBury(noteGuid)
            cardIds.forEach { cardId ->
                queries.deleteLocalSchedule(cardId)
                queries.deleteLocalCardFlag(cardId)
                queries.deleteLocalCardBury(cardId)
                if (!downloaded) queries.deleteLocalReviewEventsForCard(cardId)
            }
            queries.deleteLocalCardsForNote(noteGuid)
            queries.deleteLocalNoteOverride(noteGuid)
            queries.deleteLocalNote(noteGuid)
            if (downloaded) {
                queries.upsertLocalNoteSync(
                    noteGuid,
                    "delete",
                    queries.selectSyncNoteChecksum(noteGuid).executeAsOne(),
                    nowMillis,
                )
            } else {
                queries.deleteLocalNoteSync(noteGuid)
            }
            queries.markBrowseIndexDirty()
        }
        return loadLocalContent()
    }

    private fun requireVisibleNote(noteGuid: String): SyncedCollection =
        loadCollection().withLocalContent(loadLocalContent()).also { displayed ->
            require(noteGuid in displayed.notes) { "This note no longer exists" }
        }
}
