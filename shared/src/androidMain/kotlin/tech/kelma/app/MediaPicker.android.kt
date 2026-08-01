package tech.kelma.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream

@Composable
actual fun rememberMediaPicker(): MediaPicker {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val picker = remember(context, scope) { AndroidMediaPicker(context, scope) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent(), picker::selected)
    picker.launch = launcher::launch
    return picker
}

private class AndroidMediaPicker(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
) : MediaPicker {
    var launch: (String) -> Unit = {}
    private var pending: CompletableDeferred<PickedMediaFile?>? = null

    override suspend fun pick(kind: AttachmentKind): PickedMediaFile? {
        check(pending == null) { "An attachment picker is already open" }
        val deferred = CompletableDeferred<PickedMediaFile?>()
        pending = deferred
        launch(if (kind == AttachmentKind.Image) "image/*" else "audio/*")
        return try {
            deferred.await()
        } finally {
            pending = null
        }
    }

    fun selected(uri: Uri?) {
        val deferred = pending ?: return
        if (uri == null) {
            deferred.complete(null)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val filename = queryFilename(uri) ?: "attachment"
                val mime = context.contentResolver.getType(uri) ?: mimeTypeForFilename(filename)
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) {
                    "Could not open the selected attachment"
                }.use { it.readAttachmentBytes() }
                deferred.complete(PickedMediaFile(filename, mime, bytes))
            } catch (exception: Exception) {
                deferred.completeExceptionally(exception)
            }
        }
    }

    private fun queryFilename(uri: Uri): String? = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}

private fun InputStream.readAttachmentBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= MaxAttachmentBytes) { "Attachments cannot exceed 100 MiB" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
