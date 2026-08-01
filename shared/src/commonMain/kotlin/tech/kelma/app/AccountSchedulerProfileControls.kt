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
import kotlinx.coroutines.launch

@Composable
internal fun DesktopAccountSchedulerProfileControls(
    state: SchedulerProfileState,
    signedIn: Boolean,
    enabled: Boolean,
    onApplyCurrent: suspend (publish: Boolean) -> String?,
    onApplyCloud: suspend () -> String?,
) {
    AccountSchedulerProfileControls(
        state = state,
        signedIn = signedIn,
        enabled = enabled,
        compact = true,
        onApplyCurrent = onApplyCurrent,
        onApplyCloud = onApplyCloud,
    )
}

@Composable
internal fun MobileAccountSchedulerProfileControls(
    state: SchedulerProfileState,
    signedIn: Boolean,
    enabled: Boolean,
    onApplyCurrent: suspend (publish: Boolean) -> String?,
    onApplyCloud: suspend () -> String?,
) {
    AccountSchedulerProfileControls(
        state = state,
        signedIn = signedIn,
        enabled = enabled,
        compact = false,
        onApplyCurrent = onApplyCurrent,
        onApplyCloud = onApplyCloud,
    )
}

@Composable
private fun AccountSchedulerProfileControls(
    state: SchedulerProfileState,
    signedIn: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onApplyCurrent: suspend (publish: Boolean) -> String?,
    onApplyCloud: suspend () -> String?,
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
            message = error ?: "Scheduler profile applied"
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            "Account profile",
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Local v${state.local.version} · parameters ${state.local.settings.parameterSource.label()} · " +
                "retention ${state.local.settings.retentionSource.label()}",
            fontSize = if (compact) 11.sp else 13.sp,
        )
        Text(
            state.cloud?.let { cloud ->
                "KelmaSync v${cloud.version} · ${state.syncStatus.label()} · " +
                    "${state.projections.pending + state.projections.running} projections pending"
            } ?: if (signedIn) {
                "KelmaSync profile has not been pulled yet"
            } else {
                "Sign in to publish this account profile"
            },
            fontSize = if (compact) 11.sp else 13.sp,
        )
        if (compact) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(
                    onClick = { run { onApplyCurrent(false) } },
                    enabled = enabled && !working,
                    modifier = Modifier.weight(1f).testTag("profile-apply-local"),
                ) { Text("Use locally", fontSize = 11.sp) }
                Button(
                    onClick = { run { onApplyCurrent(true) } },
                    enabled = enabled && signedIn && !working,
                    modifier = Modifier.weight(1f).testTag("profile-apply-publish"),
                ) { Text("Apply & publish", fontSize = 11.sp) }
            }
        } else {
            OutlinedButton(
                onClick = { run { onApplyCurrent(false) } },
                enabled = enabled && !working,
                modifier = Modifier.fillMaxWidth().testTag("mobile-profile-apply-local"),
            ) { Text("Use as account default") }
            Button(
                onClick = { run { onApplyCurrent(true) } },
                enabled = enabled && signedIn && !working,
                modifier = Modifier.fillMaxWidth().testTag("mobile-profile-apply-publish"),
            ) { Text("Apply locally and publish") }
        }
        if (state.cloud != null) {
            OutlinedButton(
                onClick = { run(onApplyCloud) },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().testTag(
                    if (compact) "profile-use-cloud" else "mobile-profile-use-cloud",
                ),
            ) { Text("Use KelmaSync profile locally") }
        }
        message?.let {
            Text(
                it,
                color = if (failed) KelmaColors.Bad else KelmaColors.Good,
                fontSize = if (compact) 11.sp else 13.sp,
            )
        }
    }
}

private fun SchedulerProfileSource.label(): String = when (this) {
    SchedulerProfileSource.Default -> "default"
    SchedulerProfileSource.ClientOptimized -> "optimized"
    SchedulerProfileSource.Manual -> "manual"
}

private fun SchedulerProfileSyncStatus.label(): String = when (this) {
    SchedulerProfileSyncStatus.Current -> "current"
    SchedulerProfileSyncStatus.Pending -> "upload pending"
    SchedulerProfileSyncStatus.AwaitingConfirmation -> "awaiting confirmation"
    SchedulerProfileSyncStatus.Conflict -> "conflict"
}
