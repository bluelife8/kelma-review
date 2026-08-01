package tech.kelma.app

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

actual fun createAudioPlayer(): AudioPlayer = IosAudioPlayer()

@OptIn(ExperimentalForeignApi::class)
private class IosAudioPlayer : AudioPlayer {
    private var player: AVAudioPlayer? = null
    private var temporaryPath: String? = null

    override fun play(media: CardMedia) {
        closePlayback()
        if (media.bytes.isEmpty()) return
        AVAudioSession.sharedInstance().setCategory(
            AVAudioSessionCategoryPlayback,
            error = null,
        )
        val extension = media.filename.substringAfterLast('.', "media")
            .filter { it.isLetterOrDigit() }
            .take(8)
            .ifBlank { "media" }
        val path = NSTemporaryDirectory() + "kelma-card-audio.$extension"
        val file = fopen(path, "wb") ?: return
        media.bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1.convert(), media.bytes.size.convert(), file)
        }
        fclose(file)
        temporaryPath = path
        player = AVAudioPlayer(
            contentsOfURL = NSURL.fileURLWithPath(path),
            error = null,
        ).apply {
            prepareToPlay()
            play()
        }
    }

    override fun pause(): Boolean {
        val current = player ?: return false
        current.pause()
        return true
    }

    override fun seekBy(offsetMillis: Long): Boolean {
        val current = player ?: return false
        current.currentTime = (current.currentTime + offsetMillis / 1_000.0)
            .coerceIn(0.0, current.duration.coerceAtLeast(0.0))
        return true
    }

    override fun stop() {
        closePlayback()
    }

    override fun close() {
        closePlayback()
    }

    private fun closePlayback() {
        player?.stop()
        player = null
        temporaryPath?.let { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
        temporaryPath = null
    }
}
