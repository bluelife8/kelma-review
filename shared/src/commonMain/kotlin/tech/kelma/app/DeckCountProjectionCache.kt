package tech.kelma.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.math.min

/**
 * Retains exact deck summaries and reprojects only decks whose queue inputs changed.
 * All count calculations still go through the canonical queue builder.
 */
internal class DeckCountProjectionCache {
    private val mutex = Mutex()
    private var state: DeckCountProjectionState? = null

    suspend fun project(
        collection: SyncedCollection,
        localContent: LocalContentSnapshot,
        localReviews: LocalReviewSnapshot,
        nowMillis: Long,
        pendingSyncByDeck: Map<String, PendingDeckChanges>,
        selectedDeckId: String?,
    ): List<DeckSummary> {
        val coroutineContext = currentCoroutineContext()
        return mutex.withLock {
            projectLocked(
                collection,
                localContent,
                localReviews,
                nowMillis,
                pendingSyncByDeck,
                selectedDeckId,
                coroutineContext,
            )
        }
    }

    private fun projectLocked(
        collection: SyncedCollection,
        localContent: LocalContentSnapshot,
        localReviews: LocalReviewSnapshot,
        nowMillis: Long,
        pendingSyncByDeck: Map<String, PendingDeckChanges>,
        selectedDeckId: String?,
        coroutineContext: CoroutineContext,
    ): List<DeckSummary> {
        val previous = state
        val index = previous?.index?.takeIf { it.collection === collection }
            ?: buildDeckSourceIndex(collection, coroutineContext)
        val summaries = previous?.summaries.orEmpty()
            .filterKeys(index.deckNames::contains)
            .toMutableMap()
        val affectedDecks = affectedDecks(
            previous,
            index,
            summaries,
            localContent,
            localReviews,
            nowMillis,
            selectedDeckId,
        )
        affectedDecks.sortedWith(String.CASE_INSENSITIVE_ORDER).forEachIndexed { position, deckName ->
            if (position % 16 == 0) coroutineContext.ensureActive()
            summaries[deckName] = projectDeck(
                deckName,
                collection,
                index,
                localContent,
                localReviews,
                nowMillis,
                pendingSyncByDeck,
                selectedDeckId,
            )
        }
        normalizeSummaries(summaries, selectedDeckId, pendingSyncByDeck)
        state = DeckCountProjectionState(
            index,
            localReviews,
            localContent.deckOptions,
            localContent.cardFlags,
            summaries,
        )
        return index.orderedDeckNames.mapNotNull { summaries[it]?.summary }
    }

    private fun affectedDecks(
        previous: DeckCountProjectionState?,
        index: DeckSourceIndex,
        summaries: Map<String, CachedDeckSummary>,
        localContent: LocalContentSnapshot,
        localReviews: LocalReviewSnapshot,
        nowMillis: Long,
        selectedDeckId: String?,
    ): Set<String> = buildSet {
        if (previous == null) {
            if (selectedDeckId == null) addAll(index.orderedDeckNames)
        } else {
            if (previous.index !== index) addAll(changedSourceDecks(previous.index, index))
            changedMapKeys(previous.deckOptions, localContent.deckOptions).forEach { deckName ->
                addAll(previous.index.relatedDecks(deckName))
                addAll(index.relatedDecks(deckName))
            }
            addAll(changedReviewDecks(previous.localReviews, localReviews, previous.index, index))
            summaries.forEach { (deckName, cached) ->
                if (cached.projectedAtMillis > nowMillis || cached.validUntilMillis <= nowMillis) add(deckName)
            }
            if (selectedDeckId == null) addAll(index.deckNames - summaries.keys)
        }
        selectedDeckId?.takeIf(index.deckNames::contains)?.let { deckName ->
            val selectedNeedsQueue = summaries[deckName]?.summary?.queueLoaded != true
            val selectedContentChanged = previous?.index?.collection !== index.collection
            val selectedFlagsChanged = previous?.cardFlags !== localContent.cardFlags
            if (selectedNeedsQueue || selectedContentChanged || selectedFlagsChanged) add(deckName)
        }
        retainAll(index.deckNames)
    }

    private fun projectDeck(
        deckName: String,
        collection: SyncedCollection,
        index: DeckSourceIndex,
        localContent: LocalContentSnapshot,
        localReviews: LocalReviewSnapshot,
        nowMillis: Long,
        pendingSyncByDeck: Map<String, PendingDeckChanges>,
        selectedDeckId: String?,
    ): CachedDeckSummary {
        val options = collection.effectiveDeckOptions(deckName, localContent.deckOptions)
        val deckCards = index.cardsBySubtree[deckName].orEmpty()
        val summary = collection.projectDeckCards(
            name = deckName,
            deckCards = deckCards,
            localSchedules = localReviews.schedules,
            nowMillis = nowMillis,
            deckOptions = localContent.deckOptions,
            studiedTodayByDeck = localReviews.studiedTodayByDeck,
            studiedCardOrdsByNoteToday = localReviews.studiedCardOrdsByNoteToday,
            buriedCardIds = localReviews.buriedCardIds,
            buriedNoteGuids = localReviews.buriedNoteGuids,
            dueDateOverrides = localReviews.dueDateOverrides,
            studyDayPolicy = localReviews.studyDayPolicy,
            loadQueue = deckName == selectedDeckId,
        ).withProjectionMetadata(localContent.cardFlags, pendingSyncByDeck)
        return CachedDeckSummary(summary, nowMillis, nextDeckCountChange(deckCards, localReviews, options, nowMillis))
    }

    private fun normalizeSummaries(
        summaries: MutableMap<String, CachedDeckSummary>,
        selectedDeckId: String?,
        pendingSyncByDeck: Map<String, PendingDeckChanges>,
    ) {
        summaries.keys.toList().forEach { deckName ->
            val cached = summaries.getValue(deckName)
            val withoutQueue = if (deckName != selectedDeckId && cached.summary.queueLoaded) {
                cached.summary.copy(cards = emptyList(), queueLoaded = false)
            } else {
                cached.summary
            }
            summaries[deckName] = cached.copy(
                summary = withoutQueue.withProjectionMetadata(emptyMap(), pendingSyncByDeck),
            )
        }
    }
}

private data class DeckCountProjectionState(
    val index: DeckSourceIndex,
    val localReviews: LocalReviewSnapshot,
    val deckOptions: Map<String, DeckOptions>,
    val cardFlags: Map<Long, Int>,
    val summaries: Map<String, CachedDeckSummary>,
)

private data class CachedDeckSummary(
    val summary: DeckSummary,
    val projectedAtMillis: Long,
    val validUntilMillis: Long,
)

private data class DeckSourceIndex(
    val collection: SyncedCollection,
    val orderedDeckNames: List<String>,
    val deckNames: Set<String>,
    val cardsByDeck: Map<String, List<SyncCard>>,
    val cardsBySubtree: Map<String, List<SyncCard>>,
    val primaryDeckByNoteGuid: Map<String, String>,
    val additionalDecksByNoteGuid: Map<String, Set<String>>,
)

private fun buildDeckSourceIndex(
    collection: SyncedCollection,
    coroutineContext: CoroutineContext,
): DeckSourceIndex {
    val cardsByDeck = mutableMapOf<String, MutableList<SyncCard>>()
    val primaryDeckByNoteGuid = mutableMapOf<String, String>()
    val additionalDecksByNoteGuid = mutableMapOf<String, MutableSet<String>>()
    val knownDeckNames = (collection.deckNames + collection.deckRecords.keys)
        .flatMapTo(mutableSetOf(), ::deckHierarchyNames)
    collection.cards.values.forEachIndexed { index, card ->
        if (index % 256 == 0) coroutineContext.ensureActive()
        knownDeckNames += deckHierarchyNames(card.deckName)
        if (card.noteGuid !in collection.notes) return@forEachIndexed
        cardsByDeck.getOrPut(card.deckName, ::mutableListOf).add(card)
        val primaryDeck = primaryDeckByNoteGuid[card.noteGuid]
        if (primaryDeck == null) {
            primaryDeckByNoteGuid[card.noteGuid] = card.deckName
        } else if (primaryDeck != card.deckName) {
            additionalDecksByNoteGuid.getOrPut(card.noteGuid, ::mutableSetOf).add(card.deckName)
        }
    }
    val orderedNames = knownDeckNames.sortedWith(String.CASE_INSENSITIVE_ORDER)
    val directCards = cardsByDeck.mapValues { it.value.toList() }
    val subtreeCards = orderedNames.associateWith { parentName ->
        directCards.entries
            .filter { (deckName, _) -> deckName.isDeckOrDescendantOf(parentName) }
            .flatMap { (_, cards) -> cards }
    }
    return DeckSourceIndex(
        collection = collection,
        orderedDeckNames = orderedNames,
        deckNames = orderedNames.toSet(),
        cardsByDeck = directCards,
        cardsBySubtree = subtreeCards,
        primaryDeckByNoteGuid = primaryDeckByNoteGuid,
        additionalDecksByNoteGuid = additionalDecksByNoteGuid.mapValues { it.value.toSet() },
    )
}

private fun changedSourceDecks(previous: DeckSourceIndex, current: DeckSourceIndex): Set<String> = buildSet {
    val names = previous.deckNames + current.deckNames
    names.forEach { deckName ->
        if (
            (deckName in previous.deckNames) != (deckName in current.deckNames) ||
            previous.collection.deckRecords[deckName]?.config != current.collection.deckRecords[deckName]?.config ||
            !sameProjectionCards(
                previous.cardsByDeck[deckName].orEmpty(),
                current.cardsByDeck[deckName].orEmpty(),
            )
        ) {
            addAll(previous.relatedDecks(deckName))
            addAll(current.relatedDecks(deckName))
        }
    }
}

private fun sameProjectionCards(previous: List<SyncCard>, current: List<SyncCard>): Boolean =
    previous.size == current.size && previous.indices.all { index ->
        val oldCard = previous[index]
        val newCard = current[index]
        oldCard.cardId == newCard.cardId &&
            oldCard.noteGuid == newCard.noteGuid &&
            oldCard.ord == newCard.ord &&
            oldCard.studyState == newCard.studyState
    }

private fun changedReviewDecks(
    previous: LocalReviewSnapshot,
    current: LocalReviewSnapshot,
    previousIndex: DeckSourceIndex,
    currentIndex: DeckSourceIndex,
): Set<String> {
    if (previous === current) return emptySet()
    if (
        previous.studyDay != current.studyDay ||
        previous.studyDayPolicy != current.studyDayPolicy
    ) return previousIndex.deckNames + currentIndex.deckNames
    val hint = current.deckProjectionMutationHint
    if (hint?.previousToken === previous.deckProjectionToken) {
        return resolveMutationHint(hint, previousIndex, currentIndex)
    }
    return buildSet {
        changedMapKeys(previous.schedules, current.schedules).forEach { cardId ->
            addDecksForCard(cardId, previousIndex, currentIndex)
        }
        changedMapKeys(previous.dueDateOverrides, current.dueDateOverrides).forEach { cardId ->
            addDecksForCard(cardId, previousIndex, currentIndex)
        }
        symmetricDifference(previous.buriedCardIds, current.buriedCardIds).forEach { cardId ->
            addDecksForCard(cardId, previousIndex, currentIndex)
        }
        symmetricDifference(previous.buriedNoteGuids, current.buriedNoteGuids).forEach { noteGuid ->
            addDecksForNote(noteGuid, previousIndex, currentIndex)
        }
        changedMapKeys(previous.studiedTodayByDeck, current.studiedTodayByDeck).forEach { deckName ->
            addAll(previousIndex.relatedDecks(deckName))
            addAll(currentIndex.relatedDecks(deckName))
        }
        changedMapKeys(
            previous.studiedCardOrdsByNoteToday,
            current.studiedCardOrdsByNoteToday,
        ).forEach { noteGuid -> addDecksForNote(noteGuid, previousIndex, currentIndex) }
    }
}

private fun resolveMutationHint(
    hint: DeckProjectionMutationHint,
    previousIndex: DeckSourceIndex,
    currentIndex: DeckSourceIndex,
): Set<String> = buildSet {
    hint.deckNames.forEach { deckName ->
        addAll(previousIndex.relatedDecks(deckName))
        addAll(currentIndex.relatedDecks(deckName))
    }
    hint.cardIds.forEach { addDecksForCard(it, previousIndex, currentIndex) }
    hint.noteGuids.forEach { addDecksForNote(it, previousIndex, currentIndex) }
}

private fun MutableSet<String>.addDecksForCard(
    cardId: Long,
    previousIndex: DeckSourceIndex,
    currentIndex: DeckSourceIndex,
) {
    previousIndex.deckNameFor(cardId)?.let { addAll(previousIndex.relatedDecks(it)) }
    currentIndex.deckNameFor(cardId)?.let { addAll(currentIndex.relatedDecks(it)) }
}

private fun DeckSourceIndex.deckNameFor(cardId: Long): String? {
    val card = collection.cards[cardId] ?: return null
    return card.deckName.takeIf { card.noteGuid in collection.notes }
}

private fun MutableSet<String>.addDecksForNote(
    noteGuid: String,
    previousIndex: DeckSourceIndex,
    currentIndex: DeckSourceIndex,
) {
    previousIndex.primaryDeckByNoteGuid[noteGuid]?.let { addAll(previousIndex.relatedDecks(it)) }
    currentIndex.primaryDeckByNoteGuid[noteGuid]?.let { addAll(currentIndex.relatedDecks(it)) }
    previousIndex.additionalDecksByNoteGuid[noteGuid].orEmpty().forEach {
        addAll(previousIndex.relatedDecks(it))
    }
    currentIndex.additionalDecksByNoteGuid[noteGuid].orEmpty().forEach {
        addAll(currentIndex.relatedDecks(it))
    }
}

private fun DeckSourceIndex.relatedDecks(deckName: String): Set<String> {
    val rootName = deckHierarchyNames(deckName).first()
    return deckNames.filterTo(mutableSetOf()) { it.isDeckOrDescendantOf(rootName) }
}

private fun <Key, Value> changedMapKeys(
    previous: Map<Key, Value>,
    current: Map<Key, Value>,
): Set<Key> {
    if (previous === current) return emptySet()
    return buildSet {
        previous.forEach { (key, value) ->
            if (key !in current || current[key] != value) add(key)
        }
        current.keys.forEach { key -> if (key !in previous) add(key) }
    }
}

private fun <Value> symmetricDifference(previous: Set<Value>, current: Set<Value>): Set<Value> {
    if (previous === current) return emptySet()
    return (previous - current) + (current - previous)
}

private fun nextDeckCountChange(
    cards: List<SyncCard>,
    reviews: LocalReviewSnapshot,
    options: DeckOptions,
    nowMillis: Long,
): Long {
    var nextChange = nextStudyDayStart(nowMillis, reviews.studyDayPolicy)
    var hasDueReview = false
    cards.forEach { card ->
        if (
            card.studyState != CardStudyState.Active ||
            card.cardId in reviews.buriedCardIds ||
            card.noteGuid in reviews.buriedNoteGuids
        ) {
            return@forEach
        }
        val schedule = reviews.schedules[card.cardId]
        val dueAt = reviews.dueDateOverrides[card.cardId] ?: schedule?.dueAtMillis ?: return@forEach
        if (dueAt > nowMillis) nextChange = min(nextChange, dueAt)
        if (schedule?.phase in setOf(ReviewPhase.Learning, ReviewPhase.Relearning)) {
            val learnAheadAt = subtractSaturated(dueAt, DefaultLearnAheadMillis)
            if (learnAheadAt > nowMillis) nextChange = min(nextChange, learnAheadAt)
        }
        if (schedule?.phase == ReviewPhase.Review && dueAt <= nowMillis) hasDueReview = true
    }
    if (
        hasDueReview &&
        options.reviewSortOrder in setOf(
            ReviewSortOrder.RetrievabilityAscending,
            ReviewSortOrder.RetrievabilityDescending,
            ReviewSortOrder.RelativeOverdueness,
        )
    ) {
        nextChange = min(nextChange, incrementSaturated(nowMillis))
    }
    return nextChange
}

private fun subtractSaturated(value: Long, amount: Long): Long =
    if (value < Long.MIN_VALUE + amount) Long.MIN_VALUE else value - amount

private fun incrementSaturated(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

private fun DeckSummary.withProjectionMetadata(
    cardFlags: Map<Long, Int>,
    pendingSyncByDeck: Map<String, PendingDeckChanges>,
): DeckSummary {
    val nextCards = if (cards.isEmpty() || cardFlags.isEmpty()) {
        cards
    } else {
        cards.map { card -> card.copy(flag = cardFlags[card.id] ?: 0) }
    }
    val nextPending = pendingSyncByDeck[name] ?: PendingDeckChanges()
    return if (nextCards === cards && nextPending == pendingChanges) {
        this
    } else {
        copy(cards = nextCards, pendingChanges = nextPending)
    }
}
