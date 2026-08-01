package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVEncoderAudioQualityKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

@Composable
actual fun rememberVoiceRecorder(): VoiceRecorder = remember { IosVoiceRecorder() }

@OptIn(ExperimentalForeignApi::class)
private class IosVoiceRecorder : VoiceRecorder {
    private var recorder: AVAudioRecorder? = null
    private var temporaryPath: String? = null

    override val isRecording: Boolean get() = recorder?.recording == true

    override suspend fun start(): String? {
        if (isRecording) return null
        val session = AVAudioSession.sharedInstance()
        if (!requestPermission(session)) return "Microphone permission was denied"
        return try {
            session.setCategory(AVAudioSessionCategoryPlayAndRecord, error = null)
            val path = NSTemporaryDirectory() + "kelma-own-voice.m4a"
            val settings = mapOf<Any?, Any?>(
                AVFormatIDKey to 0x61616320u,
                AVSampleRateKey to 44_100.0,
                AVNumberOfChannelsKey to 1,
                AVEncoderAudioQualityKey to 64,
            )
            val active = AVAudioRecorder(
                uRL = NSURL.fileURLWithPath(path),
                settings = settings,
                error = null,
            )
            check(active.prepareToRecord()) { "Could not prepare microphone recording" }
            check(active.record()) { "Could not start microphone recording" }
            temporaryPath = path
            recorder = active
            null
        } catch (failure: Exception) {
            close()
            failure.message ?: "Microphone recording is unavailable"
        }
    }

    override suspend fun stop(): VoiceRecording? {
        val active = recorder ?: return null
        recorder = null
        active.stop()
        val path = temporaryPath
        temporaryPath = null
        return try {
            path?.let(::readRecording)?.takeIf(ByteArray::isNotEmpty)?.let {
                VoiceRecording("kelma-voice-${currentEpochMillis()}.m4a", it)
            }
        } finally {
            path?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
        }
    }

    override fun close() {
        recorder?.stop()
        recorder = null
        temporaryPath?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
        temporaryPath = null
    }

    private suspend fun requestPermission(session: AVAudioSession): Boolean {
        val result = CompletableDeferred<Boolean>()
        session.requestRecordPermission { granted -> result.complete(granted) }
        return result.await()
    }

    private fun readRecording(path: String): ByteArray {
        val file = fopen(path, "rb") ?: return ByteArray(0)
        return try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            if (size <= 0) return ByteArray(0)
            fseek(file, 0, SEEK_SET)
            ByteArray(size.toInt()).also { bytes ->
                bytes.usePinned { pinned ->
                    val read = fread(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                    check(read.toLong() == bytes.size.toLong()) { "Could not read voice recording" }
                }
            }
        } finally {
            fclose(file)
        }
    }
}
