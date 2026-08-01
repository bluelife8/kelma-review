package tech.kelma.app

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaDatabase
import tech.kelma.fsrs.FsrsOptimizer
import tech.kelma.fsrs.OptimizationResult
import tech.kelma.fsrs.OptimizerCancellationSignal
import tech.kelma.fsrs.OptimizerDatasetFormatter
import tech.kelma.fsrs.OptimizerProgressListener
import tech.kelma.fsrs.OptimizerTrainingPolicy
import tech.kelma.fsrs.StudyDayPolicy

internal class SchedulerOptimizerPersistence(
    private val database: KelmaDatabase,
    private val json: Json,
    private val profiles: SchedulerProfilePersistence,
) {
    private val queries = database.kelmaQueries
    private val history = SchedulerOptimizerHistoryLoader(queries, json)

    fun load(recoverInterrupted: Boolean = false, nowMillis: Long = currentEpochMillis()): SchedulerOptimizerState {
        if (recoverInterrupted) queries.recoverInterruptedSchedulerOptimizerJobs(nowMillis)
        return SchedulerOptimizerState(
            job = loadLatestJob(),
            candidate = loadLatestCandidate(),
        )
    }

    fun prepare(
        timezoneId: String = TimeZone.currentSystemDefault().id,
        dayStartHour: Int = 4,
        nowMillis: Long = currentEpochMillis(),
    ): SchedulerOptimizerState {
        val policy = OptimizerTrainingPolicy()
        TimeZone.of(timezoneId)
        require(dayStartHour in 0..23) { "Study-day start hour must be in [0,23]" }
        database.transaction {
            require(loadLatestJob()?.status != SchedulerOptimizerJobStatus.Running) {
                "An optimizer job is already running"
            }
            queries.stalePendingSchedulerOptimizerCandidates(nowMillis)
            queries.insertSchedulerOptimizerJob(
                jobId = randomUuidString(),
                timezoneId = timezoneId,
                dayStartHour = dayStartHour.toLong(),
                totalEpochs = policy.epochs.toLong(),
                createdAt = nowMillis,
            )
        }
        return load()
    }

    fun run(jobId: String): SchedulerOptimizerState {
        val job = requireNotNull(loadJob(jobId)) { "Optimizer job no longer exists" }
        require(job.status == SchedulerOptimizerJobStatus.Running) { "Optimizer job is not running" }
        val policy = OptimizerTrainingPolicy()
        return try {
            val snapshot = history.load()
            val result = FsrsOptimizer(
                StudyDayPolicy(job.timezoneId, job.dayStartHour),
                policy,
            ).optimize(
                reviews = snapshot.reviews,
                cancellation = OptimizerCancellationSignal {
                    queries.selectSchedulerOptimizerCancellation(jobId).executeAsOneOrNull() == 1L
                },
                progress = OptimizerProgressListener { progress ->
                    queries.updateSchedulerOptimizerProgress(
                        completedEpochs = progress.completedEpochs.toLong(),
                        qualifyingReviews = progress.qualifyingReviews.toLong(),
                        updatedAt = currentEpochMillis(),
                        jobId = jobId,
                    )
                },
            )
            persistResult(job, snapshot, result, policy)
        } catch (_: Exception) {
            finishJob(
                jobId = jobId,
                status = SchedulerOptimizerJobStatus.Failed,
                rawReviews = 0,
                reasonCode = "optimizer_failed",
            )
            load()
        }
    }

    fun requestCancellation(jobId: String, nowMillis: Long = currentEpochMillis()): SchedulerOptimizerState {
        queries.requestSchedulerOptimizerCancellation(updatedAt = nowMillis, jobId = jobId)
        return load()
    }

    fun interrupt(jobId: String, nowMillis: Long = currentEpochMillis()): SchedulerOptimizerState {
        queries.interruptSchedulerOptimizerJob(interruptedAt = nowMillis, jobId = jobId)
        return load()
    }

    fun discardCandidate(
        candidateId: String,
        nowMillis: Long = currentEpochMillis(),
    ): SchedulerOptimizerState {
        queries.resolveSchedulerOptimizerCandidate(
            state = SchedulerOptimizerCandidateStatus.Discarded.stored(),
            resolvedAt = nowMillis,
            candidateId = candidateId,
        )
        return load()
    }

    fun applyCandidate(
        candidateId: String,
        publishToCloud: Boolean,
        nowMillis: Long = currentEpochMillis(),
    ): Pair<SchedulerOptimizerState, SchedulerProfileState> {
        val candidate = requireNotNull(loadCandidate(candidateId)) { "Optimizer candidate no longer exists" }
        require(candidate.status == SchedulerOptimizerCandidateStatus.Pending) {
            "Optimizer candidate is no longer available"
        }
        val currentHistory = history.load()
        val currentDataset = OptimizerDatasetFormatter(
            StudyDayPolicy(candidate.payload.timezoneId, candidate.payload.dayStartHour),
        ).format(currentHistory.reviews)
        require(
            currentDataset.historySha256 == candidate.payload.localHistorySha256 &&
                currentDataset.datasetSha256 == candidate.payload.datasetSha256 &&
                currentDataset.throughReviewId == candidate.payload.throughReviewId,
        ) { "Review history changed; run Optimize again" }
        if (publishToCloud) {
            require(currentHistory.publishable && currentHistory.serverHistorySha256 != null) {
                "Sync pending reviews before publishing this optimized profile"
            }
        }
        lateinit var profile: SchedulerProfileState
        database.transaction {
            val active = profiles.load().local.settings
            val optimized = active.copy(
                parameters = candidate.payload.parameters,
                parameterSource = SchedulerProfileSource.ClientOptimized,
                optimizer = "kelma-fsrs-v6",
                optimizerVersion = candidate.payload.optimizerVersion,
                optimizationHistoryHash = currentHistory.serverHistorySha256.orEmpty(),
                optimizationThroughReviewId = candidate.payload.throughReviewId,
                qualityMetrics = candidate.payload.qualityMetrics(),
            )
            profile = profiles.applyLocal(optimized, publishToCloud, nowMillis)
            queries.resolveSchedulerOptimizerCandidate(
                state = SchedulerOptimizerCandidateStatus.Applied.stored(),
                resolvedAt = nowMillis,
                candidateId = candidateId,
            )
        }
        return load() to profile
    }

    fun markHistoryChanged(nowMillis: Long = currentEpochMillis()) {
        queries.stalePendingSchedulerOptimizerCandidates(nowMillis)
    }

    fun clear() {
        queries.clearSchedulerOptimizerCandidates()
        queries.clearSchedulerOptimizerJobs()
    }

    private fun persistResult(
        job: SchedulerOptimizerJob,
        snapshot: SchedulerOptimizerHistory,
        result: OptimizationResult,
        policy: OptimizerTrainingPolicy,
    ): SchedulerOptimizerState {
        val nowMillis = currentEpochMillis()
        when (result) {
            is OptimizationResult.CandidateReady -> {
                val value = result.candidate
                val dataset = OptimizerDatasetFormatter(
                    StudyDayPolicy(job.timezoneId, job.dayStartHour),
                    policy,
                ).format(snapshot.reviews)
                val payload = SchedulerOptimizerCandidatePayload(
                    parameters = value.parameters,
                    previousParameters = profiles.load().local.settings.parameters,
                    initializedParameters = value.initializedParameters,
                    metrics = SchedulerOptimizerMetrics(
                        value.metrics.trainingExamples,
                        value.metrics.validationExamples,
                        value.metrics.trainingLossBefore,
                        value.metrics.trainingLossAfter,
                        value.metrics.validationLossBefore,
                        value.metrics.validationLossAfter,
                    ),
                    localHistorySha256 = value.historySha256,
                    datasetSha256 = value.datasetSha256,
                    serverHistorySha256 = snapshot.serverHistorySha256,
                    throughReviewId = requireNotNull(value.throughReviewId),
                    rawReviews = snapshot.reviews.size,
                    qualifyingReviews = value.qualifyingReviews,
                    qualifyingCards = value.qualifyingCards,
                    recalledOutcomes = dataset.eligibility.recalledOutcomes,
                    forgottenOutcomes = dataset.eligibility.forgottenOutcomes,
                    timezoneId = job.timezoneId,
                    dayStartHour = job.dayStartHour,
                    optimizerVersion = value.optimizerVersion,
                )
                database.transaction {
                    finishJobQueries(
                        jobId = job.jobId,
                        status = SchedulerOptimizerJobStatus.Completed,
                        completedEpochs = policy.epochs,
                        rawReviews = snapshot.reviews.size,
                        qualifyingReviews = value.qualifyingReviews,
                        qualifyingCards = value.qualifyingCards,
                        historySha256 = value.historySha256,
                        datasetSha256 = value.datasetSha256,
                        throughReviewId = value.throughReviewId,
                        reasonCode = null,
                        completedAt = nowMillis,
                    )
                    queries.insertSchedulerOptimizerCandidate(
                        candidateId = randomUuidString(),
                        jobId = job.jobId,
                        candidateJson = json.encodeToString(payload),
                        createdAt = nowMillis,
                    )
                }
            }
            is OptimizationResult.Ineligible -> finishJobQueries(
                jobId = job.jobId,
                status = SchedulerOptimizerJobStatus.Ineligible,
                completedEpochs = 0,
                rawReviews = snapshot.reviews.size,
                qualifyingReviews = result.eligibility.qualifyingReviews,
                qualifyingCards = result.eligibility.qualifyingCards,
                historySha256 = result.historySha256,
                datasetSha256 = result.datasetSha256,
                throughReviewId = snapshot.throughReviewId,
                reasonCode = result.eligibility.reason?.code,
                completedAt = nowMillis,
            )
            is OptimizationResult.Cancelled -> finishJobQueries(
                jobId = job.jobId,
                status = SchedulerOptimizerJobStatus.Cancelled,
                completedEpochs = loadJob(job.jobId)?.completedEpochs ?: 0,
                rawReviews = snapshot.reviews.size,
                qualifyingReviews = loadJob(job.jobId)?.qualifyingReviewCount ?: 0,
                qualifyingCards = 0,
                historySha256 = null,
                datasetSha256 = null,
                throughReviewId = snapshot.throughReviewId,
                reasonCode = result.checkpoint.name,
                completedAt = nowMillis,
            )
        }
        return load()
    }

    private fun finishJob(
        jobId: String,
        status: SchedulerOptimizerJobStatus,
        rawReviews: Int,
        reasonCode: String,
    ) {
        finishJobQueries(
            jobId, status, 0, rawReviews, 0, 0,
            null, null, null, reasonCode, currentEpochMillis(),
        )
    }

    private fun finishJobQueries(
        jobId: String,
        status: SchedulerOptimizerJobStatus,
        completedEpochs: Int,
        rawReviews: Int,
        qualifyingReviews: Int,
        qualifyingCards: Int,
        historySha256: String?,
        datasetSha256: String?,
        throughReviewId: Long?,
        reasonCode: String?,
        completedAt: Long,
    ) {
        queries.completeSchedulerOptimizerJob(
            status = status.stored(),
            completedEpochs = completedEpochs.toLong(),
            rawReviews = rawReviews.toLong(),
            qualifyingReviews = qualifyingReviews.toLong(),
            qualifyingCards = qualifyingCards.toLong(),
            historySha256 = historySha256,
            datasetSha256 = datasetSha256,
            throughReviewId = throughReviewId,
            reasonCode = reasonCode,
            completedAt = completedAt,
            jobId = jobId,
        )
    }

    private fun loadLatestJob(): SchedulerOptimizerJob? = queries.selectLatestSchedulerOptimizerJob(
        mapper = ::mapJob,
    ).executeAsOneOrNull()

    private fun loadJob(jobId: String): SchedulerOptimizerJob? =
        queries.selectSchedulerOptimizerJob(jobId, mapper = ::mapJob).executeAsOneOrNull()

    private fun loadLatestCandidate(): SchedulerOptimizerCandidate? =
        queries.selectLatestSchedulerOptimizerCandidate(mapper = ::mapCandidate).executeAsOneOrNull()

    private fun loadCandidate(candidateId: String): SchedulerOptimizerCandidate? =
        queries.selectSchedulerOptimizerCandidate(candidateId, mapper = ::mapCandidate).executeAsOneOrNull()

    private fun mapJob(
        jobId: String, status: String, timezoneId: String, dayStartHour: Long,
        totalEpochs: Long, completedEpochs: Long, rawReviews: Long,
        qualifyingReviews: Long, qualifyingCards: Long, historySha256: String?,
        datasetSha256: String?, throughReviewId: Long?, reasonCode: String?,
        cancelRequested: Long, createdAt: Long, updatedAt: Long, completedAt: Long?,
    ) = SchedulerOptimizerJob(
        jobId,
        SchedulerOptimizerJobStatus.entries.first { it.stored() == status },
        timezoneId,
        dayStartHour.toInt(),
        totalEpochs.toInt(),
        completedEpochs.toInt(),
        rawReviews.toInt(),
        qualifyingReviews.toInt(),
        qualifyingCards.toInt(),
        historySha256,
        datasetSha256,
        throughReviewId,
        reasonCode,
        cancelRequested == 1L,
        createdAt,
        updatedAt,
        completedAt,
    )

    private fun mapCandidate(
        candidateId: String, jobId: String, candidateJson: String, state: String,
        createdAt: Long, resolvedAt: Long?,
    ) = SchedulerOptimizerCandidate(
        candidateId,
        jobId,
        json.decodeFromString(candidateJson),
        SchedulerOptimizerCandidateStatus.entries.first { it.stored() == state },
        createdAt,
        resolvedAt,
    )
}

private fun SchedulerOptimizerJobStatus.stored(): String = name.lowercase()
private fun SchedulerOptimizerCandidateStatus.stored(): String = name.lowercase()
