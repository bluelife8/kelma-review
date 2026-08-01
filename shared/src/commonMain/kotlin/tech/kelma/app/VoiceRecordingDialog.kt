package tech.kelma.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
internal fun VoiceRecordingDialog(
    recording: Boolean,
    hasRecording: Boolean,
    error: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record Own Voice") },
        text = {
            Text(
                when {
                    error != null -> error
                    recording -> "Recording… Speak your answer, then stop to keep it for this " +
                        "review session (maximum two minutes)."
                    hasRecording -> "Recording saved for temporary replay in this review session."
                    else -> "Record your answer and compare it with the card audio. The recording stays on this device."
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = if (recording) onStop else onStart,
                modifier = Modifier.testTag(if (recording) "voice-record-stop" else "voice-record-start"),
            ) {
                Text(if (recording) "Stop & Save" else if (hasRecording) "Record Again" else "Start Recording")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (recording) "Discard" else "Close") }
        },
    )
}
