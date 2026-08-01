package tech.kelma.app

import tech.kelma.fsrs.Card
import tech.kelma.fsrs.Rating as FsrsRating
import tech.kelma.fsrs.Scheduler
import tech.kelma.fsrs.SchedulerConfig
import tech.kelma.fsrs.State
import kotlin.time.Duration.Companion.seconds

/** Application adapter for the independent common-Kotlin FSRS-6 library. */
object FsrsScheduler : SchedulingEngine {
    override fun review(
        card: SyncCard,
        previous: LocalCardSchedule?,
        rating: Rating,
        reviewedAtMillis: Long,
        serverLastReviewAtMillis: Long?,
        options: DeckOptions,
    ): LocalCardSchedule {
        val validated = options.validated()
        if (validated.effectiveSchedulerAlgorithm == SchedulerAlgorithm.Fsrs5) {
            return LegacyFsrs5Scheduler.review(
                card,
                previous,
                rating,
                reviewedAtMillis,
                serverLastReviewAtMillis,
                validated,
            )
        }
        val scheduler = Scheduler(
            SchedulerConfig(
                parameters = validated.fsrsParameters,
                desiredRetention = validated.desiredRetention,
                learningSteps = validated.effectiveLearningStepsSeconds.map { it.seconds },
                relearningSteps = validated.effectiveRelearningStepsSeconds.map { it.seconds },
                maximumIntervalDays = validated.maximumIntervalDays,
                enableShortTerm = true,
                enableFuzzing = false,
            ),
        )
        val current = previous?.toFsrsCard() ?: Card.new(card.cardId, reviewedAtMillis)
        val next = scheduler.review(current, rating.toFsrsRating(), reviewedAtMillis)
        val phase = next.state.toReviewPhase()
        val lapse = previous?.phase == ReviewPhase.Review && rating == Rating.Again
        return LocalCardSchedule(
            cardId = card.cardId,
            phase = phase,
            step = next.step,
            dueAtMillis = next.dueAtMillis,
            stability = checkNotNull(next.stability),
            difficulty = checkNotNull(next.difficulty),
            scheduledDays = if (phase == ReviewPhase.Review) {
                ((next.dueAtMillis - reviewedAtMillis) / MillisPerDay)
                    .coerceIn(1L, validated.maximumIntervalDays.toLong())
                    .toInt()
            } else {
                0
            },
            repetitions = (previous?.repetitions ?: 0) + 1,
            lapses = (previous?.lapses ?: 0) + if (lapse) 1 else 0,
            lastReviewAtMillis = reviewedAtMillis,
        )
    }
}

private fun LocalCardSchedule.toFsrsCard(): Card = Card(
    id = cardId,
    state = when (phase) {
        ReviewPhase.Learning -> State.Learning
        ReviewPhase.Review -> State.Review
        ReviewPhase.Relearning -> State.Relearning
    },
    step = if (phase == ReviewPhase.Review) null else step ?: 0,
    stability = stability,
    difficulty = difficulty,
    dueAtMillis = dueAtMillis,
    lastReviewAtMillis = lastReviewAtMillis,
)

private fun Rating.toFsrsRating(): FsrsRating = when (this) {
    Rating.Again -> FsrsRating.Again
    Rating.Hard -> FsrsRating.Hard
    Rating.Good -> FsrsRating.Good
    Rating.Easy -> FsrsRating.Easy
}

private fun State.toReviewPhase(): ReviewPhase = when (this) {
    State.Learning -> ReviewPhase.Learning
    State.Review -> ReviewPhase.Review
    State.Relearning -> ReviewPhase.Relearning
}
