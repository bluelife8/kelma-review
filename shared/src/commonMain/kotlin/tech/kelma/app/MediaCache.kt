package tech.kelma.app

import androidx.compose.runtime.Composable

interface MediaCache {
    val retainsWrites: Boolean get() = true
    fun read(filename: String): ByteArray?
    fun write(filename: String, bytes: ByteArray)
    fun delete(filename: String)
    fun clear()
}

internal object NoOpMediaCache : MediaCache {
    override val retainsWrites: Boolean = false
    override fun read(filename: String): ByteArray? = null
    override fun write(filename: String, bytes: ByteArray) = Unit
    override fun delete(filename: String) = Unit
    override fun clear() = Unit
}

@Composable
expect fun rememberMediaCache(namespace: String): MediaCache

internal fun mediaCacheKey(filename: String): String =
    SchedulerHistorySha256().update(filename).hexDigest()

internal fun syncMediaStagingDataKey(filename: String): String = "sync-stage:data:$filename"

internal fun syncMediaStagingVersionKey(filename: String): String = "sync-stage:version:$filename"
