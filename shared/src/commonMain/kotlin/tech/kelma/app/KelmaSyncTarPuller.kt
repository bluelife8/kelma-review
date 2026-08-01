package tech.kelma.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal class MediaTarUnsupported : Exception()

internal fun shouldUseMassMediaTar(entries: List<ManifestEntry>, totalBytes: Long): Boolean =
    entries.size >= MinimumMassMediaFiles ||
        (entries.size >= MinimumMassMediaFilesByBytes && totalBytes >= MinimumMassMediaBytes)

internal class KelmaSyncTarPuller(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val mediaCache: MediaCache,
) {
    suspend fun pull(
        token: String,
        entries: List<ManifestEntry>,
        progress: MediaPullProgressTracker,
    ): Map<String, SyncMediaFile> {
        val files = mutableMapOf<String, SyncMediaFile>()
        val pending = mutableListOf<ManifestEntry>()
        entries.forEach { entry ->
            val staged = readStagedMedia(entry)
            if (staged == null) {
                pending += entry
            } else {
                files[entry.filename] = staged
                progress.complete(entry.filename, entry.sizeBytes)
            }
        }
        val batches = pending.toMediaTarBatches()
        if (batches.isEmpty()) return files
        val preparationSlots = Semaphore(MaximumConcurrentMediaTarPreparations)
        val jobsMutex = Mutex()
        val preparedJobs = mutableMapOf<String, PreparedMediaTarResponse>()
        var preparedCount = 0
        var completedBatches = 0
        var downloading = false
        progress.updateTransport(mediaTarDetail(preparedCount, completedBatches, batches.size, downloading))
        try {
            coroutineScope {
                val ready = Channel<PreparedTarBatch>(Channel.UNLIMITED)
                batches.forEach { batch ->
                    launch {
                        preparationSlots.withPermit {
                            val prepared = prepareBatch(token, batch)
                            val counts = jobsMutex.withLock {
                                preparedJobs[prepared.jobId] = prepared
                                preparedCount++
                                Triple(preparedCount, completedBatches, downloading)
                            }
                            progress.updateTransport(
                                mediaTarDetail(counts.first, counts.second, batches.size, counts.third),
                            )
                            ready.send(PreparedTarBatch(batch, prepared))
                        }
                    }
                }
                repeat(batches.size) {
                    val next = ready.receive()
                    val started = jobsMutex.withLock {
                        downloading = true
                        Triple(preparedCount, completedBatches, downloading)
                    }
                    progress.updateTransport(
                        mediaTarDetail(started.first, started.second, batches.size, started.third),
                    )
                    val downloaded = downloadPreparedBatch(token, next.prepared, next.entries, progress)
                    files.putAll(downloaded)
                    deletePreparedTar(token, next.prepared.jobId)
                    val counts = jobsMutex.withLock {
                        preparedJobs.remove(next.prepared.jobId)
                        completedBatches++
                        downloading = false
                        Triple(preparedCount, completedBatches, downloading)
                    }
                    progress.updateTransport(
                        mediaTarDetail(counts.first, counts.second, batches.size, counts.third),
                    )
                }
                ready.close()
            }
        } finally {
            val leftovers = jobsMutex.withLock { preparedJobs.keys.toList() }
            withContext(NonCancellable) {
                leftovers.forEach { jobID -> runCatching { deletePreparedTar(token, jobID) } }
            }
        }
        return files
    }

    private suspend fun prepareBatch(
        token: String,
        batch: List<ManifestEntry>,
    ): PreparedMediaTarResponse {
        var lastFailure: Exception? = null
        repeat(MaximumMediaTarAttempts) { attempt ->
            var serverRetryDelayMillis = 0L
            try {
                val response = httpClient.post("$baseUrl/v2/media/prepare-tar") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(MediaFilenamesRequest(batch.map(ManifestEntry::filename)))
                    timeout {
                        requestTimeoutMillis = MediaTarRequestTimeoutMillis
                        socketTimeoutMillis = MediaTarSocketTimeoutMillis
                    }
                }
                if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed) {
                    response.body<ByteArray>()
                    throw MediaTarUnsupported()
                }
                if (response.status.isSuccess()) {
                    return response.body<PreparedMediaTarResponse>().also { prepared ->
                        require(prepared.jobId.isNotBlank() && prepared.files == batch.size) {
                            "KelmaSync returned invalid prepared media metadata"
                        }
                        require(prepared.payloadBytes == batch.sumOf { it.sizeBytes } && prepared.archiveBytes > 0) {
                            "KelmaSync returned inconsistent prepared media sizes"
                        }
                    }
                }
                response.body<ByteArray>()
                val failure = KelmaSyncException("Mass media preparation failed")
                if (!response.status.isTransientTarFailure()) throw TerminalMediaTarFailure(failure)
                serverRetryDelayMillis = response.retryAfterMillis()
                lastFailure = failure
            } catch (failure: MediaTarUnsupported) {
                throw failure
            } catch (failure: TerminalMediaTarFailure) {
                throw failure.cause
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                lastFailure = KelmaSyncException(
                    "Mass media preparation failed; retry sync to resume completed downloads",
                )
            }
            if (attempt == MaximumMediaTarAttempts - 1) throw requireNotNull(lastFailure)
            delay(maxOf(mediaTarRetryDelayMillis(attempt), serverRetryDelayMillis))
        }
        throw requireNotNull(lastFailure)
    }

    private suspend fun downloadPreparedBatch(
        token: String,
        prepared: PreparedMediaTarResponse,
        batch: List<ManifestEntry>,
        progress: MediaPullProgressTracker,
    ): Map<String, SyncMediaFile> {
        val files = mutableMapOf<String, SyncMediaFile>()
        var lastFailure: Exception? = null
        repeat(MaximumMediaTarAttempts) { attempt ->
            try {
                var completed = false
                httpClient.prepareGet("$baseUrl/v2/media/prepared-tar/${prepared.jobId}") {
                    bearerAuth(token)
                    timeout {
                        requestTimeoutMillis = MediaTarRequestTimeoutMillis
                        socketTimeoutMillis = MediaTarSocketTimeoutMillis
                    }
                }.execute { response ->
                    if (response.status.isSuccess()) {
                        validateResponseHeaders(response, batch)
                        response.readMediaTar(batch, files, progress)
                        completed = true
                    } else {
                        response.body<ByteArray>()
                        val failure = KelmaSyncException("Prepared media download failed")
                        if (!response.status.isTransientTarFailure()) throw TerminalMediaTarFailure(failure)
                        lastFailure = failure
                    }
                }
                if (completed) return files
            } catch (failure: TerminalMediaTarFailure) {
                throw failure.cause
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                lastFailure = KelmaSyncException(
                    "Prepared media connection failed; retry sync to resume completed downloads",
                )
            }
            if (attempt == MaximumMediaTarAttempts - 1) throw requireNotNull(lastFailure)
            delay(mediaTarRetryDelayMillis(attempt))
        }
        throw requireNotNull(lastFailure)
    }

    private suspend fun deletePreparedTar(token: String, jobID: String) {
        val response = httpClient.delete("$baseUrl/v2/media/prepared-tar/$jobID") { bearerAuth(token) }
        if (response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.NotFound) {
            response.body<ByteArray>()
            throw KelmaSyncException("Prepared media cleanup failed")
        }
    }

    private fun validateResponseHeaders(response: HttpResponse, entries: List<ManifestEntry>) {
        require(response.headers["X-Kelma-Media-Files"]?.toIntOrNull() == entries.size) {
            "Mass media response contained an invalid file count"
        }
        require(
            response.headers["X-Kelma-Media-Bytes"]?.toLongOrNull() == entries.sumOf { it.sizeBytes },
        ) { "Mass media response contained an invalid byte count" }
    }

    private suspend fun HttpResponse.readMediaTar(
        entries: List<ManifestEntry>,
        files: MutableMap<String, SyncMediaFile>,
        progress: MediaPullProgressTracker,
    ) {
        val channel = bodyAsChannel()
        val completedIndexes = mutableSetOf<Int>()
        var zeroBlocks = 0
        while (zeroBlocks < 2) {
            val header = channel.readTarBlock()
            if (header.all { it == 0.toByte() }) {
                zeroBlocks++
                continue
            }
            require(zeroBlocks == 0) { "Media TAR resumed after its end marker" }
            require(header.hasValidTarChecksum()) { "Media TAR header checksum did not match" }
            val index = header.tarString(0, 100).toIntOrNull()
                ?: throw KelmaSyncException("Media TAR contained an invalid entry")
            require(index in entries.indices && completedIndexes.add(index)) {
                "Media TAR contained an unexpected entry"
            }
            val entry = entries[index]
            val size = header.tarOctal(124, 12)
            require(size == entry.sizeBytes && size in 0..MaximumTarMediaFileBytes) {
                "Media TAR entry size did not match the manifest"
            }
            require(header[156] == 0.toByte() || header[156] == '0'.code.toByte()) {
                "Media TAR contained a non-file entry"
            }
            if (entry.filename in files) {
                channel.discardTarBytes(size)
                val padding = ((TarBlockBytes - size % TarBlockBytes) % TarBlockBytes).toInt()
                if (padding > 0) channel.readTarBytes(ByteArray(padding))
            } else {
                readEntry(channel, entry, size, files, progress)
            }
        }
        require(completedIndexes.size == entries.size) { "Media TAR did not contain every requested file" }
    }

    private suspend fun readEntry(
        channel: ByteReadChannel,
        entry: ManifestEntry,
        size: Long,
        files: MutableMap<String, SyncMediaFile>,
        progress: MediaPullProgressTracker,
    ) {
        val bytes = ByteArray(size.toInt())
        try {
            channel.readTarBytes(bytes) { transferred ->
                progress.updateActive(entry.filename, transferred)
            }
            val padding = ((TarBlockBytes - size % TarBlockBytes) % TarBlockBytes).toInt()
            if (padding > 0) channel.readTarBytes(ByteArray(padding))
            withContext(Dispatchers.Default) {
                mediaCache.write(syncMediaStagingDataKey(entry.filename), bytes)
                mediaCache.write(
                    syncMediaStagingVersionKey(entry.filename),
                    entry.modifiedAt.encodeToByteArray(),
                )
            }
            withContext(Dispatchers.Default) { mediaCache.write(entry.filename, bytes) }
            files[entry.filename] = SyncMediaFile(
                entry.filename,
                entry.modifiedAt,
                if (mediaCache.retainsWrites) byteArrayOf() else bytes,
                size,
            )
            progress.complete(entry.filename, size)
        } catch (failure: Exception) {
            progress.updateActive(entry.filename, 0)
            throw failure
        }
    }

    private suspend fun readStagedMedia(entry: ManifestEntry): SyncMediaFile? {
        if (entry.sizeBytes !in 0..MaximumTarMediaFileBytes) return null
        val (version, bytes) = withContext(Dispatchers.Default) {
            mediaCache.read(syncMediaStagingVersionKey(entry.filename))?.decodeToString() to
                mediaCache.read(syncMediaStagingDataKey(entry.filename))
        }
        return bytes?.takeIf { version == entry.modifiedAt && it.size.toLong() == entry.sizeBytes }
            ?.let {
                withContext(Dispatchers.Default) { mediaCache.write(entry.filename, it) }
                SyncMediaFile(
                    entry.filename,
                    entry.modifiedAt,
                    if (mediaCache.retainsWrites) byteArrayOf() else it,
                    entry.sizeBytes,
                )
            }
    }
}

private class TerminalMediaTarFailure(override val cause: Exception) : Exception(cause)

private data class PreparedTarBatch(
    val entries: List<ManifestEntry>,
    val prepared: PreparedMediaTarResponse,
)

private fun List<ManifestEntry>.toMediaTarBatches(): List<List<ManifestEntry>> {
    val batches = mutableListOf<List<ManifestEntry>>()
    var batch = mutableListOf<ManifestEntry>()
    var bytes = 0L
    for (entry in this) {
        require(entry.sizeBytes in 0..MaximumTarMediaFileBytes) { "KelmaSync returned an invalid media size" }
        if (batch.isNotEmpty() &&
            (batch.size >= MaximumMediaTarFiles || bytes + entry.sizeBytes > MaximumMediaTarBytes)
        ) {
            batches += batch
            batch = mutableListOf()
            bytes = 0
        }
        batch += entry
        bytes += entry.sizeBytes
    }
    if (batch.isNotEmpty()) batches += batch
    return batches
}

private suspend fun ByteReadChannel.readTarBlock(): ByteArray =
    ByteArray(TarBlockBytes.toInt()).also { readTarBytes(it) }

private suspend fun ByteReadChannel.discardTarBytes(size: Long) {
    val buffer = ByteArray(minOf(64 * 1_024L, size.coerceAtLeast(1)).toInt())
    var remaining = size
    while (remaining > 0) {
        val chunk = minOf(buffer.size.toLong(), remaining).toInt()
        readTarBytes(if (chunk == buffer.size) buffer else ByteArray(chunk))
        remaining -= chunk
    }
}

private suspend fun ByteReadChannel.readTarBytes(
    bytes: ByteArray,
    onBytes: suspend (Long) -> Unit = {},
) {
    var offset = 0
    while (offset < bytes.size) {
        val read = readAvailable(bytes, offset, bytes.size - offset)
        if (read < 0) throw KelmaSyncException("Media TAR response ended early")
        if (read == 0) {
            yield()
        } else {
            offset += read
            onBytes(offset.toLong())
        }
    }
}

private fun ByteArray.tarString(offset: Int, length: Int): String =
    copyOfRange(offset, offset + length).takeWhile { it != 0.toByte() }.toByteArray().decodeToString().trim()

private fun ByteArray.tarOctal(offset: Int, length: Int): Long =
    tarString(offset, length).trim().ifEmpty { "0" }.toLongOrNull(8)
        ?: throw KelmaSyncException("Media TAR contained an invalid number")

private fun ByteArray.hasValidTarChecksum(): Boolean {
    val expected = tarOctal(148, 8)
    val actual = indices.sumOf { index ->
        if (index in 148..155) 32 else this[index].toInt() and 0xff
    }.toLong()
    return expected == actual
}

private fun HttpStatusCode.isTransientTarFailure(): Boolean =
    value == 408 || value == 429 || value in 500..599

private fun HttpResponse.retryAfterMillis(): Long = headers["Retry-After"]
    ?.toLongOrNull()
    ?.coerceIn(0, 60)
    ?.times(1_000L)
    ?: 0L

private fun mediaTarDetail(prepared: Int, completed: Int, total: Int, downloading: Boolean): String =
    "KelmaSync TAR pipeline · $prepared/$total prepared · $completed/$total downloaded · " +
        if (downloading) "downloading 1 · sequential" else "sequential"

private fun mediaTarRetryDelayMillis(attempt: Int): Long =
    250L * (1L shl attempt) + ("media-tar".hashCode().toLong() and 127L)

private const val MaximumTarMediaFileBytes = 100L * 1_024L * 1_024L
private const val MaximumMediaTarFiles = 10_000
private const val MaximumMediaTarBytes = 128L * 1_024L * 1_024L
private const val MinimumMassMediaFiles = 1_000
private const val MinimumMassMediaFilesByBytes = 128
private const val MinimumMassMediaBytes = 128L * 1_024L * 1_024L
private const val TarBlockBytes = 512L
private const val MediaTarRequestTimeoutMillis = 30L * 60L * 1_000L
private const val MediaTarSocketTimeoutMillis = 120L * 1_000L
internal const val MaximumConcurrentMediaTarPreparations = 5
private const val MaximumMediaTarAttempts = 4
