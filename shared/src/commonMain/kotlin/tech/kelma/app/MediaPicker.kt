package tech.kelma.app

import androidx.compose.runtime.Composable

enum class AttachmentKind { Image, Audio }

internal const val MaxAttachmentBytes = 100 * 1024 * 1024

data class PickedMediaFile(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

interface MediaPicker {
    suspend fun pick(kind: AttachmentKind): PickedMediaFile?
}

@Composable
expect fun rememberMediaPicker(): MediaPicker

internal fun mimeTypeForFilename(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "ogg", "opus" -> "audio/ogg"
    "flac" -> "audio/flac"
    else -> "application/octet-stream"
}
