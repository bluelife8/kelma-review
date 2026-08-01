package tech.kelma.app

import kotlinx.serialization.Serializable
import tech.kelma.fsrs.DefaultParameters

@Serializable
enum class NewCardGatherOrder(val label: String) {
    Deck("Deck"),
    DeckThenRandomNotes("Deck, then random notes"),
    LowestPosition("Ascending position"),
    HighestPosition("Descending position"),
    RandomNotes("Random notes"),
    RandomCards("Random cards"),
}

@Serializable
enum class NewCardSortOrder(val label: String) {
    TemplateThenGather("Card type, then gather order"),
    GatherOrder("Gather order"),
    TemplateThenRandom("Card type, then random"),
    RandomNoteThenTemplate("Random note, then card type"),
    RandomCard("Random cards"),
}

@Serializable
enum class QueueMixOrder(val label: String) {
    MixWithReviews("Mix with reviews"),
    AfterReviews("Show after reviews"),
    BeforeReviews("Show before reviews"),
}

@Serializable
enum class ReviewSortOrder(val label: String) {
    DueDateThenRandom("Due date, then random"),
    DueDateThenDeck("Due date, then deck"),
    DeckThenDueDate("Deck, then due date"),
    IntervalAscending("Ascending interval"),
    IntervalDescending("Descending interval"),
    DifficultyAscending("Ascending difficulty"),
    DifficultyDescending("Descending difficulty"),
    RetrievabilityAscending("Ascending retrievability"),
    RetrievabilityDescending("Descending retrievability"),
    RelativeOverdueness("Relative overdueness"),
    Random("Random"),
    Added("Order added"),
    LatestAddedFirst("Latest added first"),
}

@Serializable
enum class SchedulerAlgorithm {
    Fsrs5,
    Fsrs6,
    ;

    val label: String
        get() = if (this == Fsrs6) "FSRS-6" else "FSRS-5 (legacy)"

    val parameterCount: Int
        get() = if (this == Fsrs6) 21 else 19
}

val DefaultFsrs5Parameters: List<Double> = listOf(
    0.40255, 1.18385, 3.173, 15.69105,
    7.1949, 0.5345, 1.4604, 0.0046,
    1.54575, 0.1192, 1.01925, 1.9395,
    0.11, 0.29605, 2.2698, 0.2315,
    2.9898, 0.51655, 0.6621,
)

val DefaultFsrs6Parameters: List<Double> = DefaultParameters.toList()

@Serializable
data class DeckOptions(
    val newCardsPerDay: Int = 20,
    val maximumReviewsPerDay: Int = 200,
    val newCardsIgnoreReviewLimit: Boolean = false,
    val learningStepsMinutes: List<Int> = listOf(1, 10),
    val relearningStepsMinutes: List<Int> = listOf(10),
    val fsrsLearningStepsSeconds: List<Int>? = null,
    val fsrsRelearningStepsSeconds: List<Int>? = null,
    val autoplayAudio: Boolean = true,
    val maximumAnswerSeconds: Int = 60,
    val confirmBeforeUndo: Boolean = true,
    val desiredRetention: Double = 0.9,
    val fsrsParameters: List<Double> = DefaultFsrs6Parameters,
    val schedulerAlgorithm: SchedulerAlgorithm? = null,
    val maximumIntervalDays: Int = 36_500,
    val newCardGatherOrder: NewCardGatherOrder = NewCardGatherOrder.Deck,
    val newCardSortOrder: NewCardSortOrder = NewCardSortOrder.TemplateThenGather,
    val newReviewMixOrder: QueueMixOrder = QueueMixOrder.MixWithReviews,
    val interdayLearningMixOrder: QueueMixOrder = QueueMixOrder.MixWithReviews,
    val reviewSortOrder: ReviewSortOrder = ReviewSortOrder.DueDateThenRandom,
    val buryNewSiblings: Boolean = true,
    val buryReviewSiblings: Boolean = true,
    val buryInterdayLearningSiblings: Boolean = false,
) {
    val effectiveSchedulerAlgorithm: SchedulerAlgorithm
        get() = schedulerAlgorithm ?: when {
            fsrsParameters.size == 21 -> SchedulerAlgorithm.Fsrs6
            fsrsParameters == DefaultFsrs5Parameters -> SchedulerAlgorithm.Fsrs6
            else -> SchedulerAlgorithm.Fsrs5
        }

    val effectiveLearningStepsSeconds: List<Int>
        get() = fsrsLearningStepsSeconds ?: learningStepsMinutes.map { it * 60 }

    val effectiveRelearningStepsSeconds: List<Int>
        get() = fsrsRelearningStepsSeconds ?: relearningStepsMinutes.map { it * 60 }

    fun validated(): DeckOptions {
        val normalized = when {
            schedulerAlgorithm == null && fsrsParameters == DefaultFsrs5Parameters -> copy(
                fsrsParameters = DefaultFsrs6Parameters,
                schedulerAlgorithm = SchedulerAlgorithm.Fsrs6,
            )
            schedulerAlgorithm == null -> copy(schedulerAlgorithm = effectiveSchedulerAlgorithm)
            else -> this
        }
        require(normalized.newCardsPerDay in 0..9999) { "New cards/day must be between 0 and 9999" }
        require(normalized.maximumReviewsPerDay in 0..9999) { "Maximum reviews/day must be between 0 and 9999" }
        val isFsrs6 = normalized.effectiveSchedulerAlgorithm == SchedulerAlgorithm.Fsrs6
        if (isFsrs6) {
            require(
                normalized.effectiveLearningStepsSeconds.size <= 2 &&
                    normalized.effectiveLearningStepsSeconds.all { it in 1..86_399 } &&
                    normalized.effectiveLearningStepsSeconds.zipWithNext().all { (left, right) -> left < right },
            ) { "Enter up to two increasing learning steps shorter than one day" }
            require(
                normalized.effectiveRelearningStepsSeconds.size <= 1 &&
                    normalized.effectiveRelearningStepsSeconds.all { it in 1..86_399 },
            ) { "Enter at most one relearning step shorter than one day" }
        }
        val maximumStepMinutes = if (isFsrs6) 1_439 else 43_200
        val stepRangeDescription = if (isFsrs6) "shorter than one day" else "at most 30 days"
        require(
            normalized.learningStepsMinutes.size in 1..2 &&
                normalized.learningStepsMinutes.all { it in 1..maximumStepMinutes },
        ) { "Enter one or two learning steps $stepRangeDescription" }
        val validRelearningCount = if (isFsrs6) {
            normalized.relearningStepsMinutes.size <= 1 || normalized.fsrsRelearningStepsSeconds != null
        } else {
            normalized.relearningStepsMinutes.size == 1
        }
        require(
            validRelearningCount && normalized.relearningStepsMinutes.all { it in 1..maximumStepMinutes },
        ) { "Enter ${if (isFsrs6) "at most one" else "one"} relearning step $stepRangeDescription" }
        require(normalized.maximumAnswerSeconds in 1..7_200) {
            "Maximum answer time must be between 1 and 7200 seconds"
        }
        require(normalized.desiredRetention in 0.70..0.99) {
            "Desired retention must be between 70% and 99%"
        }
        val parameterCount = if (normalized.effectiveSchedulerAlgorithm == SchedulerAlgorithm.Fsrs6) 21 else 19
        require(normalized.fsrsParameters.size == parameterCount && normalized.fsrsParameters.all(Double::isFinite)) {
            "${normalized.effectiveSchedulerAlgorithm.name} requires exactly $parameterCount finite parameters"
        }
        require(normalized.maximumIntervalDays in 1..36_500) {
            "Maximum interval must be between 1 and 36500 days"
        }
        return normalized
    }
}

internal fun parseMinuteSteps(input: String): List<Int> {
    if (input.isBlank()) return emptyList()
    return input.trim().split(Regex("[,\\s]+"))
        .filter(String::isNotBlank)
        .map { token ->
            val match = Regex("""(\d+)([mhd]?)""", RegexOption.IGNORE_CASE).matchEntire(token)
                ?: throw IllegalArgumentException("Use steps such as 1m 10m 1h")
            val value = match.groupValues[1].toLong()
            val multiplier = when (match.groupValues[2].lowercase()) {
                "h" -> 60L
                "d" -> 1_440L
                else -> 1L
            }
            (value * multiplier).also { require(it in 1..43_200) { "A step must be between 1 minute and 30 days" } }.toInt()
        }
}

internal fun formatMinuteSteps(steps: List<Int>): String = steps.joinToString(" ") { "${it}m" }
