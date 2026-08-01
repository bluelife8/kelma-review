package tech.kelma.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class SchedulerOptimizerJobStatus {
    Running,
    Completed,
    Ineligible,
    Cancelled,
    Interrupted,
    Failed,
}

@Serializable
enum class SchedulerOptimizerCandidateStatus {
    Pending,
    Applied,
    Discarded,
    Stale,
}

@Serializable
data class SchedulerOptimizerMetrics(
    val trainingExamples: Int,
    val validationExamples: Int,
    val trainingLossBefore: Double,
    val trainingLossAfter: Double,
    val validationLossBefore: Double,
    val validationLossAfter: Double,
)

@Serializable
data class SchedulerOptimizerCandidatePayload(
    val parameters: List<Double>,
    val previousParameters: List<Double>,
    val initializedParameters: List<Double>,
    val metrics: SchedulerOptimizerMetrics,
    val localHistorySha256: String,
    val datasetSha256: String,
    val serverHistorySha256: String? = null,
    val throughReviewId: Long,
    val rawReviews: Int,
    val qualifyingReviews: Int,
    val qualifyingCards: Int,
    val recalledOutcomes: Int,
    val forgottenOutcomes: Int,
    val timezoneId: String,
    val dayStartHour: Int,
    val optimizerVersion: String,
) {
    fun qualityMetrics() = buildJsonObject {
        put("training_examples", metrics.trainingExamples)
        put("validation_examples", metrics.validationExamples)
        put("training_loss_before", metrics.trainingLossBefore)
        put("training_loss_after", metrics.trainingLossAfter)
        put("validation_loss_before", metrics.validationLossBefore)
        put("validation_loss_after", metrics.validationLossAfter)
        put("qualifying_reviews", qualifyingReviews)
        put("qualifying_cards", qualifyingCards)
        put("recalled_outcomes", recalledOutcomes)
        put("forgotten_outcomes", forgottenOutcomes)
        put("dataset_sha256", datasetSha256)
        put("timezone", timezoneId)
        put("day_start_hour", dayStartHour)
    }
}

data class SchedulerOptimizerJob(
    val jobId: String,
    val status: SchedulerOptimizerJobStatus,
    val timezoneId: String,
    val dayStartHour: Int,
    val totalEpochs: Int,
    val completedEpochs: Int,
    val rawReviewCount: Int,
    val qualifyingReviewCount: Int,
    val qualifyingCardCount: Int,
    val historySha256: String?,
    val datasetSha256: String?,
    val throughReviewId: Long?,
    val reasonCode: String?,
    val cancelRequested: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val completedAtMillis: Long?,
)

data class SchedulerOptimizerCandidate(
    val candidateId: String,
    val jobId: String,
    val payload: SchedulerOptimizerCandidatePayload,
    val status: SchedulerOptimizerCandidateStatus,
    val createdAtMillis: Long,
    val resolvedAtMillis: Long?,
)

data class SchedulerOptimizerState(
    val job: SchedulerOptimizerJob? = null,
    val candidate: SchedulerOptimizerCandidate? = null,
) {
    val running: Boolean get() = job?.status == SchedulerOptimizerJobStatus.Running
    val pendingCandidate: SchedulerOptimizerCandidate?
        get() = candidate?.takeIf { it.status == SchedulerOptimizerCandidateStatus.Pending }
}
