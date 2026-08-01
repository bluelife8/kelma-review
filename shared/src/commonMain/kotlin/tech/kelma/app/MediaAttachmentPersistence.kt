package tech.kelma.app

import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaQueries

data class SavedMediaAttachment(
    val filename: String,
    val content: LocalContentSnapshot,
)

internal data class ImportedMediaSaveResult(
    val filenames: Map<String, String>,
    val added: Int,
    val renamed: Int,
)

internal class MediaAttachmentPersistence(
    private val queries: KelmaQueries,
    private val json: Json,
    private val cache: MediaCache,
) {
    fun save(
        requestedFilename: String,
        mimeType: String,
        bytes: ByteArray,
        nowMillis: Long,
    ): SavedMediaAttachment {
        val requested = normalizeMediaFilename(requestedFilename)
        require(bytes.isNotEmpty()) { "Attachment is empty" }
        require(bytes.size <= MaxAttachmentBytes) { "Attachments cannot exceed 100 MiB" }
        require(mimeType.startsWith("image/") || mimeType.startsWith("audio/")) {
            "Choose an image or audio file"
        }
        val checksum = SchedulerHistorySha256().update(bytes).hexDigest()
        val existingChecksums = existingChecksums()
        val filename = chooseFilename(requested, checksum, existingChecksums)
        cache.write(filename, bytes)
        queries.upsertLocalMedia(filename, mimeType, checksum, bytes, nowMillis)
        return SavedMediaAttachment(filename, loadLocalContentSnapshot(queries, json))
    }

    fun saveImported(
        files: List<ImportedMedia>,
        nowMillis: Long,
    ): ImportedMediaSaveResult {
        val existingChecksums = existingChecksums().toMutableMap()
        val filenames = mutableMapOf<String, String>()
        var added = 0
        var renamed = 0
        files.forEach { file ->
            val requested = normalizeMediaFilename(file.filename)
            require(file.bytes.isNotEmpty()) { "Imported media is empty: $requested" }
            require(file.bytes.size <= MaxAttachmentBytes) { "Imported media cannot exceed 100 MiB: $requested" }
            val checksum = SchedulerHistorySha256().update(file.bytes).hexDigest()
            val filename = chooseFilename(requested, checksum, existingChecksums)
            cache.write(filename, file.bytes)
            if (existingChecksums[filename] != checksum) {
                queries.upsertLocalMedia(
                    filename,
                    mimeTypeForFilename(filename),
                    checksum,
                    file.bytes,
                    nowMillis,
                )
                added++
            }
            if (filename != requested) renamed++
            existingChecksums[filename] = checksum
            filenames[requested] = filename
        }
        return ImportedMediaSaveResult(filenames, added, renamed)
    }

    fun queueRemoteRepairs(filenames: Set<String>, nowMillis: Long): Int {
        if (filenames.isEmpty()) return 0
        filenames.sorted().forEach { filename ->
            val bytes = cache.read(filename)
                ?: queries.selectMediaByFilename(filename) { _, _, stored, _ -> stored }
                    .executeAsOneOrNull()
                    ?.takeIf(ByteArray::isNotEmpty)
                ?: error("No local copy is available to repair $filename")
            save(filename, mimeTypeForFilename(filename), bytes, nowMillis)
        }
        return filenames.size
    }

    fun repairCache() {
        if (cache.retainsWrites) {
            queries.selectLegacyMediaFilenames().executeAsList().forEach { filename ->
                val legacy = queries.selectMediaByFilename(filename) { _, modified, bytes, sizeBytes ->
                    Triple(modified, bytes, maxOf(sizeBytes, bytes.size.toLong()))
                }.executeAsOneOrNull() ?: return@forEach
                if (legacy.second.isNotEmpty()) {
                    cache.write(filename, legacy.second)
                    queries.insertMedia(filename, legacy.first, byteArrayOf(), legacy.third)
                }
            }
        }
        queries.selectLocalMedia { filename, _, _, bytes, _, _ -> filename to bytes }.executeAsList()
            .forEach { (filename, bytes) -> cache.write(filename, bytes) }
    }

    fun cache(collection: SyncedCollection, filenames: Set<String>? = null) {
        val files = if (filenames == null) collection.media.values else filenames.mapNotNull(collection.media::get)
        files.filter { it.bytes.isNotEmpty() }.forEach { file -> cache.write(file.filename, file.bytes) }
    }

    fun clearSyncStaging(collection: SyncedCollection, filenames: Set<String>? = null) {
        val files = if (filenames == null) collection.media.values else filenames.mapNotNull(collection.media::get)
        files.forEach { file ->
            cache.delete(syncMediaStagingDataKey(file.filename))
            cache.delete(syncMediaStagingVersionKey(file.filename))
        }
    }

    fun clearCache() = cache.clear()

    fun loadDownloadedBytes(filename: String): ByteArray? = cache.read(filename)
        ?: queries.selectMediaByFilename(filename) { _, _, bytes, _ -> bytes }
            .executeAsOneOrNull()
            ?.takeIf(ByteArray::isNotEmpty)

    private fun existingChecksums(): Map<String, String> = buildMap {
        queries.selectMediaMetadata { filename, _, _ -> filename }.executeAsList().forEach { filename ->
            val existingBytes = cache.read(filename)
                ?: queries.selectMediaByFilename(filename) { _, _, bytes, _ -> bytes }.executeAsOneOrNull()
            existingBytes?.takeIf(ByteArray::isNotEmpty)?.let {
                put(filename, SchedulerHistorySha256().update(it).hexDigest())
            }
        }
        queries.selectLocalMedia { filename, _, existingChecksum, _, _, _ -> filename to existingChecksum }
            .executeAsList().forEach { (filename, existingChecksum) -> put(filename, existingChecksum) }
    }
}

private fun chooseFilename(
    requested: String,
    checksum: String,
    existingChecksums: Map<String, String>,
): String = when (existingChecksums[requested]) {
    null, checksum -> requested
    else -> contentAddressedFilename(requested, checksum, existingChecksums)
}

private fun contentAddressedFilename(
    requested: String,
    checksum: String,
    existingChecksums: Map<String, String>,
): String {
    for (suffixLength in listOf(8, 16, checksum.length)) {
        val suffix = "-${checksum.take(suffixLength)}"
        val extension = requested.substringAfterLast('.', "")
            .takeIf { requested.contains('.') && it.length in 1..32 }
            ?.let { ".$it" }.orEmpty()
        val base = requested.removeSuffix(extension).take((255 - suffix.length - extension.length).coerceAtLeast(1))
        val candidate = "$base$suffix$extension"
        val existing = existingChecksums[candidate]
        if (existing == null || existing == checksum) return candidate
    }
    error("Could not choose a unique attachment filename")
}
