package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberVoiceRecorder(): VoiceRecorder = remember { JvmVoiceRecorder() }

private class JvmVoiceRecorder : VoiceRecorder {
    private val format = AudioFormat(44_100f, 16, 1, true, false)
    @Volatile
    private var recording = false
    private var line: TargetDataLine? = null
    private var captureThread: Thread? = null
    private var captured = ByteArrayOutputStream()

    override val isRecording: Boolean get() = recording

    override suspend fun start(): String? = withContext(Dispatchers.IO) {
        if (recording) return@withContext null
        try {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val target = AudioSystem.getLine(info) as TargetDataLine
            target.open(format)
            captured = ByteArrayOutputStream()
            line = target
            recording = true
            target.start()
            captureThread = Thread({ capture(target) }, "kelma-voice-recorder").apply {
                isDaemon = true
                start()
            }
            null
        } catch (failure: Exception) {
            recording = false
            line?.close()
            line = null
            failure.message ?: "Microphone recording is unavailable"
        }
    }

    override suspend fun stop(): VoiceRecording? = withContext(Dispatchers.IO) {
        if (!recording) return@withContext null
        recording = false
        line?.stop()
        line?.close()
        captureThread?.join(1_000)
        line = null
        captureThread = null
        val pcm = captured.toByteArray()
        if (pcm.isEmpty()) null else VoiceRecording(
            filename = "kelma-voice-${currentEpochMillis()}.wav",
            bytes = wavBytes(pcm, format),
        )
    }

    override fun close() {
        recording = false
        line?.stop()
        line?.close()
        line = null
        captureThread = null
        captured.reset()
    }

    private fun capture(target: TargetDataLine) {
        val buffer = ByteArray(4_096)
        while (recording) {
            val count = runCatching { target.read(buffer, 0, buffer.size) }.getOrDefault(-1)
            if (count <= 0) break
            captured.write(buffer, 0, count)
        }
    }
}

private fun wavBytes(pcm: ByteArray, format: AudioFormat): ByteArray = ByteArrayOutputStream().apply {
    fun little(value: Int, bytes: Int) {
        repeat(bytes) { index -> write(value shr (index * 8) and 0xff) }
    }
    val channels = format.channels
    val sampleRate = format.sampleRate.toInt()
    val bits = format.sampleSizeInBits
    write("RIFF".encodeToByteArray())
    little(36 + pcm.size, 4)
    write("WAVEfmt ".encodeToByteArray())
    little(16, 4)
    little(1, 2)
    little(channels, 2)
    little(sampleRate, 4)
    little(sampleRate * channels * bits / 8, 4)
    little(channels * bits / 8, 2)
    little(bits, 2)
    write("data".encodeToByteArray())
    little(pcm.size, 4)
    write(pcm)
}.toByteArray()
