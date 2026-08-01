package tech.kelma.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberVoiceRecorder(): VoiceRecorder {
    val context = LocalContext.current.applicationContext
    val recorder = remember(context) { AndroidVoiceRecorder(context) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        recorder::permissionResult,
    )
    recorder.requestPermission = { launcher.launch(Manifest.permission.RECORD_AUDIO) }
    return recorder
}

private class AndroidVoiceRecorder(
    private val context: Context,
) : VoiceRecorder {
    var requestPermission: () -> Unit = {}
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    override val isRecording: Boolean get() = recorder != null

    override suspend fun start(): String? {
        if (isRecording) return null
        if (!hasPermission() && !requestMicrophonePermission()) return "Microphone permission was denied"
        return withContext(Dispatchers.IO) {
            try {
                val file = File.createTempFile("kelma-voice-", ".m4a", context.cacheDir)
                output = file
                @Suppress("DEPRECATION")
                recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44_100)
                    setAudioChannels(1)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
                null
            } catch (failure: Exception) {
                close()
                failure.message ?: "Microphone recording is unavailable"
            }
        }
    }

    override suspend fun stop(): VoiceRecording? = withContext(Dispatchers.IO) {
        val active = recorder ?: return@withContext null
        val file = output
        recorder = null
        output = null
        try {
            active.stop()
            active.release()
            file?.takeIf { it.length() > 0 }?.let {
                VoiceRecording("kelma-voice-${currentEpochMillis()}.m4a", it.readBytes())
            }
        } catch (_: RuntimeException) {
            active.release()
            null
        } finally {
            file?.delete()
        }
    }

    override fun close() {
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        output?.delete()
        output = null
        pendingPermission?.complete(false)
        pendingPermission = null
    }

    fun permissionResult(granted: Boolean) {
        pendingPermission?.complete(granted)
    }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private suspend fun requestMicrophonePermission(): Boolean {
        check(pendingPermission == null) { "A microphone permission request is already open" }
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        requestPermission()
        return try {
            deferred.await()
        } finally {
            pendingPermission = null
        }
    }
}
