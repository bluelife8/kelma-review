package tech.kelma.app

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchedulerProfileCommonTest {
    @Test
    fun parameterAndRetentionOwnershipRemainIndependent() {
        val settings = SchedulerProfileSettings(
            parameterSource = SchedulerProfileSource.Manual,
            desiredRetention = 0.95,
            retentionSource = SchedulerProfileSource.Default,
        ).validated()

        assertEquals(SchedulerProfileSource.Manual, settings.parameterSource)
        assertEquals(SchedulerProfileSource.Default, settings.retentionSource)
    }

    @Test
    fun optimizedCloudProfileRequiresCompleteProvenance() {
        assertFailsWith<IllegalArgumentException> {
            SchedulerProfileSettings(
                parameterSource = SchedulerProfileSource.ClientOptimized,
            ).validated(forCloud = true)
        }
        SchedulerProfileSettings(
            parameterSource = SchedulerProfileSource.ClientOptimized,
            optimizer = "fsrs-optimizer",
            optimizerVersion = "6.5.0",
            optimizationHistoryHash = "a".repeat(64),
            optimizationThroughReviewId = 42,
        ).validated(forCloud = true)
    }

    @Test
    fun cloudStepsRetainExactSecondPrecision() {
        val options = CloudSchedulerProfile(
            learningStepsSeconds = listOf(75, 625),
            relearningStepsSeconds = listOf(425),
        ).asSettings().asDeckOptions()

        assertEquals(listOf(75, 625), options.effectiveLearningStepsSeconds)
        assertEquals(listOf(425), options.effectiveRelearningStepsSeconds)
    }

    @Test
    fun legacyNineteenParameterProfileCannotBecomeCloudFsrs6() {
        val legacy = DeckOptions(
            schedulerAlgorithm = SchedulerAlgorithm.Fsrs5,
            fsrsParameters = DefaultFsrs5Parameters.mapIndexed { index, value ->
                if (index == 0) value + 0.01 else value
            },
        )

        assertFailsWith<IllegalArgumentException> {
            SchedulerProfileSettings.fromDeckOptions(legacy)
        }
    }

    @Test
    fun candidateUsesServerFieldNames() {
        val candidate = SchedulerProfileSettings(
            parameterSource = SchedulerProfileSource.Manual,
            retentionSource = SchedulerProfileSource.Manual,
        ).toCandidate(7, "stable-request")
        val encoded = Json.encodeToString(candidate)

        assertTrue(encoded.contains("\"base_profile_version\":7"))
        assertTrue(encoded.contains("\"idempotency_key\":\"stable-request\""))
        assertTrue(encoded.contains("\"parameter_source\":\"manual\""))
        assertTrue(encoded.contains("\"retention_source\":\"manual\""))
    }
}
