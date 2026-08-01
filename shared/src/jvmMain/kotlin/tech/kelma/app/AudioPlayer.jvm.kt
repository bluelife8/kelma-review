package tech.kelma.app

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration

actual fun createAudioPlayer(): AudioPlayer = JvmAudioPlayer()

private class JvmAudioPlayer : AudioPlayer {
    @Volatile
    private var player: MediaPlayer? = null
    private var temporaryFile: File? = null

    override fun play(media: CardMedia) {
        val suffix = media.filename.substringAfterLast('.', "media")
            .takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
            ?.let { ".$it" }
            ?: ".media"
        val file = File.createTempFile("kelma-audio-", suffix).apply {
            writeBytes(media.bytes)
            deleteOnExit()
        }
        runOnFxThread {
            closePlaybackOnFxThread()
            temporaryFile = file
            player = MediaPlayer(Media(file.toURI().toString())).apply {
                setOnEndOfMedia(::closePlaybackOnFxThread)
                play()
            }
        }
    }

    override fun pause(): Boolean {
        val current = player ?: return false
        runOnFxThread { current.pause() }
        return true
    }

    override fun seekBy(offsetMillis: Long): Boolean {
        val current = player ?: return false
        runOnFxThread {
            val duration = current.totalDuration.toMillis().takeIf { it.isFinite() && it >= 0.0 }
                ?: return@runOnFxThread
            val target = (current.currentTime.toMillis() + offsetMillis).coerceIn(0.0, duration)
            current.seek(Duration.millis(target))
        }
        return true
    }

    override fun stop() {
        runOnFxThread(::closePlaybackOnFxThread)
    }

    override fun close() {
        runOnFxThread(::closePlaybackOnFxThread)
    }

    private fun closePlaybackOnFxThread() {
        player?.stop()
        player?.dispose()
        player = null
        temporaryFile?.delete()
        temporaryFile = null
    }
}

private val fxStartupAttempted = AtomicBoolean(false)

private fun runOnFxThread(action: () -> Unit) {
    if (fxStartupAttempted.compareAndSet(false, true)) {
        try {
            Platform.startup { Platform.setImplicitExit(false) }
        } catch (_: IllegalStateException) {
            // JavaFX was already started by another application component.
            Platform.setImplicitExit(false)
        }
    }
    if (Platform.isFxApplicationThread()) action() else Platform.runLater(action)
}
