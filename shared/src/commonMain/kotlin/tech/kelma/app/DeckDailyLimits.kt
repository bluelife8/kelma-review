package tech.kelma.app

/** Immutable daily capacities used to create one queue-local allocation tracker. */
internal data class DeckDailyLimitPlan(
    private val remainingNewByDeck: Map<String, Int>,
    private val remainingReviewByDeck: Map<String, Int>,
    private val newConsumesReviewByDeck: Map<String, Boolean>,
) {
    fun tracker(): DeckDailyLimitTracker = DeckDailyLimitTracker(
        remainingNewByDeck.toMutableMap(),
        remainingReviewByDeck.toMutableMap(),
        newConsumesReviewByDeck,
    )

    companion object {
        fun single(options: DeckOptions, remainingNew: Int, remainingReviews: Int): DeckDailyLimitPlan =
            DeckDailyLimitPlan(
                remainingNewByDeck = mapOf(SingleDeckLimitKey to remainingNew.coerceAtLeast(0)),
                remainingReviewByDeck = mapOf(SingleDeckLimitKey to remainingReviews.coerceAtLeast(0)),
                newConsumesReviewByDeck = mapOf(
                    SingleDeckLimitKey to !options.newCardsIgnoreReviewLimit,
                ),
            )
    }
}

internal class DeckDailyLimitTracker(
    private val remainingNewByDeck: MutableMap<String, Int>,
    private val remainingReviewByDeck: MutableMap<String, Int>,
    private val newConsumesReviewByDeck: Map<String, Boolean>,
) {
    private val reservedNewCardIds = mutableSetOf<Long>()
    private val reservedReviewCardIds = mutableSetOf<Long>()

    fun reserveNew(card: SyncCard): Boolean {
        if (!canAcceptNew(card)) return false
        acceptNew(card)
        reservedNewCardIds += card.cardId
        return true
    }

    fun isReservedNew(card: SyncCard): Boolean = card.cardId in reservedNewCardIds

    fun reserveReview(card: SyncCard): Boolean {
        val path = limitPath(card)
        if (path.any { remainingReviewByDeck.getValue(it) <= 0 }) return false
        path.forEach { deckName -> remainingReviewByDeck[deckName] = remainingReviewByDeck.getValue(deckName) - 1 }
        reservedReviewCardIds += card.cardId
        return true
    }

    fun isReservedReview(card: SyncCard): Boolean = card.cardId in reservedReviewCardIds

    private fun canAcceptNew(card: SyncCard): Boolean = limitPath(card).all { deckName ->
        remainingNewByDeck.getValue(deckName) > 0 &&
            (newConsumesReviewByDeck.getValue(deckName).not() || remainingReviewByDeck.getValue(deckName) > 0)
    }

    private fun acceptNew(card: SyncCard) {
        limitPath(card).forEach { deckName ->
            remainingNewByDeck[deckName] = remainingNewByDeck.getValue(deckName) - 1
            if (newConsumesReviewByDeck.getValue(deckName)) {
                remainingReviewByDeck[deckName] = remainingReviewByDeck.getValue(deckName) - 1
            }
        }
    }

    private fun limitPath(card: SyncCard): List<String> {
        if (SingleDeckLimitKey in remainingNewByDeck) return listOf(SingleDeckLimitKey)
        return deckHierarchyNames(card.deckName).filter(remainingNewByDeck::containsKey)
    }
}

internal fun SyncedCollection.dailyLimitPlan(
    rootDeckName: String,
    scopedCards: List<SyncCard>,
    localOptions: Map<String, DeckOptions>,
    studiedTodayByDeck: Map<String, DeckStudyCounts>,
): DeckDailyLimitPlan {
    val limitDecks = buildSet {
        addAll(deckHierarchyNames(rootDeckName))
        scopedCards.forEach { card -> addAll(deckHierarchyNames(card.deckName)) }
    }
    val optionsByDeck = limitDecks.associateWith { deckName -> effectiveDeckOptions(deckName, localOptions) }
    val countsByDeck = limitDecks.associateWith { parentName ->
        studiedTodayByDeck.entries
            .filter { (deckName, _) -> deckName.isDeckOrDescendantOf(parentName) }
            .fold(DeckStudyCounts()) { total, (_, counts) ->
                DeckStudyCounts(
                    newCards = total.newCards + counts.newCards,
                    reviews = total.reviews + counts.reviews,
                )
            }
    }
    return DeckDailyLimitPlan(
        remainingNewByDeck = optionsByDeck.mapValues { (deckName, options) ->
            remaining(options.newCardsPerDay, countsByDeck.getValue(deckName).newCards)
        },
        remainingReviewByDeck = optionsByDeck.mapValues { (deckName, options) ->
            val counts = countsByDeck.getValue(deckName)
            val consumed = counts.reviews + if (options.newCardsIgnoreReviewLimit) 0 else counts.newCards
            remaining(options.maximumReviewsPerDay, consumed)
        },
        newConsumesReviewByDeck = optionsByDeck.mapValues { (_, options) ->
            !options.newCardsIgnoreReviewLimit
        },
    )
}

private fun remaining(limit: Int, consumed: Int): Int =
    (limit.toLong() - consumed.toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

private const val SingleDeckLimitKey = "\u0000single-deck-limit"
