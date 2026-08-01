package tech.kelma.app

import android.content.Context
import android.system.Os
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
actual fun rememberMediaCache(namespace: String): MediaCache {
    val context = LocalContext.current.applicationContext
    return remember(context, namespace) { AndroidMediaCache(context, namespace) }
}

private class AndroidMediaCache(context: Context, namespace: String) : MediaCache {
    private val directory = File(context.cacheDir, "card-media/$namespace")

    override fun read(filename: String): ByteArray? = file(filename).takeIf(File::isFile)?.readBytes()

    override fun write(filename: String, bytes: ByteArray) {
        directory.mkdirs()
        val destination = file(filename)
        val temporary = File(directory, ".${destination.name}.${System.nanoTime()}.tmp")
        temporary.writeBytes(bytes)
        try {
            Os.rename(temporary.absolutePath, destination.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    override fun delete(filename: String) {
        file(filename).delete()
    }

    override fun clear() {
        directory.listFiles()?.forEach(File::delete)
    }

    private fun file(filename: String): File = File(directory, mediaCacheKey(filename))
}
