package tech.kelma.app

import android.media.MediaPlayer
import java.io.File

actual fun createAudioPlayer(): AudioPlayer = AndroidAudioPlayer()

private class AndroidAudioPlayer : AudioPlayer {
    private var player: MediaPlayer? = null
    private var temporaryFile: File? = null

    override fun play(media: CardMedia) {
        closePlayback()
        val extension = media.filename.substringAfterLast('.', "media")
        val file = File.createTempFile("kelma-audio-", ".$extension").apply {
            writeBytes(media.bytes)
        }
        temporaryFile = file
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { closePlayback() }
            prepare()
            start()
        }
    }

    override fun pause(): Boolean {
        val current = player ?: return false
        if (current.isPlaying) current.pause()
        return true
    }

    override fun seekBy(offsetMillis: Long): Boolean {
        val current = player ?: return false
        val target = (current.currentPosition.toLong() + offsetMillis)
            .coerceIn(0L, current.duration.toLong().coerceAtLeast(0L))
        current.seekTo(target.toInt())
        return true
    }

    override fun stop() {
        closePlayback()
    }

    override fun close() {
        closePlayback()
    }

    private fun closePlayback() {
        player?.release()
        player = null
        temporaryFile?.delete()
        temporaryFile = null
    }
}
