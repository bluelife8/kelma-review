package tech.kelma.app

interface AudioPlayer : AutoCloseable {
    fun play(media: CardMedia)
    fun pause(): Boolean
    fun seekBy(offsetMillis: Long): Boolean
    fun stop()
    override fun close()
}

expect fun createAudioPlayer(): AudioPlayer
