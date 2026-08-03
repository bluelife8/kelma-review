package tech.kelma.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.kelma.db.KelmaDatabase

private const val BrowseQueryId = "active"

internal class BrowseIndexPersistence(
    private val database: KelmaDatabase,
) {
    private val queries = database.kelmaQueries
    private val mutex = Mutex()
    private var cachedQueryKey: IndexedBrowseQueryKey? = null
    private var cachedQuery: IndexedBrowsePage? = null

    suspend fun prepare(collection: SyncedCollection) = mutex.withLock {
        if (queries.selectBrowseIndexDirty().executeAsOne() != 0L) rebuild(collection)
    }

    suspend fun loadPage(
        collection: SyncedCollection,
        request: BrowsePageRequest,
        rebuildIfDirty: Boolean = true,
    ): BrowsePage = mutex.withLock {
        require(request.offset >= 0) { "Browse page offset cannot be negative" }
        require(request.limit in 1..500) { "Browse page size must be between 1 and 500" }
        if (rebuildIfDirty && queries.selectBrowseIndexDirty().executeAsOne() != 0L) {
            rebuild(collection)
        }
        val key = IndexedBrowseQueryKey(
            request.queryId,
            request.query,
            request.sorting,
            if (queryHasTerm(request.query, "is:due")) request.nowMillis / 30_000L else 0L,
        )
        val indexed = cachedQuery.takeIf { cachedQueryKey == key } ?: database.transactionWithResult {
            queries.clearBrowseQueryTerms()
            request.query.toBrowseIndexTerms().forEach { (kind, value) ->
                queries.insertBrowseQueryTerm(BrowseQueryId, kind, value)
            }
            val rows = queries.selectBrowseResultRows(
                queryId = BrowseQueryId,
                nowMillis = request.nowMillis,
                sortField = request.sorting.field.name,
                ascending = if (request.sorting.ascending) 1 else 0,
            ) { cardId, state, dueMillis ->
                IndexedBrowseRow(cardId, BrowseCardState.valueOf(state), dueMillis)
            }.executeAsList()
            val decks = queries.selectBrowseDeckFacets { deck, count -> deck to count.toInt() }.executeAsList()
            val tags = queries.selectBrowseTagFacets { tag, count -> tag to count.toInt() }.executeAsList()
            queries.clearBrowseQueryTerms()
            IndexedBrowsePage(rows, rows.size, decks, tags)
        }.also {
            cachedQueryKey = key
            cachedQuery = it
        }
        val pageStart = request.offset.coerceIn(0, indexed.rows.size)
        val pageEnd = (pageStart.toLong() + request.limit)
            .coerceAtMost(indexed.rows.size.toLong())
            .toInt()
        val renderedRows = indexed.rows.subList(pageStart, pageEnd).mapNotNull { indexedRow ->
            collection.cards[indexedRow.cardId]?.let(collection::browseContentRow)?.copy(
                state = indexedRow.state,
                dueMillis = indexedRow.dueMillis,
            )
        }
        BrowsePage(
            renderedRows,
            indexed.totalCount,
            indexed.decks.takeIf { request.offset == 0 }.orEmpty(),
            indexed.tags.takeIf { request.offset == 0 }.orEmpty(),
        )
    }

    private suspend fun rebuild(collection: SyncedCollection) {
        cachedQueryKey = null
        cachedQuery = null
        val coroutineContext = currentCoroutineContext()
        database.transaction {
            queries.clearBrowseQueryTerms()
            queries.clearBrowseIndexTags()
            queries.clearBrowseIndexCards()
            collection.cards.values.forEachIndexed { index, card ->
                if (index % 128 == 0) coroutineContext.ensureActive()
                collection.browseIndexEntry(card)?.let(::insert)
            }
            queries.markBrowseIndexClean()
        }
    }

    private fun insert(entry: BrowseIndexEntry) {
        queries.insertBrowseIndexCard(
            entry.row.cardId,
            entry.row.noteGuid,
            entry.cardOrd.toLong(),
            entry.questionSort,
            entry.answerSort,
            entry.row.deck,
            entry.deckSort,
            entry.notetypeSort,
            entry.tagsSort,
            entry.searchText,
            if (entry.row.state == BrowseCardState.Suspended) "suspended" else "active",
            if (entry.row.isLocal) 1 else 0,
            entry.remoteDueAtMillis,
            entry.remoteDueModifiedAtMillis,
            entry.row.createdAtMillis,
        )
        entry.row.tags.distinctBy(String::lowercase).forEach { tag ->
            queries.insertBrowseIndexTag(entry.row.cardId, tag, tag.lowercase())
        }
    }
}

private data class IndexedBrowseQueryKey(
    val queryId: String,
    val query: String,
    val sorting: BrowseSorting,
    val timeKey: Long,
)

private data class IndexedBrowseRow(
    val cardId: Long,
    val state: BrowseCardState,
    val dueMillis: Long?,
)

private data class IndexedBrowsePage(
    val rows: List<IndexedBrowseRow>,
    val totalCount: Int,
    val decks: List<Pair<String, Int>>,
    val tags: List<Pair<String, Int>>,
)

private data class BrowseIndexEntry(
    val row: BrowseCardRow,
    val cardOrd: Int,
    val questionSort: String,
    val answerSort: String,
    val deckSort: String,
    val notetypeSort: String,
    val tagsSort: String,
    val searchText: String,
    val remoteDueAtMillis: Long,
    val remoteDueModifiedAtMillis: Long,
)

private fun SyncedCollection.browseIndexEntry(card: SyncCard): BrowseIndexEntry? {
    val note = notes[card.noteGuid] ?: return null
    val notetype = notetypes[note.notetypeId]?.name ?: "Basic"
    val fields = note.fields.map(String::asPlainCardText)
    val question = fields.firstOrNull().orEmpty().ifBlank { "(empty card)" }
    val answer = fields.getOrNull(1).orEmpty().ifBlank { "(no answer content)" }
    val questionSort = question.lowercase()
    val answerSort = answer.lowercase()
    val deckSort = card.deckName.lowercase()
    val notetypeSort = notetype.lowercase()
    val tagsSort = note.tags.joinToString(",").lowercase()
    val searchText = fields.map(String::lowercase)
        .plus(deckSort)
        .plus(notetypeSort)
        .plus(note.tags.map(String::lowercase))
        .joinToString("\u001F")
    val row = BrowseCardRow(
        cardId = card.cardId,
        noteGuid = note.guid,
        question = question,
        answer = answer,
        deck = card.deckName,
        notetype = notetype,
        tags = note.tags,
        state = if (card.studyState == CardStudyState.Suspended) {
            BrowseCardState.Suspended
        } else {
            BrowseCardState.New
        },
        dueMillis = null,
        isLocal = card.cardId < 0,
        createdAtMillis = card.createdAtMillis(),
    )
    return BrowseIndexEntry(
        row = row,
        cardOrd = card.ord,
        questionSort = questionSort,
        answerSort = answerSort,
        deckSort = deckSort,
        notetypeSort = notetypeSort,
        tagsSort = tagsSort,
        searchText = searchText,
        remoteDueAtMillis = card.dueDateOverrideMillis,
        remoteDueModifiedAtMillis = rfc3339ToEpochMillis(card.dueDateOverrideClientModifiedAt) ?: 0L,
    )
}

private fun String.toBrowseIndexTerms(): List<Pair<String, String>> = parseBrowseQuery(this).flatMap { term ->
    when (term) {
        is BrowseTerm.Text -> listOf("text" to term.value.lowercase())
        is BrowseTerm.Deck -> listOf("deck" to term.value.lowercase())
        is BrowseTerm.Tag -> listOf("tag" to term.value.lowercase())
        is BrowseTerm.Notetype -> listOf("notetype" to term.value.lowercase())
        is BrowseTerm.Flag -> when (term.value) {
            "new", "learning", "review", "suspended" -> listOf("state" to term.value)
            "due", "local" -> listOf(term.value to term.value)
            else -> emptyList()
        }
        is BrowseTerm.Created -> when (val filter = parseBrowseCreatedFilter(term.value)) {
            BrowseCreatedFilter.Unknown -> listOf("created_unknown" to "")
            is BrowseCreatedFilter.Range -> buildList {
                if (filter.startMillis != Long.MIN_VALUE) add("created_min" to filter.startMillis.toString())
                if (filter.endExclusiveMillis != Long.MAX_VALUE) {
                    add("created_max" to filter.endExclusiveMillis.toString())
                }
            }
            BrowseCreatedFilter.Invalid -> listOf("created_invalid" to "")
        }
    }
}
