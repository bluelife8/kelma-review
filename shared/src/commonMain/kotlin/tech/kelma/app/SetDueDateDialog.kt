package tech.kelma.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

private val IsoDate = Regex("""(\d{4})-(\d{2})-(\d{2})""")

internal fun parseDueDateMillis(value: String): Long? {
    val match = IsoDate.matchEntire(value.trim()) ?: return null
    val year = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..9_999 } ?: return null
    val month = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    val leapYear = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val daysInMonth = when (month) {
        2 -> if (leapYear) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    if (day !in 1..daysInMonth) return null

    val adjustedYear = year - if (month <= 2) 1 else 0
    val era = adjustedYear / 400
    val yearOfEra = adjustedYear - era * 400
    val adjustedMonth = month + if (month > 2) -3 else 9
    val dayOfYear = (153 * adjustedMonth + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return (era * 146_097L + dayOfEra - 719_468L) * MillisPerDay
}

@Composable
internal fun SetDueDateDialog(
    initialDate: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var value by remember(initialDate) { mutableStateOf(initialDate) }
    val dueAtMillis = parseDueDateMillis(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Due Date") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.testTag("set-due-date-input"),
                label = { Text("Due date (YYYY-MM-DD)") },
                supportingText = {
                    Text(if (value.isNotBlank() && dueAtMillis == null) "Enter a valid date" else "UTC date")
                },
                isError = value.isNotBlank() && dueAtMillis == null,
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = dueAtMillis != null,
                onClick = { dueAtMillis?.let(onConfirm) },
            ) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
internal fun CreationDateFilterDialog(
    initialDate: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialDate) { mutableStateOf(initialDate) }
    val normalized = value.trim()
    val validDate = parseDueDateMillis(normalized) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter by Creation Date") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.testTag("created-date-filter-input"),
                label = { Text("Creation date (YYYY-MM-DD)") },
                supportingText = {
                    Text(if (value.isNotBlank() && !validDate) "Enter a valid date" else "Defaults to today")
                },
                isError = value.isNotBlank() && !validDate,
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = validDate,
                onClick = { onConfirm(normalized) },
            ) { Text("Filter") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
