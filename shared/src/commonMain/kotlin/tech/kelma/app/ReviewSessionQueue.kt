package tech.kelma.app

@ConsistentCopyVisibility
data class TimedLearningEntry internal constructor(
    val card: ReviewCard,
    val dueAtMillis: Long,
    val phase: ReviewPhase,
    val step: Int?,
    internal val sequence: Long,
) {
    init {
        require(phase == ReviewPhase.Learning || phase == ReviewPhase.Relearning) {
            "A timed learning entry requires a learning phase"
        }
    }
}

@ConsistentCopyVisibility
data class ReviewSession private constructor(
    val currentCard: ReviewCard?,
    val regularQueue: List<ReviewCard>,
    val learningQueue: List<TimedLearningEntry>,
    private val reviewedCards: List<ReviewCard>,
    private val currentLearningEntry: TimedLearningEntry?,
    private val nextLearningSequence: Long,
    private val studyDayPolicy: AccountStudyDayPolicy,
    val showingAnswer: Boolean,
    val lastRating: Rating?,
) {
    constructor(cards: List<ReviewCard>) : this(
        currentCard = cards.firstOrNull(),
        regularQueue = cards.drop(1),
        learningQueue = emptyList(),
        reviewedCards = emptyList(),
        currentLearningEntry = null,
        nextLearningSequence = 0L,
        studyDayPolicy = AccountStudyDayPolicy(dayStartHour = 0),
        showingAnswer = false,
        lastRating = null,
    ) {
        require(cards.isNotEmpty()) { "A review session requires at least one card" }
    }

    init {
        require(currentLearningEntry == null || currentLearningEntry.card.id == currentCard?.id) {
            "The current learning entry must describe the current card"
        }
        val activeIds = listOfNotNull(currentCard?.id) + regularQueue.map(ReviewCard::id) +
            learningQueue.map { it.card.id }
        require(activeIds.size == activeIds.distinct().size) { "The active review queues contain duplicate cards" }
    }

    val cards: List<ReviewCard>
        get() = reviewedCards + listOfNotNull(currentCard) + regularQueue + learningQueue.map { it.card }

    val currentIndex: Int
        get() = reviewedCards.size

    val reviewedCount: Int
        get() = reviewedCards.size

    val previousReviewedCard: ReviewCard?
        get() = reviewedCards.lastOrNull()

    val nextQueuedCard: ReviewCard?
        get() = regularQueue.firstOrNull() ?: learningQueue.firstOrNull()?.card

    val nextLearningDueAtMillis: Long?
        get() = learningQueue.firstOrNull()?.dueAtMillis

    val isWaiting: Boolean
        get() = currentCard == null && regularQueue.isEmpty() && learningQueue.isNotEmpty()

    val isComplete: Boolean
        get() = currentCard == null && regularQueue.isEmpty() && learningQueue.isEmpty()

    fun revealAnswer(): ReviewSession {
        check(currentCard != null) { "Cannot reveal a session without a current card" }
        return copy(showingAnswer = true)
    }

    fun answer(rating: Rating): ReviewSession = completedCurrent(rating).selectNext(nowMillis = null)

    fun answer(
        rating: Rating,
        schedule: LocalCardSchedule,
        nowMillis: Long,
        studyDayCutoffMillis: Long = nextStudyDayStart(schedule.lastReviewAtMillis, studyDayPolicy),
    ): ReviewSession {
        val answeredCard = checkNotNull(currentCard)
        require(schedule.cardId == answeredCard.id) { "The committed schedule belongs to another card" }
        var updated = completedCurrent(rating).removeLearningCard(answeredCard.id)
        if (schedule.isLearningPhase() && schedule.dueAtMillis < studyDayCutoffMillis) {
            updated = updated.putLearningCard(answeredCard, schedule)
        }
        return updated.selectNext(nowMillis)
    }

    fun advanceTime(nowMillis: Long): ReviewSession = selectNext(nowMillis)

    fun refreshCards(latestCards: List<ReviewCard>): ReviewSession {
        val latest = latestCards.associateBy(ReviewCard::id)
        return mapCards { latest[it.id] ?: it }
    }

    fun reconcileLearningQueue(
        latestCards: List<ReviewCard>,
        schedules: Map<Long, LocalCardSchedule>,
        nowMillis: Long,
        dueDateOverrides: Map<Long, Long> = emptyMap(),
    ): ReviewSession {
        var updated = refreshCards(latestCards)
        val activeIds = updated.activeCardIds()
        latestCards.forEach { card ->
            val schedule = schedules[card.id]
                ?.withDueDateOverride(dueDateOverrides[card.id])
                ?: return@forEach
            if (card.id !in activeIds && schedule.isIntradayLearning(studyDayPolicy)) {
                updated = updated.putLearningCard(card, schedule)
            }
        }
        return updated.selectNext(nowMillis)
    }

    fun mapCards(transform: (ReviewCard) -> ReviewCard): ReviewSession = copy(
        currentCard = currentCard?.let(transform),
        regularQueue = regularQueue.map(transform),
        learningQueue = learningQueue.map { it.copy(card = transform(it.card)) },
        reviewedCards = reviewedCards.map(transform),
        currentLearningEntry = currentLearningEntry?.let { it.copy(card = transform(it.card)) },
    )

    fun buryCurrentCard(nowMillis: Long? = null): ReviewSession {
        val cardId = currentCard?.id ?: return this
        return copy(
            currentCard = null,
            currentLearningEntry = null,
            learningQueue = learningQueue.filterNot { it.card.id == cardId },
            showingAnswer = false,
        ).selectNext(nowMillis)
    }

    fun buryCurrentNote(nowMillis: Long? = null): ReviewSession {
        val noteGuid = currentCard?.noteGuid.orEmpty()
        if (noteGuid.isEmpty()) return buryCurrentCard(nowMillis)
        return copy(
            currentCard = null,
            currentLearningEntry = null,
            regularQueue = regularQueue.filterNot { it.noteGuid == noteGuid },
            learningQueue = learningQueue.filterNot { it.card.noteGuid == noteGuid },
            showingAnswer = false,
        ).selectNext(nowMillis)
    }

    fun restoreLastAnswer(card: ReviewCard): ReviewSession {
        var restoredRegular = regularQueue
        var restoredLearning = learningQueue
        currentCard?.let { displaced ->
            if (currentLearningEntry != null) {
                restoredLearning = sortedLearning(restoredLearning + currentLearningEntry)
            } else {
                restoredRegular = listOf(displaced) + restoredRegular
            }
        }
        restoredRegular = restoredRegular.filterNot { it.id == card.id }
        restoredLearning = restoredLearning.filterNot { it.card.id == card.id }
        val reviewedIndex = reviewedCards.indexOfLast { it.id == card.id }
        val displayCard = reviewedCards.getOrNull(reviewedIndex) ?: card
        val retainedReviews = if (reviewedIndex < 0) {
            reviewedCards
        } else {
            reviewedCards.filterIndexed { index, _ -> index != reviewedIndex }
        }
        return copy(
            currentCard = displayCard,
            regularQueue = restoredRegular,
            learningQueue = restoredLearning,
            reviewedCards = retainedReviews,
            currentLearningEntry = null,
            showingAnswer = true,
            lastRating = null,
        )
    }

    fun applyShortcut(shortcut: ReviewShortcut): ReviewSession {
        if (currentCard == null) return this
        if (!showingAnswer) return if (shortcut == ReviewShortcut.Space) revealAnswer() else this
        val rating = when (shortcut) {
            ReviewShortcut.Space, ReviewShortcut.Three -> Rating.Good
            ReviewShortcut.One -> Rating.Again
            ReviewShortcut.Two -> Rating.Hard
            ReviewShortcut.Four -> Rating.Easy
        }
        return answer(rating)
    }

    private fun completedCurrent(rating: Rating): ReviewSession {
        val card = checkNotNull(currentCard)
        check(showingAnswer) { "Reveal the current answer before rating" }
        return copy(
            currentCard = null,
            currentLearningEntry = null,
            reviewedCards = reviewedCards + card,
            showingAnswer = false,
            lastRating = rating,
        )
    }

    private fun putLearningCard(card: ReviewCard, schedule: LocalCardSchedule): ReviewSession {
        val existing = learningQueue.firstOrNull { it.card.id == card.id }
        val sequence = existing?.sequence ?: nextLearningSequence
        val entry = TimedLearningEntry(card, schedule.dueAtMillis, schedule.phase, schedule.step, sequence)
        return copy(
            learningQueue = sortedLearning(learningQueue.filterNot { it.card.id == card.id } + entry),
            nextLearningSequence = if (existing == null) nextLearningSequence + 1L else nextLearningSequence,
        )
    }

    private fun removeLearningCard(cardId: Long): ReviewSession = copy(
        learningQueue = learningQueue.filterNot { it.card.id == cardId },
    )

    private fun selectNext(nowMillis: Long?): ReviewSession {
        if (currentCard != null) return this
        val dueLearning = nowMillis?.let { now -> learningQueue.firstOrNull { it.dueAtMillis <= now } }
        if (dueLearning != null) return activateLearning(dueLearning)
        regularQueue.firstOrNull()?.let { return activateRegular(it) }
        val aheadLearning = nowMillis?.let { now ->
            val cutoff = saturatedAdd(now, DefaultLearnAheadMillis)
            learningQueue.firstOrNull { it.dueAtMillis <= cutoff }
        }
        return if (aheadLearning == null) this else activateLearning(aheadLearning)
    }

    private fun activateRegular(card: ReviewCard): ReviewSession = copy(
        currentCard = card,
        regularQueue = regularQueue.drop(1),
        currentLearningEntry = null,
        showingAnswer = false,
    )

    private fun activateLearning(entry: TimedLearningEntry): ReviewSession = copy(
        currentCard = entry.card,
        learningQueue = learningQueue.filterNot { it.card.id == entry.card.id },
        currentLearningEntry = entry,
        showingAnswer = false,
    )

    private fun activeCardIds(): Set<Long> = buildSet {
        currentCard?.let { add(it.id) }
        regularQueue.forEach { add(it.id) }
        learningQueue.forEach { add(it.card.id) }
    }

    companion object {
        fun start(
            cards: List<ReviewCard>,
            schedules: Map<Long, LocalCardSchedule>,
            nowMillis: Long,
            dueDateOverrides: Map<Long, Long> = emptyMap(),
            studyDayPolicy: AccountStudyDayPolicy = AccountStudyDayPolicy(dayStartHour = 0),
        ): ReviewSession {
            require(cards.isNotEmpty()) { "A review session requires at least one card" }
            val timedCards = cards.mapIndexedNotNull { index, card ->
                val schedule = schedules[card.id]
                    ?.withDueDateOverride(dueDateOverrides[card.id])
                    ?.takeIf { it.isIntradayLearning(studyDayPolicy) }
                    ?: return@mapIndexedNotNull null
                TimedLearningEntry(card, schedule.dueAtMillis, schedule.phase, schedule.step, index.toLong())
            }
            val timedIds = timedCards.mapTo(mutableSetOf()) { it.card.id }
            val futureInterdayIds = cards.mapNotNullTo(mutableSetOf()) { card ->
                val schedule = schedules[card.id]
                    ?.withDueDateOverride(dueDateOverrides[card.id])
                    ?: return@mapNotNullTo null
                card.id.takeIf {
                    schedule.isLearningPhase() &&
                        !schedule.isIntradayLearning(studyDayPolicy) &&
                        schedule.dueAtMillis > nowMillis
                }
            }
            return ReviewSession(
                currentCard = null,
                regularQueue = cards.filterNot { it.id in timedIds || it.id in futureInterdayIds },
                learningQueue = sortedLearning(timedCards),
                reviewedCards = emptyList(),
                currentLearningEntry = null,
                nextLearningSequence = cards.size.toLong(),
                studyDayPolicy = studyDayPolicy,
                showingAnswer = false,
                lastRating = null,
            ).selectNext(nowMillis)
        }
    }
}

private fun LocalCardSchedule.withDueDateOverride(dueAtMillis: Long?): LocalCardSchedule =
    if (dueAtMillis == null) this else copy(dueAtMillis = dueAtMillis)

private fun LocalCardSchedule.isLearningPhase(): Boolean =
    phase == ReviewPhase.Learning || phase == ReviewPhase.Relearning

private fun LocalCardSchedule.isIntradayLearning(policy: AccountStudyDayPolicy): Boolean =
    isLearningPhase() && studyDayAt(dueAtMillis, policy) <= studyDayAt(lastReviewAtMillis, policy)

private fun sortedLearning(entries: List<TimedLearningEntry>): List<TimedLearningEntry> = entries.sortedWith(
    compareBy<TimedLearningEntry> { it.dueAtMillis }.thenBy { it.sequence }.thenBy { it.card.id },
)

private fun saturatedAdd(value: Long, increment: Long): Long =
    if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
