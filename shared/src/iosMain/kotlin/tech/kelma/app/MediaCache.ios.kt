package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.remove
import platform.posix.rename

@Composable
actual fun rememberMediaCache(namespace: String): MediaCache = remember(namespace) {
    IosMediaCache("${iosMediaCacheDirectory()}/$namespace")
}

@OptIn(ExperimentalForeignApi::class)
private class IosMediaCache(private val directory: String) : MediaCache {
    init {
        NSFileManager.defaultManager.createDirectoryAtPath(
            directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    override fun read(filename: String): ByteArray? {
        val file = fopen(path(filename), "rb") ?: return null
        return try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            if (size < 0) return null
            fseek(file, 0, SEEK_SET)
            ByteArray(size.toInt()).also { bytes ->
                bytes.usePinned { pinned -> fread(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file) }
            }
        } finally {
            fclose(file)
        }
    }

    override fun write(filename: String, bytes: ByteArray) {
        val destination = path(filename)
        val temporary = "$destination.${randomUuidString()}.tmp"
        val file = checkNotNull(fopen(temporary, "wb")) { "Could not open the media cache" }
        try {
            bytes.usePinned { pinned ->
                val written = fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                check(written.toLong() == bytes.size.toLong()) { "Could not write the media cache" }
            }
        } finally {
            fclose(file)
        }
        try {
            check(rename(temporary, destination) == 0) { "Could not publish the media cache file" }
        } finally {
            remove(temporary)
        }
    }

    override fun delete(filename: String) {
        remove(path(filename))
    }

    override fun clear() {
        val files = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null).orEmpty()
        files.forEach { name ->
            (name as? String)?.let { remove("$directory/$it") }
        }
    }

    private fun path(filename: String): String = "$directory/${mediaCacheKey(filename)}"
}

@OptIn(ExperimentalForeignApi::class)
private fun iosMediaCacheDirectory(): String {
    val root = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String ?: "."
    return "$root/Kelma/card-media"
}
