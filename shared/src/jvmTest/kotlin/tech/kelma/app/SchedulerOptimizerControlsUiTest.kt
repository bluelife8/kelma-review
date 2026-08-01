package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SchedulerOptimizerControlsUiTest {
    @Test
    fun candidateRequiresExplicitApplyPublishOrDiscard() = runComposeUiTest {
        val local = AtomicInteger()
        val publish = AtomicInteger()
        val discard = AtomicInteger()
        setContent {
            KelmaTheme {
                SchedulerOptimizerControls(
                    state = SchedulerOptimizerState(candidate = optimizerUiCandidate()),
                    signedIn = true,
                    enabled = true,
                    compact = true,
                    onStart = {},
                    onCancel = {},
                    onApply = {
                        if (it) publish.incrementAndGet() else local.incrementAndGet()
                        null
                    },
                    onDiscard = { discard.incrementAndGet(); null },
                )
            }
        }
        onNodeWithTag("optimizer-candidate").assertIsDisplayed()
        onNodeWithText("Candidate ready · 1440 reviews across 120 cards").assertIsDisplayed()
        onNodeWithTag("optimizer-apply").performClick()
        waitUntil { local.get() == 1 }
        onNodeWithTag("optimizer-apply-publish").performClick()
        waitUntil { publish.get() == 1 }
        onNodeWithTag("optimizer-discard").performClick()
        waitUntil { discard.get() == 1 }
        assertEquals(1, local.get())
        assertEquals(1, publish.get())
        assertEquals(1, discard.get())
    }

    @Test
    fun runningJobShowsProgressAndCancellation() = runComposeUiTest {
        val cancelled = AtomicInteger()
        setContent {
            KelmaTheme {
                SchedulerOptimizerControls(
                    state = SchedulerOptimizerState(
                        job = SchedulerOptimizerJob(
                            jobId = "job",
                            status = SchedulerOptimizerJobStatus.Running,
                            timezoneId = "UTC",
                            dayStartHour = 4,
                            totalEpochs = 3,
                            completedEpochs = 1,
                            rawReviewCount = 1_680,
                            qualifyingReviewCount = 1_440,
                            qualifyingCardCount = 120,
                            historySha256 = null,
                            datasetSha256 = null,
                            throughReviewId = null,
                            reasonCode = null,
                            cancelRequested = false,
                            createdAtMillis = 1,
                            updatedAtMillis = 2,
                            completedAtMillis = null,
                        ),
                    ),
                    signedIn = false,
                    enabled = true,
                    compact = false,
                    onStart = {},
                    onCancel = { cancelled.incrementAndGet() },
                    onApply = { null },
                    onDiscard = { null },
                )
            }
        }
        onNodeWithText("Training epoch 1/3").assertIsDisplayed()
        onNodeWithTag("optimizer-cancel").performClick()
        assertEquals(1, cancelled.get())
    }
}

private fun optimizerUiCandidate(): SchedulerOptimizerCandidate = SchedulerOptimizerCandidate(
    candidateId = "candidate",
    jobId = "job",
    payload = SchedulerOptimizerCandidatePayload(
        parameters = DefaultFsrs6Parameters.mapIndexed { index, value ->
            if (index == 0) value + 0.01 else value
        },
        previousParameters = DefaultFsrs6Parameters,
        initializedParameters = DefaultFsrs6Parameters,
        metrics = SchedulerOptimizerMetrics(1_152, 288, 0.65, 0.52, 0.68, 0.55),
        localHistorySha256 = "a".repeat(64),
        datasetSha256 = "b".repeat(64),
        serverHistorySha256 = "c".repeat(64),
        throughReviewId = 42,
        rawReviews = 1_680,
        qualifyingReviews = 1_440,
        qualifyingCards = 120,
        recalledOutcomes = 1_152,
        forgottenOutcomes = 288,
        timezoneId = "UTC",
        dayStartHour = 4,
        optimizerVersion = "optimizer-test",
    ),
    status = SchedulerOptimizerCandidateStatus.Pending,
    createdAtMillis = 1,
    resolvedAtMillis = null,
)
