package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Composable
actual fun rememberMediaPicker(): MediaPicker = remember { JvmMediaPicker() }

private class JvmMediaPicker : MediaPicker {
    override suspend fun pick(kind: AttachmentKind): PickedMediaFile? {
        val file = suspendCancellableCoroutine<File?> { continuation ->
            SwingUtilities.invokeLater {
                val dialog = FileDialog(null as Frame?, "Choose ${kind.name.lowercase()}", FileDialog.LOAD)
                dialog.filenameFilter = java.io.FilenameFilter { _, name -> kind.accepts(name) }
                dialog.isVisible = true
                val selected = dialog.file?.let { File(dialog.directory, it) }
                dialog.dispose()
                if (continuation.isActive) continuation.resume(selected)
            }
        } ?: return null
        return withContext(Dispatchers.IO) {
            require(file.length() <= MaxAttachmentBytes) { "Attachments cannot exceed 100 MiB" }
            val mime = Files.probeContentType(file.toPath()) ?: mimeTypeForFilename(file.name)
            PickedMediaFile(file.name, mime, file.readBytes())
        }
    }
}

private fun AttachmentKind.accepts(filename: String): Boolean = when (this) {
    AttachmentKind.Image -> filename.substringAfterLast('.', "").lowercase() in
        setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
    AttachmentKind.Audio -> filename.substringAfterLast('.', "").lowercase() in
        setOf("mp3", "wav", "m4a", "aac", "ogg", "opus", "flac")
}
