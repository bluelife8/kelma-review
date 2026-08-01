package tech.kelma.app

import androidx.compose.runtime.Composable

data class VoiceRecording(
    val filename: String,
    val bytes: ByteArray,
)

interface VoiceRecorder : AutoCloseable {
    val isRecording: Boolean
    suspend fun start(): String?
    suspend fun stop(): VoiceRecording?
    override fun close()
}

@Composable
expect fun rememberVoiceRecorder(): VoiceRecorder
