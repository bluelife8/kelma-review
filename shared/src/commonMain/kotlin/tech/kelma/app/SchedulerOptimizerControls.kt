package tech.kelma.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.round
import kotlinx.coroutines.launch

@Composable
internal fun SchedulerOptimizerControls(
    state: SchedulerOptimizerState,
    signedIn: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onApply: suspend (publish: Boolean) -> String?,
    onDiscard: suspend () -> String?,
) {
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(action: suspend () -> String?) {
        if (working) return
        working = true
        message = null
        scope.launch {
            val result = runCatching { action() }
            val error = result.exceptionOrNull()?.message ?: result.getOrNull()
            working = false
            failed = error != null
            message = error ?: "Optimizer candidate updated"
        }
    }
    val fontSize = if (compact) 11.sp else 13.sp
    val job = state.job
    val candidate = state.pendingCandidate
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Local optimization", fontWeight = FontWeight.Bold, fontSize = if (compact) 12.sp else 14.sp)
        Text(
            "Uses immutable review history on this device. Starting never changes active settings.",
            fontSize = fontSize,
        )
        when {
            state.running -> {
                val runningJob = requireNotNull(job)
                Text(
                    if (runningJob.completedEpochs == 0) {
                        "Formatting history…"
                    } else {
                        "Training epoch ${runningJob.completedEpochs}/${runningJob.totalEpochs}"
                    },
                    fontSize = fontSize,
                    modifier = Modifier.testTag("optimizer-progress"),
                )
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !runningJob.cancelRequested,
                    modifier = Modifier.fillMaxWidth().testTag("optimizer-cancel"),
                ) { Text(if (runningJob.cancelRequested) "Cancellation requested" else "Cancel") }
            }
            candidate != null -> CandidateReview(
                candidate = candidate,
                signedIn = signedIn,
                compact = compact,
                enabled = enabled && !working,
                onApply = { publish -> run { onApply(publish) } },
                onDiscard = { run(onDiscard) },
            )
            else -> {
                val statusMessage = state.candidate
                    ?.takeIf { it.jobId == job?.jobId }
                    ?.let(::candidateMessage)
                    ?: job?.let(::jobMessage)
                statusMessage?.let {
                    Text(it, fontSize = fontSize, modifier = Modifier.testTag("optimizer-status"))
                }
                Button(
                    onClick = onStart,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("optimizer-start"),
                ) { Text(if (job == null) "Optimize" else "Optimize again") }
            }
        }
        message?.let {
            Text(
                it,
                color = if (failed) KelmaColors.Bad else KelmaColors.Good,
                fontSize = fontSize,
            )
        }
    }
}

@Composable
private fun CandidateReview(
    candidate: SchedulerOptimizerCandidate,
    signedIn: Boolean,
    compact: Boolean,
    enabled: Boolean,
    onApply: (Boolean) -> Unit,
    onDiscard: () -> Unit,
) {
    val payload = candidate.payload
    val fontSize = if (compact) 11.sp else 13.sp
    Text(
        "Candidate ready · ${payload.qualifyingReviews} reviews across ${payload.qualifyingCards} cards",
        fontWeight = FontWeight.Bold,
        fontSize = fontSize,
        modifier = Modifier.testTag("optimizer-candidate"),
    )
    Text(
        "Training loss ${payload.metrics.trainingLossBefore} → ${payload.metrics.trainingLossAfter} · " +
            "validation ${payload.metrics.validationLossBefore} → ${payload.metrics.validationLossAfter}",
        fontSize = fontSize,
    )
    Text("Current: ${parameterSummary(payload.previousParameters)}", fontSize = fontSize)
    Text("Candidate: ${parameterSummary(payload.parameters)}", fontSize = fontSize)
    if (compact) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(
                onClick = { onApply(false) },
                enabled = enabled,
                modifier = Modifier.weight(1f).testTag("optimizer-apply"),
            ) { Text("Apply", fontSize = 11.sp) }
            Button(
                onClick = { onApply(true) },
                enabled = enabled && signedIn,
                modifier = Modifier.weight(1f).testTag("optimizer-apply-publish"),
            ) { Text("Apply & publish", fontSize = 11.sp) }
        }
    } else {
        Button(
            onClick = { onApply(false) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("mobile-optimizer-apply"),
        ) { Text("Apply locally") }
        Button(
            onClick = { onApply(true) },
            enabled = enabled && signedIn,
            modifier = Modifier.fillMaxWidth().testTag("mobile-optimizer-apply-publish"),
        ) { Text("Apply locally and publish") }
    }
    OutlinedButton(
        onClick = onDiscard,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag(
            if (compact) "optimizer-discard" else "mobile-optimizer-discard",
        ),
    ) { Text("Discard") }
}

private fun candidateMessage(candidate: SchedulerOptimizerCandidate): String = when (candidate.status) {
    SchedulerOptimizerCandidateStatus.Pending -> "Candidate ready for review."
    SchedulerOptimizerCandidateStatus.Applied -> "Candidate applied."
    SchedulerOptimizerCandidateStatus.Discarded -> "Candidate discarded; active settings were unchanged."
    SchedulerOptimizerCandidateStatus.Stale -> "Review history changed; run Optimize again."
}

private fun jobMessage(job: SchedulerOptimizerJob): String = when (job.status) {
    SchedulerOptimizerJobStatus.Running -> "Optimization is running"
    SchedulerOptimizerJobStatus.Completed -> "Candidate completed"
    SchedulerOptimizerJobStatus.Ineligible -> when (job.reasonCode) {
        "insufficient_reviews" -> "More cross-day review history is required (minimum 1,000)."
        "insufficient_cards" -> "More reviewed cards are required (minimum 100)."
        "missing_recalled_outcome" -> "History needs at least one recalled outcome."
        "missing_forgotten_outcome" -> "History needs at least one forgotten outcome."
        else -> "Review history is not yet eligible for optimization."
    }
    SchedulerOptimizerJobStatus.Cancelled -> "Optimization was cancelled; active settings were unchanged."
    SchedulerOptimizerJobStatus.Interrupted -> "Optimization was interrupted; no candidate was created."
    SchedulerOptimizerJobStatus.Failed -> "Optimization failed; active settings were unchanged."
}

private fun parameterSummary(parameters: List<Double>): String =
    parameters.joinToString(", ") { value ->
        val rounded = round(value * 10_000.0) / 10_000.0
        rounded.toString()
    }
