package tech.kelma.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

interface SchedulingEngine {
    fun review(
        card: SyncCard,
        previous: LocalCardSchedule?,
        rating: Rating,
        reviewedAtMillis: Long,
        serverLastReviewAtMillis: Long? = null,
        options: DeckOptions = DeckOptions(),
    ): LocalCardSchedule
}

/** Preserved only for explicitly customized 19-parameter legacy profiles. */
internal object LegacyFsrs5Scheduler : SchedulingEngine {
    private const val Decay = -0.5
    private const val ForgettingCurveFactor = 19.0 / 81.0
    private const val MinimumStability = 0.01
    private const val MinuteMillis = 60_000L

    override fun review(
        card: SyncCard,
        previous: LocalCardSchedule?,
        rating: Rating,
        reviewedAtMillis: Long,
        serverLastReviewAtMillis: Long?,
        options: DeckOptions,
    ): LocalCardSchedule {
        val validatedOptions = options.validated()
        val weights = validatedOptions.fsrsParameters
        val current = previous ?: card.seedSchedule(reviewedAtMillis, serverLastReviewAtMillis, validatedOptions)
        val firstReview = previous == null && current.repetitions == 0
        val elapsedDays = if (current.repetitions == 0) {
            0.0
        } else {
            max(0.0, (reviewedAtMillis - current.lastReviewAtMillis).toDouble() / MillisPerDay)
        }
        val retrievability = retrievability(current.stability, elapsedDays)
        val difficulty = if (firstReview) {
            initialDifficulty(rating, weights)
        } else {
            nextDifficulty(current.difficulty, rating, weights)
        }
        fun stabilityFor(candidate: Rating): Double = when {
            firstReview -> initialStability(candidate, weights)
            elapsedDays == 0.0 || current.phase != ReviewPhase.Review ->
                nextShortTermStability(current.stability, candidate, weights)
            candidate == Rating.Again -> nextForgetStability(current, current.difficulty, retrievability, weights)
            else -> nextRecallStability(current, current.difficulty, retrievability, candidate, weights)
        }.coerceAtLeast(MinimumStability)

        val stability = stabilityFor(rating)
        val interval = intervalFor(current, rating, stability, firstReview, validatedOptions, ::stabilityFor)
        val lapse = !firstReview && rating == Rating.Again && current.phase == ReviewPhase.Review
        return LocalCardSchedule(
            cardId = card.cardId,
            phase = interval.phase,
            dueAtMillis = reviewedAtMillis.safelyPlus(interval.millis),
            stability = stability,
            difficulty = difficulty,
            scheduledDays = interval.days,
            repetitions = current.repetitions + 1,
            lapses = current.lapses + if (lapse) 1 else 0,
            lastReviewAtMillis = reviewedAtMillis,
        )
    }

    private fun intervalFor(
        current: LocalCardSchedule,
        rating: Rating,
        stability: Double,
        firstReview: Boolean,
        options: DeckOptions,
        stabilityFor: (Rating) -> Double,
    ): ScheduledInterval {
        if (rating == Rating.Again) {
            val minutes = if (firstReview) {
                options.learningStepsMinutes.firstOrNull() ?: 1
            } else {
                options.relearningStepsMinutes.firstOrNull() ?: 5
            }
            val phase = when {
                firstReview -> ReviewPhase.Learning
                current.phase == ReviewPhase.Review || current.phase == ReviewPhase.Relearning ->
                    ReviewPhase.Relearning
                else -> ReviewPhase.Learning
            }
            return ScheduledInterval(phase, minutes * MinuteMillis, 0)
        }
        if (firstReview && (rating == Rating.Hard || rating == Rating.Good)) {
            val first = options.learningStepsMinutes.firstOrNull() ?: 1
            val last = options.learningStepsMinutes.lastOrNull() ?: 10
            val minutes = if (rating == Rating.Hard) ((first + last) / 2).coerceAtLeast(1) else last
            return ScheduledInterval(ReviewPhase.Learning, minutes * MinuteMillis, 0)
        }
        if (current.phase != ReviewPhase.Review && rating == Rating.Hard) {
            val minutes = options.learningStepsMinutes.lastOrNull() ?: 10
            return ScheduledInterval(current.phase.learningEquivalent(), minutes * MinuteMillis, 0)
        }

        val rawDays = intervalDays(stability, options)
        val days = when {
            firstReview -> rawDays
            current.phase != ReviewPhase.Review && rating == Rating.Good -> rawDays
            current.phase != ReviewPhase.Review -> {
                val goodDays = intervalDays(stabilityFor(Rating.Good), options)
                max(rawDays, goodDays + 1)
            }
            else -> normalizedReviewInterval(rating, rawDays, options, stabilityFor)
        }
        return ScheduledInterval(ReviewPhase.Review, days * MillisPerDay, days)
    }

    private fun normalizedReviewInterval(
        rating: Rating,
        selectedRawDays: Int,
        options: DeckOptions,
        stabilityFor: (Rating) -> Double,
    ): Int {
        val hardRaw = if (rating == Rating.Hard) selectedRawDays else intervalDays(stabilityFor(Rating.Hard), options)
        val goodRaw = if (rating == Rating.Good) selectedRawDays else intervalDays(stabilityFor(Rating.Good), options)
        val hard = min(hardRaw, goodRaw)
        val good = max(goodRaw, hard + 1).coerceAtMost(options.maximumIntervalDays)
        val easyRaw = if (rating == Rating.Easy) selectedRawDays else intervalDays(stabilityFor(Rating.Easy), options)
        val easy = max(easyRaw, good + 1).coerceAtMost(options.maximumIntervalDays)
        return when (rating) {
            Rating.Hard -> hard
            Rating.Good -> good
            Rating.Easy -> easy
            Rating.Again -> 0
        }
    }

    private fun initialStability(rating: Rating, weights: List<Double>): Double = weights[rating.index]

    private fun initialDifficulty(rating: Rating, weights: List<Double>): Double =
        (weights[4] - exp(weights[5] * (rating.index)) + 1.0).coerceIn(1.0, 10.0)

    private fun nextDifficulty(current: Double, rating: Rating, weights: List<Double>): Double {
        val delta = -weights[6] * (rating.value - 3)
        val dampedDelta = delta * (10.0 - current) / 9.0
        val changed = current + dampedDelta
        val reverted = weights[7] * initialDifficulty(Rating.Easy, weights) + (1.0 - weights[7]) * changed
        return reverted.coerceIn(1.0, 10.0)
    }

    private fun nextRecallStability(
        current: LocalCardSchedule,
        difficulty: Double,
        retrievability: Double,
        rating: Rating,
        weights: List<Double>,
    ): Double {
        val hardPenalty = if (rating == Rating.Hard) weights[15] else 1.0
        val easyBonus = if (rating == Rating.Easy) weights[16] else 1.0
        val growth = exp(weights[8]) *
            (11.0 - difficulty) *
            current.stability.pow(-weights[9]) *
            (exp((1.0 - retrievability) * weights[10]) - 1.0) *
            hardPenalty * easyBonus
        return current.stability * (1.0 + growth)
    }

    private fun nextShortTermStability(stability: Double, rating: Rating, weights: List<Double>): Double =
        stability * exp(weights[17] * (rating.value - 3 + weights[18]))

    private fun nextForgetStability(
        current: LocalCardSchedule,
        difficulty: Double,
        retrievability: Double,
        weights: List<Double>,
    ): Double {
        val forgotten = weights[11] *
            difficulty.pow(-weights[12]) *
            ((current.stability + 1.0).pow(weights[13]) - 1.0) *
            exp((1.0 - retrievability) * weights[14])
        val maximum = current.stability / exp(weights[17] * weights[18])
        return forgotten.coerceIn(MinimumStability, maximum)
    }

    private fun retrievability(stability: Double, elapsedDays: Double): Double =
        (1.0 + ForgettingCurveFactor * elapsedDays / stability.coerceAtLeast(MinimumStability)).pow(Decay)

    private fun intervalDays(stability: Double, options: DeckOptions): Int {
        val days = stability / ForgettingCurveFactor *
            (options.desiredRetention.pow(1.0 / Decay) - 1.0)
        return days.roundToInt().coerceIn(1, options.maximumIntervalDays)
    }

    private fun SyncCard.seedSchedule(
        nowMillis: Long,
        serverLastReviewAtMillis: Long?,
        options: DeckOptions,
    ): LocalCardSchedule {
        val queue = scheduling.int("queue") ?: 0
        val interval = (scheduling.int("ivl") ?: 0).coerceAtLeast(0)
        val repetitions = (scheduling.int("reps")
            ?: if (queue != 0 || interval > 0) 1 else 0).coerceAtLeast(0)
        val lapses = (scheduling.int("lapses") ?: 0).coerceAtLeast(0)
        val fsrsData = scheduling.fsrsData()
        val stability = (scheduling.double("stability")
            ?: fsrsData?.double("s")
            ?: interval.takeIf { it > 0 }?.toDouble()
            ?: MinimumStability).coerceAtLeast(MinimumStability)
        val factor = scheduling.int("factor") ?: 2_500
        val difficulty = (scheduling.double("difficulty")
            ?: fsrsData?.double("d")
            ?: (5.0 + (2_500 - factor) / 500.0)).coerceIn(1.0, 10.0)
        val phase = when (queue) {
            1 -> ReviewPhase.Learning
            3 -> ReviewPhase.Relearning
            else -> ReviewPhase.Review
        }
        return LocalCardSchedule(
            cardId = cardId,
            phase = phase,
            dueAtMillis = nowMillis,
            stability = stability,
            difficulty = difficulty,
            scheduledDays = interval,
            repetitions = repetitions,
            lapses = lapses,
            lastReviewAtMillis = serverLastReviewAtMillis
                ?.takeIf { it in 1..nowMillis }
                ?: nowMillis - interval.toLong().coerceAtMost(options.maximumIntervalDays.toLong()) * MillisPerDay,
        )
    }
}

private data class ScheduledInterval(
    val phase: ReviewPhase,
    val millis: Long,
    val days: Int,
)

private val Rating.value: Int
    get() = ordinal + 1

private val Rating.index: Int
    get() = ordinal

private fun ReviewPhase.learningEquivalent(): ReviewPhase =
    if (this == ReviewPhase.Relearning) ReviewPhase.Relearning else ReviewPhase.Learning

private fun JsonObject.int(name: String): Int? = get(name)?.jsonPrimitive?.intOrNull

private fun JsonObject.double(name: String): Double? = get(name)?.jsonPrimitive?.doubleOrNull

private fun JsonObject.fsrsData(): JsonObject? {
    val element = get("data") ?: return null
    return runCatching { element.jsonObject }.getOrNull()
        ?: runCatching { Json.parseToJsonElement(element.jsonPrimitive.content).jsonObject }.getOrNull()
}

private fun Long.safelyPlus(other: Long): Long =
    if (other > 0 && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
