package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Composable
actual fun rememberMediaCache(namespace: String): MediaCache = remember(namespace) {
    JvmMediaCache(File(desktopMediaCacheDirectory(), namespace))
}

internal class JvmMediaCache(private val directory: File) : MediaCache {
    override fun read(filename: String): ByteArray? = file(filename).takeIf(File::isFile)?.readBytes()

    override fun write(filename: String, bytes: ByteArray) {
        directory.mkdirs()
        val destination = file(filename)
        val temporary = File(directory, ".${destination.name}.${System.nanoTime()}.tmp")
        temporary.writeBytes(bytes)
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
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

private fun desktopMediaCacheDirectory(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") -> File(home, "Library/Caches/Kelma/media")
        os.contains("win") -> File(System.getenv("LOCALAPPDATA") ?: home, "Kelma/cache/media")
        else -> File(home, ".cache/kelma/media")
    }
}
