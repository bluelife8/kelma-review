package tech.kelma.app

import kotlin.math.pow

internal const val DefaultLearnAheadMillis = 20L * 60L * 1_000L

internal fun buildDeckQueue(
    cards: List<SyncCard>,
    localSchedules: Map<Long, LocalCardSchedule>,
    options: DeckOptions,
    remainingNew: Int,
    remainingReviews: Int,
    nowMillis: Long,
    dailyLimitPlan: DeckDailyLimitPlan? = null,
    studiedCardOrdsByNoteToday: Map<String, Set<Int>> = emptyMap(),
    dueDateOverrides: Map<Long, Long> = emptyMap(),
    studyDayPolicy: AccountStudyDayPolicy = AccountStudyDayPolicy(dayStartHour = 0),
): List<SyncCard> {
    val learnAheadCutoff = if (nowMillis > Long.MAX_VALUE - DefaultLearnAheadMillis) {
        Long.MAX_VALUE
    } else {
        nowMillis + DefaultLearnAheadMillis
    }
    fun effectiveDueAt(card: SyncCard): Long? =
        dueDateOverrides[card.cardId] ?: localSchedules[card.cardId]?.dueAtMillis

    val activeCards = cards.filter { it.studyState == CardStudyState.Active }
    val due = activeCards.filter { effectiveDueAt(it)?.let { dueAt -> dueAt <= nowMillis } ?: true }
    val learning = due.filter { card ->
        localSchedules[card.cardId]?.phase in setOf(ReviewPhase.Learning, ReviewPhase.Relearning)
    }
    val learnAhead = activeCards.filter { card ->
        val schedule = localSchedules[card.cardId] ?: return@filter false
        val dueAt = effectiveDueAt(card) ?: return@filter false
        schedule.phase in setOf(ReviewPhase.Learning, ReviewPhase.Relearning) &&
            !schedule.isInterdayLearning(dueDateOverrides[card.cardId], studyDayPolicy) &&
            dueAt > nowMillis && dueAt <= learnAheadCutoff
    }.sortedWith(
        compareBy<SyncCard> { effectiveDueAt(it) ?: Long.MAX_VALUE }.thenBy(SyncCard::cardId),
    )
    val (interdayLearning, intradayLearning) = learning.partition { card ->
        studyDayAt(effectiveDueAt(card) ?: Long.MAX_VALUE, studyDayPolicy) >
            studyDayAt(localSchedules.getValue(card.cardId).lastReviewAtMillis, studyDayPolicy)
    }
    val learningIds = learning.mapTo(mutableSetOf(), SyncCard::cardId)
    val reviews = due.filter { card ->
        card.cardId !in learningIds && localSchedules[card.cardId]?.phase == ReviewPhase.Review
    }.sortedWith(
        reviewComparator(options.reviewSortOrder, localSchedules, dueDateOverrides, nowMillis, studyDayPolicy),
    )
    val newCards = orderedNewCards(
        cards = due.filter { it.cardId !in localSchedules },
        options = options,
        seed = studyDayAt(nowMillis, studyDayPolicy),
    )
    val dueComparator = compareBy<SyncCard> { effectiveDueAt(it) ?: Long.MAX_VALUE }
        .thenBy(SyncCard::cardId)
    val reviewAndInterday = placeAroundReviews(
        reviews = reviews,
        inserted = interdayLearning.sortedWith(dueComparator),
        order = options.interdayLearningMixOrder,
    )
    val ordered = intradayLearning.sortedWith(dueComparator) + placeAroundReviews(
        reviews = reviewAndInterday,
        inserted = newCards,
        order = options.newReviewMixOrder,
    ) + learnAhead
    return applyDailyLimitsAndSiblingBurying(
        ordered,
        localSchedules,
        options,
        remainingNew,
        remainingReviews,
        dailyLimitPlan,
        studiedCardOrdsByNoteToday,
        dueDateOverrides,
        studyDayPolicy,
    )
}

private fun orderedNewCards(
    cards: List<SyncCard>,
    options: DeckOptions,
    seed: Long,
): List<SyncCard> {
    val gathered = cards.sortedWith(newGatherComparator(options.newCardGatherOrder, seed))
    val rank = gathered.mapIndexed { index, card -> card.cardId to index }.toMap()
    val gatherRank = compareBy<SyncCard> { rank.getValue(it.cardId) }
    val randomCard = compareBy<SyncCard> { stableQueueKey(it.cardId.toString(), seed) }
    val comparator = when (options.newCardSortOrder) {
        NewCardSortOrder.TemplateThenGather -> compareBy<SyncCard> { it.ord }.then(gatherRank)
        NewCardSortOrder.GatherOrder -> gatherRank
        NewCardSortOrder.TemplateThenRandom -> compareBy<SyncCard> { it.ord }.then(randomCard)
        NewCardSortOrder.RandomNoteThenTemplate ->
            compareBy<SyncCard> { stableQueueKey(it.noteGuid, seed) }.thenBy { it.ord }.thenBy { it.cardId }
        NewCardSortOrder.RandomCard -> randomCard.thenBy { it.cardId }
    }
    return gathered.sortedWith(comparator)
}

private fun applyDailyLimitsAndSiblingBurying(
    ordered: List<SyncCard>,
    schedules: Map<Long, LocalCardSchedule>,
    options: DeckOptions,
    remainingNew: Int,
    remainingReviews: Int,
    dailyLimitPlan: DeckDailyLimitPlan?,
    studiedCardOrdsByNoteToday: Map<String, Set<Int>>,
    dueDateOverrides: Map<Long, Long>,
    studyDayPolicy: AccountStudyDayPolicy,
): List<SyncCard> {
    val limits = (dailyLimitPlan ?: DeckDailyLimitPlan.single(options, remainingNew, remainingReviews)).tracker()
    val allocationSeenByNote = studiedCardOrdsByNoteToday
        .mapValuesTo(mutableMapOf()) { it.value.toMutableSet() }
    ordered.filter { schedules[it.cardId] == null }.forEach { card ->
        val shownSibling = allocationSeenByNote[card.noteGuid].orEmpty().any { it != card.ord }
        if (!shownSibling || !shouldBurySibling(null, options, null, studyDayPolicy)) {
            if (limits.reserveNew(card)) {
                allocationSeenByNote.getOrPut(card.noteGuid, ::mutableSetOf).add(card.ord)
            }
        }
    }
    ordered.forEach { card ->
        val schedule = schedules[card.cardId]
        val shownSibling = allocationSeenByNote[card.noteGuid].orEmpty().any { it != card.ord }
        when (schedule?.phase) {
            ReviewPhase.Review -> if (
                !shownSibling ||
                !shouldBurySibling(schedule, options, dueDateOverrides[card.cardId], studyDayPolicy)
            ) {
                if (limits.reserveReview(card)) {
                    allocationSeenByNote.getOrPut(card.noteGuid, ::mutableSetOf).add(card.ord)
                }
            }
            ReviewPhase.Learning, ReviewPhase.Relearning -> if (
                !shownSibling ||
                !shouldBurySibling(schedule, options, dueDateOverrides[card.cardId], studyDayPolicy)
            ) {
                allocationSeenByNote.getOrPut(card.noteGuid, ::mutableSetOf).add(card.ord)
            }
            null -> Unit
        }
    }
    return buildList {
        val seenByNote = studiedCardOrdsByNoteToday.mapValuesTo(mutableMapOf()) { it.value.toMutableSet() }
        ordered.forEach { card ->
            val schedule = schedules[card.cardId]
            val withinLimit = when (schedule?.phase) {
                null -> limits.isReservedNew(card)
                ReviewPhase.Review -> limits.isReservedReview(card)
                else -> true
            }
            if (!withinLimit) return@forEach
            val shownSiblings = seenByNote[card.noteGuid].orEmpty().any { it != card.ord }
            if (
                shownSiblings &&
                shouldBurySibling(schedule, options, dueDateOverrides[card.cardId], studyDayPolicy)
            ) return@forEach
            add(card)
            seenByNote.getOrPut(card.noteGuid, ::mutableSetOf).add(card.ord)
        }
    }
}

private fun shouldBurySibling(
    schedule: LocalCardSchedule?,
    options: DeckOptions,
    dueDateOverride: Long?,
    studyDayPolicy: AccountStudyDayPolicy,
): Boolean = when (schedule?.phase) {
    null -> options.buryNewSiblings
    ReviewPhase.Review -> options.buryReviewSiblings
    ReviewPhase.Learning, ReviewPhase.Relearning ->
        options.buryInterdayLearningSiblings && schedule.isInterdayLearning(dueDateOverride, studyDayPolicy)
}

private fun newGatherComparator(order: NewCardGatherOrder, seed: Long): Comparator<SyncCard> {
    val position = compareBy<SyncCard> { it.synchronizedNewPosition ?: it.cardId }
        .thenBy(SyncCard::cardId)
    val reversePosition = compareByDescending<SyncCard> { it.synchronizedNewPosition ?: it.cardId }
        .thenByDescending(SyncCard::cardId)
    val randomNote = compareBy<SyncCard> { stableQueueKey(it.noteGuid, seed) }
        .thenBy { it.noteGuid }.thenBy { it.ord }.thenBy { it.cardId }
    val randomCard = compareBy<SyncCard> { stableQueueKey(it.cardId.toString(), seed) }.thenBy { it.cardId }
    return when (order) {
        NewCardGatherOrder.Deck -> compareBy<SyncCard> { it.deckName.lowercase() }.then(position)
        NewCardGatherOrder.DeckThenRandomNotes ->
            compareBy<SyncCard> { it.deckName.lowercase() }.then(randomNote)
        NewCardGatherOrder.LowestPosition -> position
        NewCardGatherOrder.HighestPosition -> reversePosition
        NewCardGatherOrder.RandomNotes -> randomNote
        NewCardGatherOrder.RandomCards -> randomCard
    }
}

private fun reviewComparator(
    order: ReviewSortOrder,
    schedules: Map<Long, LocalCardSchedule>,
    dueDateOverrides: Map<Long, Long>,
    nowMillis: Long,
    studyDayPolicy: AccountStudyDayPolicy,
): Comparator<SyncCard> {
    fun SyncCard.schedule() = schedules.getValue(cardId)
    fun SyncCard.dueAt() = dueDateOverrides[cardId] ?: schedule().dueAtMillis
    val seed = studyDayAt(nowMillis, studyDayPolicy)
    val dueThenId = compareBy<SyncCard> { it.dueAt() }.thenBy { it.cardId }
    return when (order) {
        ReviewSortOrder.DueDateThenRandom -> compareBy<SyncCard> { it.dueAt() }
            .thenBy { stableQueueKey(it.cardId.toString(), seed) }.thenBy { it.cardId }
        ReviewSortOrder.DueDateThenDeck -> compareBy<SyncCard> { it.dueAt() }
            .thenBy { it.deckName.lowercase() }.thenBy { it.cardId }
        ReviewSortOrder.DeckThenDueDate -> compareBy<SyncCard> { it.deckName.lowercase() }.then(dueThenId)
        ReviewSortOrder.IntervalAscending -> compareBy<SyncCard> { it.schedule().scheduledDays }.then(dueThenId)
        ReviewSortOrder.IntervalDescending ->
            compareByDescending<SyncCard> { it.schedule().scheduledDays }.then(dueThenId)
        ReviewSortOrder.DifficultyAscending -> compareBy<SyncCard> { it.schedule().difficulty }.then(dueThenId)
        ReviewSortOrder.DifficultyDescending ->
            compareByDescending<SyncCard> { it.schedule().difficulty }.then(dueThenId)
        ReviewSortOrder.RetrievabilityAscending ->
            compareBy<SyncCard> { it.schedule().retrievability(nowMillis) }.then(dueThenId)
        ReviewSortOrder.RetrievabilityDescending ->
            compareByDescending<SyncCard> { it.schedule().retrievability(nowMillis) }.then(dueThenId)
        ReviewSortOrder.RelativeOverdueness ->
            compareByDescending<SyncCard> { it.schedule().relativeOverdueness(nowMillis) }.then(dueThenId)
        ReviewSortOrder.Random -> compareBy<SyncCard> {
            stableQueueKey(it.cardId.toString(), seed)
        }.thenBy { it.cardId }
        ReviewSortOrder.Added -> compareBy<SyncCard> { it.createdAtMillis(nowMillis) == null }
            .thenBy { it.createdAtMillis(nowMillis) }
            .thenBy(SyncCard::cardId)
        ReviewSortOrder.LatestAddedFirst -> compareBy<SyncCard> { it.createdAtMillis(nowMillis) == null }
            .thenByDescending { it.createdAtMillis(nowMillis) }
            .thenByDescending(SyncCard::cardId)
    }
}

private fun <T> placeAroundReviews(reviews: List<T>, inserted: List<T>, order: QueueMixOrder): List<T> = when (order) {
    QueueMixOrder.BeforeReviews -> inserted + reviews
    QueueMixOrder.AfterReviews -> reviews + inserted
    QueueMixOrder.MixWithReviews -> balancedMix(reviews, inserted)
}

private fun <T> balancedMix(first: List<T>, second: List<T>): List<T> = buildList(first.size + second.size) {
    var firstIndex = 0
    var secondIndex = 0
    while (firstIndex < first.size || secondIndex < second.size) {
        val takeFirst = when {
            firstIndex == first.size -> false
            secondIndex == second.size -> true
            else -> (firstIndex + 1L) * second.size <= (secondIndex + 1L) * first.size
        }
        if (takeFirst) add(first[firstIndex++]) else add(second[secondIndex++])
    }
}

private fun LocalCardSchedule.isInterdayLearning(
    dueDateOverride: Long?,
    studyDayPolicy: AccountStudyDayPolicy,
): Boolean = studyDayAt(dueDateOverride ?: dueAtMillis, studyDayPolicy) >
    studyDayAt(lastReviewAtMillis, studyDayPolicy)

private fun LocalCardSchedule.retrievability(nowMillis: Long): Double {
    if (stability <= 0.0) return 0.0
    val elapsedDays = (nowMillis - lastReviewAtMillis).coerceAtLeast(0L) / MillisPerDay.toDouble()
    return (1.0 + (19.0 / 81.0) * elapsedDays / stability).pow(-0.5)
}

private fun LocalCardSchedule.relativeOverdueness(nowMillis: Long): Double {
    val elapsedDays = (nowMillis - lastReviewAtMillis).coerceAtLeast(0L) / MillisPerDay.toDouble()
    return elapsedDays / scheduledDays.coerceAtLeast(1)
}

private fun stableQueueKey(value: String, seed: Long): Long {
    var hash = -3_750_763_034_362_895_579L xor seed
    value.forEach { char -> hash = (hash xor char.code.toLong()) * 1_099_511_628_211L }
    hash = (hash xor (hash ushr 30)) * -4_658_895_280_553_007_687L
    hash = (hash xor (hash ushr 27)) * -7_723_592_293_110_705_685L
    return hash xor (hash ushr 31)
}
