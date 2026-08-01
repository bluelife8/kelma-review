package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

@Composable
actual fun rememberMediaPicker(): MediaPicker {
    val presenter = LocalUIViewController.current
    return remember(presenter) { IosMediaPicker(presenter) }
}

@OptIn(ExperimentalForeignApi::class)
private class IosMediaPicker(private val presenter: UIViewController) : MediaPicker {
    private var pending: CompletableDeferred<PickedMediaFile?>? = null
    private val delegate = DocumentPickerDelegate(::selected) { pending?.complete(null) }

    override suspend fun pick(kind: AttachmentKind): PickedMediaFile? {
        check(pending == null) { "An attachment picker is already open" }
        val deferred = CompletableDeferred<PickedMediaFile?>()
        pending = deferred
        val type = if (kind == AttachmentKind.Image) UTTypeImage else UTTypeAudio
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(type),
            asCopy = true,
        ).apply { delegate = this@IosMediaPicker.delegate }
        presenter.presentViewController(picker, animated = true, completion = null)
        return try {
            deferred.await()
        } finally {
            pending = null
        }
    }

    private fun selected(url: NSURL?) {
        if (url == null) {
            pending?.complete(null)
            return
        }
        val accessing = url.startAccessingSecurityScopedResource()
        try {
            val bytes = readSelectedFile(checkNotNull(url.path) { "Selected attachment has no path" })
            val filename = url.lastPathComponent ?: "attachment"
            pending?.complete(PickedMediaFile(filename, mimeTypeForFilename(filename), bytes))
        } catch (exception: Exception) {
            pending?.completeExceptionally(exception)
        } finally {
            if (accessing) url.stopAccessingSecurityScopedResource()
        }
    }

    private fun readSelectedFile(path: String): ByteArray {
        val file = checkNotNull(fopen(path, "rb")) { "Could not open the selected attachment" }
        return try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            check(size >= 0) { "Could not measure the selected attachment" }
            require(size <= MaxAttachmentBytes) { "Attachments cannot exceed 100 MiB" }
            fseek(file, 0, SEEK_SET)
            if (size == 0L) return ByteArray(0)
            ByteArray(size.toInt()).also { bytes ->
                bytes.usePinned { pinned ->
                    val read = fread(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                    check(read.toLong() == bytes.size.toLong()) { "Could not read the selected attachment" }
                }
            }
        } finally {
            fclose(file)
        }
    }
}

private class DocumentPickerDelegate(
    private val onSelected: (NSURL?) -> Unit,
    private val onCancelled: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onSelected(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onCancelled()
    }
}
