package tech.kelma.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun SyncConflictDialog(
    conflict: SyncUploadConflict,
    onKeepLocal: (() -> Unit)?,
    onUseServer: () -> Unit,
) {
    val resource = when (conflict.kind) {
        "note" -> "note ${conflict.resourceKey}"
        "deck" -> "deck ${conflict.resourceKey}"
        SchedulerProfileConflictKind -> "account scheduler profile"
        else -> "review ${conflict.resourceKey}"
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Sync conflict") },
        text = {
            Text(
                when (conflict.kind) {
                    "review" ->
                        "KelmaSync already contains different immutable history for this review. Use the server copy to continue."
                    SchedulerProfileConflictKind ->
                        "Another device changed the cloud scheduler profile. Keep this device to retry against the new version, " +
                            "or use KelmaSync while leaving this device's local profile unchanged."
                    else ->
                        "Both this device and KelmaSync changed $resource. Choose which version should become authoritative."
                },
            )
        },
        confirmButton = {
            onKeepLocal?.let { action ->
                TextButton(onClick = action) { Text("Keep this device") }
            }
        },
        dismissButton = {
            TextButton(onClick = onUseServer) { Text("Use KelmaSync") }
        },
    )
}
