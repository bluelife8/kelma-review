package tech.kelma.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun DesktopStudyDayPolicyControls(
    policy: AccountStudyDayPolicy,
    signedIn: Boolean,
    syncing: Boolean,
    onSave: suspend (String, Int) -> String?,
) {
    var timezone by remember(policy) { mutableStateOf(policy.timezoneId) }
    var hour by remember(policy) { mutableStateOf(policy.dayStartHour.toString()) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        val parsedHour = hour.toIntOrNull()
        if (parsedHour == null || parsedHour !in 0..23) {
            message = "Study-day start hour must be between 0 and 23"
            messageIsError = true
            return
        }
        saving = true
        message = null
        scope.launch {
            val error = onSave(timezone, parsedHour)
            saving = false
            message = error ?: "Study day saved to KelmaSync"
            messageIsError = error != null
        }
    }

    OutlinedTextField(
        value = timezone,
        onValueChange = { timezone = it },
        modifier = Modifier.fillMaxWidth().testTag("options-study-day-timezone"),
        label = { Text("Timezone") },
        supportingText = { Text("IANA timezone, for example America/New_York") },
        singleLine = true,
    )
    OutlinedTextField(
        value = hour,
        onValueChange = { hour = it.filter(Char::isDigit).take(2) },
        modifier = Modifier.fillMaxWidth().testTag("options-study-day-hour"),
        label = { Text("Day starts at local hour") },
        singleLine = true,
    )
    Text(
        "KelmaSync v${policy.version} · used by Review and Immersion",
        color = KelmaDesktopColors.TextSecondary,
        fontSize = 12.sp,
    )
    message?.let { text ->
        Text(
            text,
            color = if (messageIsError) KelmaColors.Bad else KelmaDesktopColors.Accent,
            fontSize = 12.sp,
        )
    }
    Button(
        onClick = ::save,
        enabled = signedIn && !syncing && !saving,
        modifier = Modifier.testTag("options-save-study-day"),
    ) {
        Text(if (saving) "Saving…" else "Save account study day")
    }
}

@Composable
internal fun MobileStudyDayPolicyControls(
    policy: AccountStudyDayPolicy,
    signedIn: Boolean,
    syncing: Boolean,
    onSave: suspend (String, Int) -> String?,
) {
    var timezone by remember(policy) { mutableStateOf(policy.timezoneId) }
    var hour by remember(policy) { mutableStateOf(policy.dayStartHour.toString()) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        val parsedHour = hour.toIntOrNull()
        if (parsedHour == null || parsedHour !in 0..23) {
            message = "Study-day start hour must be between 0 and 23"
            messageIsError = true
            return
        }
        saving = true
        message = null
        scope.launch {
            val error = onSave(timezone, parsedHour)
            saving = false
            message = error ?: "Study day saved to KelmaSync"
            messageIsError = error != null
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = timezone,
            onValueChange = { timezone = it },
            modifier = Modifier.fillMaxWidth().testTag("mobile-options-study-day-timezone"),
            label = { Text("Timezone") },
            supportingText = { Text("IANA timezone, for example America/New_York") },
            singleLine = true,
        )
        OutlinedTextField(
            value = hour,
            onValueChange = { hour = it.filter(Char::isDigit).take(2) },
            modifier = Modifier.fillMaxWidth().testTag("mobile-options-study-day-hour"),
            label = { Text("Day starts at local hour") },
            singleLine = true,
        )
        Text(
            "KelmaSync v${policy.version} · shared with Immersion",
            color = KelmaColors.TextSecondary,
            fontSize = 13.sp,
        )
        message?.let { text ->
            Text(
                text,
                color = if (messageIsError) KelmaColors.Bad else KelmaColors.Good,
                fontSize = 13.sp,
            )
        }
        Button(
            onClick = ::save,
            enabled = signedIn && !syncing && !saving,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).testTag("mobile-options-save-study-day"),
        ) {
            Text(if (saving) "Saving…" else "Save account study day")
        }
    }
}
