package tech.kelma.app

import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tech.kelma.fsrs.FsrsOptimizer
import tech.kelma.fsrs.OptimizationResult
import tech.kelma.fsrs.OptimizerDataset
import tech.kelma.fsrs.OptimizerDatasetFormatter
import tech.kelma.fsrs.OptimizerReview
import tech.kelma.fsrs.OptimizerReviewState
import tech.kelma.fsrs.Rating
import tech.kelma.db.KelmaDatabase
import tech.kelma.fsrs.StudyDayPolicy

/** Opt-in, read-only optimizer acceptance against local scheduling metadata. */
class RealCollectionFsrsOptimizerAcceptanceTest {
    @Test
    fun realHistoryMatchesDeterministicLocalOptimizerContract() {
        val sourcePath = System.getenv(CollectionEnvironment)
        if (sourcePath.isNullOrBlank()) {
            check(System.getenv(RequiredEnvironment) != "1") {
                "$CollectionEnvironment is required for real optimizer acceptance"
            }
            return
        }
        val source = File(sourcePath).canonicalFile
        require(source.isFile) { "Real optimizer acceptance source does not exist" }
        val sourceSize = source.length()
        val sourceModifiedAt = source.lastModified()
        val timezone = System.getenv(TimezoneEnvironment)?.takeIf(String::isNotBlank)
            ?: "America/New_York"
        val dayStartHour = System.getenv(DayStartEnvironment)?.toIntOrNull() ?: 4
        val studyPolicy = StudyDayPolicy(timezone, dayStartHour)

        lateinit var input: RealOptimizerInput
        lateinit var dataset: OptimizerDataset
        lateinit var first: OptimizationResult.CandidateReady
        lateinit var second: OptimizationResult.CandidateReady
        var persistentWorkflowMillis = 0L
        val loadMillis = measureTimeMillis { input = readOptimizerInput(source) }
        val formatMillis = measureTimeMillis {
            dataset = OptimizerDatasetFormatter(studyPolicy).format(input.reviews)
        }
        assertTrue(dataset.eligibility.eligible, "Real optimizer dataset must be eligible")
        val firstMillis = measureTimeMillis {
            first = FsrsOptimizer(studyPolicy).optimize(input.reviews) as OptimizationResult.CandidateReady
        }
        val repeatMillis = measureTimeMillis {
            second = FsrsOptimizer(studyPolicy).optimize(input.reviews) as OptimizationResult.CandidateReady
        }
        assertEquals(first, second, "Repeated real-history optimization changed its candidate")
        persistentWorkflowMillis = measureTimeMillis {
            validatePersistentOptimizerWorkflow(input, studyPolicy, first)
        }
        assertEquals(dataset.historySha256, first.candidate.historySha256)
        assertEquals(dataset.datasetSha256, first.candidate.datasetSha256)
        assertTrue(first.candidate.parameters.all(Double::isFinite))
        assertEquals(sourceSize, source.length(), "Acceptance must not resize the source database")
        assertEquals(sourceModifiedAt, source.lastModified(), "Acceptance must not modify the source database")

        writeReviewFixture(input.reviews)
        writeMachineResult(
            RealOptimizerMachineResult(
                schemaVersion = 1,
                timezone = timezone,
                dayStartHour = dayStartHour,
                sourceFingerprintSha256 = input.fingerprint,
                activeCards = input.activeCards,
                activeQualifyingReviews = input.reviews.size,
                historySha256 = dataset.historySha256,
                datasetSha256 = dataset.datasetSha256,
                qualifyingReviews = dataset.eligibility.qualifyingReviews,
                qualifyingCards = dataset.eligibility.qualifyingCards,
                recalledOutcomes = dataset.eligibility.recalledOutcomes,
                forgottenOutcomes = dataset.eligibility.forgottenOutcomes,
                initializedParameters = first.candidate.initializedParameters,
                parameters = first.candidate.parameters,
                trainingExamples = first.candidate.metrics.trainingExamples,
                validationExamples = first.candidate.metrics.validationExamples,
                trainingLossBefore = first.candidate.metrics.trainingLossBefore,
                trainingLossAfter = first.candidate.metrics.trainingLossAfter,
                validationLossBefore = first.candidate.metrics.validationLossBefore,
                validationLossAfter = first.candidate.metrics.validationLossAfter,
            ),
        )
        writeHumanReport(
            input = input,
            dataset = dataset,
            candidate = first,
            timezone = timezone,
            dayStartHour = dayStartHour,
            loadMillis = loadMillis,
            formatMillis = formatMillis,
            firstMillis = firstMillis,
            repeatMillis = repeatMillis,
            persistentWorkflowMillis = persistentWorkflowMillis,
        )
    }
}

private data class RealOptimizerInput(
    val reviews: List<OptimizerReview>,
    val activeCards: Int,
    val fingerprint: String,
)

@Serializable
private data class RealOptimizerMachineResult(
    val schemaVersion: Int,
    val timezone: String,
    val dayStartHour: Int,
    val sourceFingerprintSha256: String,
    val activeCards: Int,
    val activeQualifyingReviews: Int,
    val historySha256: String,
    val datasetSha256: String,
    val qualifyingReviews: Int,
    val qualifyingCards: Int,
    val recalledOutcomes: Int,
    val forgottenOutcomes: Int,
    val initializedParameters: List<Double>,
    val parameters: List<Double>,
    val trainingExamples: Int,
    val validationExamples: Int,
    val trainingLossBefore: Double,
    val trainingLossAfter: Double,
    val validationLossBefore: Double,
    val validationLossAfter: Double,
)

private fun readOptimizerInput(source: File): RealOptimizerInput {
    Class.forName("org.sqlite.JDBC")
    val reviews = ArrayList<OptimizerReview>(130_000)
    val digest = MessageDigest.getInstance("SHA-256")
    var activeCards = 0
    DriverManager.getConnection("jdbc:sqlite:file:${source.absolutePath}?mode=ro").use { connection ->
        connection.autoCommit = false
        connection.createStatement().use { statement -> statement.execute("PRAGMA query_only = ON") }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM cards").use { rows ->
                check(rows.next())
                activeCards = rows.getInt(1)
            }
        }
        connection.prepareStatement(
            """
            SELECT r.id, r.cid, r.ease, r.type, r.time
            FROM revlog r
            INNER JOIN cards c ON c.id = r.cid
            WHERE r.ease BETWEEN 1 AND 4
            ORDER BY r.id, r.cid
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val reviewId = rows.getLong(1)
                    val cardId = rows.getLong(2)
                    val rating = rows.getInt(3)
                    val kind = rows.getInt(4)
                    val duration = rows.getLong(5).coerceAtLeast(0L)
                    digest.updateLongValue(reviewId)
                    digest.updateLongValue(cardId)
                    digest.updateIntValue(rating)
                    digest.updateIntValue(kind)
                    digest.updateLongValue(duration)
                    reviews += OptimizerReview(
                        reviewId = reviewId,
                        cardIdentity = "card-$cardId",
                        reviewedAtMillis = reviewId,
                        rating = Rating.fromValue(rating),
                        state = when (kind) {
                            0 -> OptimizerReviewState.Learning
                            2 -> OptimizerReviewState.Relearning
                            else -> OptimizerReviewState.Review
                        },
                        durationMillis = duration,
                    )
                }
            }
        }
        connection.rollback()
    }
    require(reviews.isNotEmpty()) { "Acceptance source contains no active rating reviews" }
    return RealOptimizerInput(
        reviews = reviews,
        activeCards = activeCards,
        fingerprint = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
    )
}

private fun validatePersistentOptimizerWorkflow(
    input: RealOptimizerInput,
    studyPolicy: StudyDayPolicy,
    direct: OptimizationResult.CandidateReady,
) {
    val directory = kotlin.io.path.createTempDirectory("kelma-real-optimizer-store-").toFile()
    val databaseFile = directory.resolve("kelma.db")
    try {
        var driver = openDesktopDatabase(databaseFile)
        var store = PersistentCollectionStore(KelmaDatabase(driver))
        store.saveSignedInState(
            StoredSyncAuth(
                token = "real-optimizer-acceptance",
                clientId = "local-read-only",
                endpoint = "https://acceptance.kelma.invalid",
                username = "local-acceptance",
            ),
            input.asSyncedCollection(),
        )
        val job = checkNotNull(
            store.prepareSchedulerOptimization(
                timezoneId = studyPolicy.timeZoneId,
                dayStartHour = studyPolicy.dayStartHour,
            ).job,
        )
        val completed = store.runSchedulerOptimization(job.jobId)
        val persisted = checkNotNull(completed.pendingCandidate)
        assertEquals(direct.candidate.parameters, persisted.payload.parameters)
        assertEquals(direct.candidate.metrics.trainingLossAfter, persisted.payload.metrics.trainingLossAfter)
        assertEquals(direct.candidate.metrics.validationLossAfter, persisted.payload.metrics.validationLossAfter)
        assertEquals(0, store.loadSchedulerProfile().local.version)

        driver.close()
        driver = openDesktopDatabase(databaseFile)
        store = PersistentCollectionStore(KelmaDatabase(driver))
        assertEquals(persisted, store.loadSchedulerOptimizer(recoverInterrupted = true).pendingCandidate)
        assertEquals(0, store.loadSchedulerProfile().local.version)
        driver.close()
    } finally {
        directory.deleteRecursively()
    }
}

private fun RealOptimizerInput.asSyncedCollection(): SyncedCollection {
    val cards = reviews.asSequence().map { review ->
        val cardId = review.cardIdentity.removePrefix("card-").toLong()
        cardId to SyncCard(
            cardId = cardId,
            noteGuid = "optimizer-card-$cardId",
            deckName = "Private optimizer acceptance",
            ord = 0,
        )
    }.distinctBy { it.first }.toMap()
    val syncedReviews = reviews.associate { review ->
        val cardId = review.cardIdentity.removePrefix("card-").toLong()
        val card = cards.getValue(cardId)
        review.reviewId to SyncReview(
            reviewId = review.reviewId,
            sourceCardId = cardId,
            noteGuid = card.noteGuid,
            cardOrd = 0,
            deckName = card.deckName,
            ease = review.rating.value,
            takenMillis = review.durationMillis?.toInt() ?: 0,
            reviewKind = when (review.state) {
                OptimizerReviewState.Relearning -> 2
                OptimizerReviewState.Review -> 1
                else -> 0
            },
            checksum = "private-optimizer-${review.reviewId}",
        )
    }
    return SyncedCollection(
        cards = cards,
        reviews = syncedReviews,
        deckNames = setOf("Private optimizer acceptance"),
    )
}

private fun writeReviewFixture(reviews: List<OptimizerReview>) {
    val destination = System.getenv(ReviewFixtureEnvironment)?.takeIf(String::isNotBlank) ?: return
    File(destination).bufferedWriter().use { writer ->
        writer.appendLine("reviewId\tcardIdentity\treviewedAtMillis\trating\tstate\tdurationMillis")
        reviews.forEach { review ->
            writer.append(review.reviewId.toString()).append('\t')
                .append(review.cardIdentity).append('\t')
                .append(review.reviewedAtMillis.toString()).append('\t')
                .append(review.rating.value.toString()).append('\t')
                .append(checkNotNull(review.state).value.toString()).append('\t')
                .appendLine(checkNotNull(review.durationMillis).toString())
        }
    }
}

private fun writeMachineResult(result: RealOptimizerMachineResult) {
    val destination = System.getenv(ResultEnvironment)?.takeIf(String::isNotBlank) ?: return
    File(destination).writeText(RealOptimizerReportJson.encodeToString(result) + "\n")
}

private fun writeHumanReport(
    input: RealOptimizerInput,
    dataset: OptimizerDataset,
    candidate: OptimizationResult.CandidateReady,
    timezone: String,
    dayStartHour: Int,
    loadMillis: Long,
    formatMillis: Long,
    firstMillis: Long,
    repeatMillis: Long,
    persistentWorkflowMillis: Long,
) {
    val destination = System.getenv(ReportEnvironment)?.takeIf(String::isNotBlank) ?: return
    val metrics = candidate.candidate.metrics
    File(destination).writeText(
        buildString {
            appendLine("result=passed")
            appendLine("source_fingerprint_sha256=${input.fingerprint}")
            appendLine("timezone=$timezone")
            appendLine("day_start_hour=$dayStartHour")
            appendLine("active_cards=${input.activeCards}")
            appendLine("active_qualifying_reviews=${input.reviews.size}")
            appendLine("optimizer_examples=${dataset.examples.size}")
            appendLine("optimizer_cards=${dataset.eligibility.qualifyingCards}")
            appendLine("recalled_outcomes=${dataset.eligibility.recalledOutcomes}")
            appendLine("forgotten_outcomes=${dataset.eligibility.forgottenOutcomes}")
            appendLine("training_examples=${metrics.trainingExamples}")
            appendLine("validation_examples=${metrics.validationExamples}")
            appendLine("training_loss=${metrics.trainingLossBefore}->${metrics.trainingLossAfter}")
            appendLine("validation_loss=${metrics.validationLossBefore}->${metrics.validationLossAfter}")
            appendLine("candidate_parameters=${candidate.candidate.parameters.joinToString(",")}")
            appendLine("load_ms=$loadMillis")
            appendLine("format_ms=$formatMillis")
            appendLine("first_optimization_ms=$firstMillis")
            appendLine("repeat_optimization_ms=$repeatMillis")
            appendLine("persistent_workflow_ms=$persistentWorkflowMillis")
        },
    )
}

private fun MessageDigest.updateLongValue(value: Long) {
    for (shift in 56 downTo 0 step 8) update((value ushr shift).toByte())
}

private fun MessageDigest.updateIntValue(value: Int) {
    for (shift in 24 downTo 0 step 8) update((value ushr shift).toByte())
}

private val RealOptimizerReportJson = Json { prettyPrint = true }

private const val CollectionEnvironment = "KELMA_ANKI_COLLECTION"
private const val RequiredEnvironment = "KELMA_REQUIRE_REAL_OPTIMIZER_ACCEPTANCE"
private const val ReviewFixtureEnvironment = "KELMA_OPTIMIZER_REVIEW_FIXTURE"
private const val ResultEnvironment = "KELMA_OPTIMIZER_KOTLIN_RESULT"
private const val ReportEnvironment = "KELMA_OPTIMIZER_KOTLIN_REPORT"
private const val TimezoneEnvironment = "KELMA_OPTIMIZER_TIMEZONE"
private const val DayStartEnvironment = "KELMA_OPTIMIZER_DAY_START_HOUR"
