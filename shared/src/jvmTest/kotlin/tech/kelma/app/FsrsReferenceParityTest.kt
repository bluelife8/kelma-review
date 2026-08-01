package tech.kelma.app

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FsrsReferenceParityTest {
    private val fixtureBytes by lazy {
        val resource = requireNotNull(javaClass.getResourceAsStream(FixturePath)) {
            "Missing FSRS reference fixture: $FixturePath"
        }
        resource.use { it.readBytes() }
    }
    private val fixture by lazy {
        Json.decodeFromString<ReferenceFixture>(fixtureBytes.decodeToString())
    }

    @Test
    fun fixtureIsPinnedToTheAuditedReference() {
        assertEquals(1, fixture.schemaVersion)
        assertEquals("ts-fsrs", fixture.oracle.`package`)
        assertEquals("4.7.1", fixture.oracle.packageVersion)
        assertEquals("v4.7.1 using FSRS-5.0", fixture.oracle.algorithmVersion)
        assertEquals("da455aa5eee15e462548bb4b990871689f77eff5", fixture.oracle.gitCommit)
        assertEquals("MIT", fixture.oracle.license)
        assertEquals(
            "sha512-uYqKbSCNWLRPT0PfqFFy2rn0YuYz2fKmtgpgHOBd5/DP14oj6diG7P271AwJ8iGrKnmIspzXCF+nTKxict+Lcg==",
            fixture.oracle.npmIntegrity,
        )
        assertEquals(FixtureSha256, fixtureBytes.sha256())
        assertEquals(19, fixture.oracle.weights.size)
        assertEquals(0.9, fixture.oracle.requestRetention)
        assertEquals(48, fixture.cases.size)
        assertEquals(5, fixture.trajectories.size)
        assertEquals(47, fixture.trajectories.sumOf { it.steps.size })
        assertTrue(!fixture.oracle.enableFuzz)
        assertTrue(fixture.oracle.enableShortTerm)
    }

    @Test
    fun schedulerMatchesTsFsrsReferenceVectors() {
        fixture.cases.forEach(::assertReferenceCase)
    }

    @Test
    fun completeReviewTrajectoriesMatchTsFsrs() {
        fixture.trajectories.forEach { trajectory ->
            var schedule: LocalCardSchedule? = null
            trajectory.steps.forEachIndexed { index, step ->
                val result = FsrsScheduler.review(
                    card = SyncCard(CardId, "note-1", "Deck"),
                    previous = schedule,
                    rating = Rating.valueOf(step.rating),
                    reviewedAtMillis = BaseTimeMillis + step.reviewedAtOffsetMillis,
                    options = LegacyOptions,
                )
                val message = "trajectory ${trajectory.name}, step $index (${step.rating})"
                assertEquals(
                    step.stability,
                    result.stability,
                    step.stability.referenceTolerance(),
                    message,
                )
                assertEquals(
                    step.difficulty,
                    result.difficulty,
                    step.difficulty.referenceTolerance(),
                    message,
                )
                assertEquals(ReviewPhase.valueOf(step.phase), result.phase, message)
                assertEquals(step.scheduledDays, result.scheduledDays, message)
                assertEquals(BaseTimeMillis + step.dueAtOffsetMillis, result.dueAtMillis, message)
                assertEquals(step.repetitions, result.repetitions, message)
                assertEquals(step.lapses, result.lapses, message)
                schedule = result
            }
        }
    }

    private fun assertReferenceCase(vector: ReferenceCase) {
        val reviewedAt = BaseTimeMillis + (vector.elapsedDays * MillisPerDay).roundToLong()
        val previous = vector.phase?.let { phase ->
            LocalCardSchedule(
                cardId = CardId,
                phase = ReviewPhase.valueOf(phase),
                dueAtMillis = BaseTimeMillis,
                stability = requireNotNull(vector.stability),
                difficulty = requireNotNull(vector.difficulty),
                scheduledDays = vector.scheduledDays,
                repetitions = 1,
                lapses = 0,
                lastReviewAtMillis = BaseTimeMillis,
            )
        }
        val result = FsrsScheduler.review(
            card = SyncCard(CardId, "note-1", "Deck"),
            previous = previous,
            rating = Rating.valueOf(vector.rating),
            reviewedAtMillis = reviewedAt,
            options = LegacyOptions,
        )
        val message = "reference vector ${vector.name}"

        assertEquals(
            vector.expectedStability,
            result.stability,
            vector.expectedStability.referenceTolerance(),
            message,
        )
        assertEquals(
            vector.expectedDifficulty,
            result.difficulty,
            vector.expectedDifficulty.referenceTolerance(),
            message,
        )
        if (vector.mode == "initial") {
            assertEquals(ReviewPhase.valueOf(vector.expectedPhase), result.phase, message)
            assertEquals(vector.expectedIntervalDays, result.scheduledDays, message)
            val dueOffset = vector.expectedDueMinutes?.times(60_000L)
                ?: vector.expectedIntervalDays * MillisPerDay
            assertEquals(reviewedAt + dueOffset, result.dueAtMillis, message)
            assertEquals(1, result.repetitions, message)
            assertEquals(0, result.lapses, message)
        }
        assertTrue(result.stability.isFinite(), message)
        assertTrue(result.difficulty in 1.0..10.0, message)
    }

    private companion object {
        const val FixturePath = "/fsrs/ts-fsrs-4.7.1-vectors.json"
        const val FixtureSha256 = "254680c55ae4e896b1ae600ab2430126909a2146aeed4c81203ebfebec74960d"
        const val CardId = 42L
        const val BaseTimeMillis = 1_700_000_000_000L
        const val AbsoluteTolerance = 0.00001
        const val RelativeTolerance = 0.00000001
        val LegacyOptions = DeckOptions(
            fsrsParameters = DefaultFsrs5Parameters,
            schedulerAlgorithm = SchedulerAlgorithm.Fsrs5,
            relearningStepsMinutes = listOf(5),
        )
    }

    private fun Double.referenceTolerance(): Double =
        max(AbsoluteTolerance, abs(this) * RelativeTolerance)

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

@Serializable
private data class ReferenceFixture(
    val schemaVersion: Int,
    val oracle: ReferenceOracle,
    val cases: List<ReferenceCase>,
    val trajectories: List<ReferenceTrajectory>,
)

@Serializable
private data class ReferenceOracle(
    val `package`: String,
    val packageVersion: String,
    val algorithmVersion: String,
    val gitCommit: String,
    val license: String,
    val npmIntegrity: String,
    val requestRetention: Double,
    val maximumInterval: Int,
    val enableFuzz: Boolean,
    val enableShortTerm: Boolean,
    val weights: List<Double>,
)

@Serializable
private data class ReferenceTrajectory(
    val name: String,
    val steps: List<ReferenceTrajectoryStep>,
)

@Serializable
private data class ReferenceTrajectoryStep(
    val rating: String,
    val reviewedAtOffsetMillis: Long,
    val dueAtOffsetMillis: Long,
    val stability: Double,
    val difficulty: Double,
    val phase: String,
    val scheduledDays: Int,
    val repetitions: Int,
    val lapses: Int,
)

@Serializable
private data class ReferenceCase(
    val name: String,
    val mode: String,
    val phase: String?,
    val stability: Double?,
    val difficulty: Double?,
    val scheduledDays: Int,
    val elapsedDays: Double,
    val rating: String,
    val expectedStability: Double,
    val expectedDifficulty: Double,
    val expectedPhase: String,
    val expectedIntervalDays: Int,
    val expectedDueMinutes: Long?,
)
