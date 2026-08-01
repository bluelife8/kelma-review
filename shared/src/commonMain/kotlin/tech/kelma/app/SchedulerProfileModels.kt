package tech.kelma.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import tech.kelma.fsrs.Scheduler
import tech.kelma.fsrs.SchedulerConfig
import kotlin.time.Duration.Companion.seconds

@Serializable
enum class SchedulerProfileSource {
    @SerialName("default")
    Default,

    @SerialName("client_optimized")
    ClientOptimized,

    @SerialName("manual")
    Manual,
}

@Serializable
data class SchedulerProfileSettings(
    val algorithm: SchedulerAlgorithm = SchedulerAlgorithm.Fsrs6,
    val parameters: List<Double> = DefaultFsrs6Parameters,
    val parameterSource: SchedulerProfileSource = SchedulerProfileSource.Default,
    val desiredRetention: Double = 0.90,
    val retentionSource: SchedulerProfileSource = SchedulerProfileSource.Default,
    val maximumInterval: Int = 36_500,
    val learningStepsSeconds: List<Int> = listOf(60, 600),
    val relearningStepsSeconds: List<Int> = listOf(600),
    val enableShortTerm: Boolean = true,
    val enableFuzzing: Boolean = false,
    val optimizer: String = "",
    val optimizerVersion: String = "",
    val optimizationHistoryHash: String = "",
    val optimizationThroughReviewId: Long? = null,
    val qualityMetrics: JsonObject = JsonObject(emptyMap()),
) {
    fun validated(forCloud: Boolean = false): SchedulerProfileSettings {
        require(algorithm == SchedulerAlgorithm.Fsrs6) {
            "Account scheduler profiles currently require FSRS-6"
        }
        require(desiredRetention in 0.70..0.99) {
            "Desired retention must be between 70% and 99%"
        }
        require(enableShortTerm && !enableFuzzing) {
            "Short-term scheduling must be enabled and fuzzing disabled"
        }
        Scheduler(
            SchedulerConfig(
                parameters = parameters,
                desiredRetention = desiredRetention,
                learningSteps = learningStepsSeconds.map { it.seconds },
                relearningSteps = relearningStepsSeconds.map { it.seconds },
                maximumIntervalDays = maximumInterval,
                enableShortTerm = enableShortTerm,
                enableFuzzing = enableFuzzing,
            ),
        )
        if (forCloud && (
                parameterSource == SchedulerProfileSource.ClientOptimized ||
                    retentionSource == SchedulerProfileSource.ClientOptimized
            )
        ) {
            require(
                optimizer.isNotBlank() && optimizerVersion.isNotBlank() &&
                    optimizationHistoryHash.length == 64 && optimizationThroughReviewId != null,
            ) { "Optimized profiles require optimizer and history provenance" }
        }
        return this
    }

    fun asDeckOptions(): DeckOptions = DeckOptions(
        desiredRetention = desiredRetention,
        fsrsParameters = parameters,
        schedulerAlgorithm = algorithm,
        maximumIntervalDays = maximumInterval,
        fsrsLearningStepsSeconds = learningStepsSeconds,
        fsrsRelearningStepsSeconds = relearningStepsSeconds,
    ).validated()

    companion object {
        fun fromDeckOptions(
            options: DeckOptions,
            parameterSource: SchedulerProfileSource = SchedulerProfileSource.Manual,
            retentionSource: SchedulerProfileSource = SchedulerProfileSource.Manual,
        ): SchedulerProfileSettings {
            val validated = options.validated()
            require(validated.effectiveSchedulerAlgorithm == SchedulerAlgorithm.Fsrs6) {
                "Legacy FSRS-5 settings cannot be published as FSRS-6"
            }
            return SchedulerProfileSettings(
                parameters = validated.fsrsParameters,
                parameterSource = parameterSource,
                desiredRetention = validated.desiredRetention,
                retentionSource = retentionSource,
                maximumInterval = validated.maximumIntervalDays,
                learningStepsSeconds = validated.effectiveLearningStepsSeconds,
                relearningStepsSeconds = validated.effectiveRelearningStepsSeconds,
            ).validated()
        }
    }
}

@Serializable
data class CloudSchedulerProfile(
    val version: Long = 0,
    val algorithm: String = "fsrs-6",
    @SerialName("scheduler_implementation") val schedulerImplementation: String = "kelma-go-fsrs",
    @SerialName("scheduler_version") val schedulerVersion: String = "",
    @SerialName("idempotency_key") val idempotencyKey: String = "",
    val parameters: List<Double> = DefaultFsrs6Parameters,
    @SerialName("parameter_source") val parameterSource: SchedulerProfileSource = SchedulerProfileSource.Default,
    @SerialName("desired_retention") val desiredRetention: Double = 0.90,
    @SerialName("retention_source") val retentionSource: SchedulerProfileSource = SchedulerProfileSource.Default,
    @SerialName("maximum_interval") val maximumInterval: Int = 36_500,
    @SerialName("learning_steps_seconds") val learningStepsSeconds: List<Int> = listOf(60, 600),
    @SerialName("relearning_steps_seconds") val relearningStepsSeconds: List<Int> = listOf(600),
    @SerialName("enable_short_term") val enableShortTerm: Boolean = true,
    @SerialName("enable_fuzzing") val enableFuzzing: Boolean = false,
    @SerialName("config_hash") val configHash: String = "",
    val optimizer: String = "",
    @SerialName("optimizer_version") val optimizerVersion: String = "",
    @SerialName("optimization_history_hash") val optimizationHistoryHash: String = "",
    @SerialName("optimization_through_review_id") val optimizationThroughReviewId: Long? = null,
    @SerialName("quality_metrics") val qualityMetrics: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String = "",
) {
    fun asSettings(): SchedulerProfileSettings {
        require(algorithm == "fsrs-6") { "KelmaSync returned an unsupported scheduler algorithm" }
        return SchedulerProfileSettings(
            parameters = parameters,
            parameterSource = parameterSource,
            desiredRetention = desiredRetention,
            retentionSource = retentionSource,
            maximumInterval = maximumInterval,
            learningStepsSeconds = learningStepsSeconds,
            relearningStepsSeconds = relearningStepsSeconds,
            enableShortTerm = enableShortTerm,
            enableFuzzing = enableFuzzing,
            optimizer = optimizer,
            optimizerVersion = optimizerVersion,
            optimizationHistoryHash = optimizationHistoryHash,
            optimizationThroughReviewId = optimizationThroughReviewId,
            qualityMetrics = qualityMetrics,
        ).validated()
    }
}

@Serializable
data class SchedulerProjectionSummary(
    val pending: Long = 0,
    val running: Long = 0,
    val succeeded: Long = 0,
    val failed: Long = 0,
    val superseded: Long = 0,
    val stale: Long = 0,
)

@Serializable
data class SchedulerProfileResponse(
    val profile: CloudSchedulerProfile = CloudSchedulerProfile(),
    val projections: SchedulerProjectionSummary = SchedulerProjectionSummary(),
)

@Serializable
data class SchedulerProfileCandidate(
    @SerialName("base_profile_version") val baseProfileVersion: Long,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val parameters: List<Double>,
    @SerialName("parameter_source") val parameterSource: SchedulerProfileSource,
    @SerialName("desired_retention") val desiredRetention: Double,
    @SerialName("retention_source") val retentionSource: SchedulerProfileSource,
    @SerialName("maximum_interval") val maximumInterval: Int,
    @SerialName("learning_steps_seconds") val learningStepsSeconds: List<Int>,
    @SerialName("relearning_steps_seconds") val relearningStepsSeconds: List<Int>,
    @SerialName("enable_short_term") val enableShortTerm: Boolean,
    @SerialName("enable_fuzzing") val enableFuzzing: Boolean,
    val optimizer: String = "",
    @SerialName("optimizer_version") val optimizerVersion: String = "",
    @SerialName("optimization_history_hash") val optimizationHistoryHash: String = "",
    @SerialName("optimization_through_review_id") val optimizationThroughReviewId: Long? = null,
    @SerialName("quality_metrics") val qualityMetrics: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SchedulerProfileConflictResponse(
    val error: String,
    val server: CloudSchedulerProfile,
    val client: SchedulerProfileCandidate,
)

data class LocalSchedulerProfile(
    val version: Long,
    val settings: SchedulerProfileSettings,
    val createdAtMillis: Long,
)

enum class SchedulerProfileSyncStatus {
    Current,
    Pending,
    AwaitingConfirmation,
    Conflict,
}

data class SchedulerProfileState(
    val local: LocalSchedulerProfile = LocalSchedulerProfile(0, SchedulerProfileSettings(), 0),
    val cloud: CloudSchedulerProfile? = null,
    val projections: SchedulerProjectionSummary = SchedulerProjectionSummary(),
    val syncStatus: SchedulerProfileSyncStatus = SchedulerProfileSyncStatus.Current,
    val desiredLocalVersion: Long? = null,
    val desiredCloudBaseVersion: Long? = null,
    val acknowledgedCloudVersion: Long? = null,
)

internal fun SchedulerProfileSettings.toCandidate(
    baseVersion: Long,
    idempotencyKey: String,
): SchedulerProfileCandidate = validated(forCloud = true).let { settings ->
    SchedulerProfileCandidate(
        baseProfileVersion = baseVersion,
        idempotencyKey = idempotencyKey,
        parameters = settings.parameters,
        parameterSource = settings.parameterSource,
        desiredRetention = settings.desiredRetention,
        retentionSource = settings.retentionSource,
        maximumInterval = settings.maximumInterval,
        learningStepsSeconds = settings.learningStepsSeconds,
        relearningStepsSeconds = settings.relearningStepsSeconds,
        enableShortTerm = settings.enableShortTerm,
        enableFuzzing = settings.enableFuzzing,
        optimizer = settings.optimizer,
        optimizerVersion = settings.optimizerVersion,
        optimizationHistoryHash = settings.optimizationHistoryHash,
        optimizationThroughReviewId = settings.optimizationThroughReviewId,
        qualityMetrics = settings.qualityMetrics,
    )
}
