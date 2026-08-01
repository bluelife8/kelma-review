package tech.kelma.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal class KelmaSyncPuller(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val mediaCache: MediaCache,
) {
    suspend fun getSchedulerProfile(token: String): SchedulerProfileResponse {
        val response = httpClient.get("$baseUrl/v2/scheduler-profile") { bearerAuth(token) }
        ensureSuccess(response, "Scheduler profile download failed")
        return response.body()
    }

    suspend fun getStudyDayPolicy(token: String): AccountStudyDayPolicy {
        val response = httpClient.get("$baseUrl/v2/study-day-policy") { bearerAuth(token) }
        ensureSuccess(response, "Study-day policy download failed")
        return response.body<AccountStudyDayPolicy>().validated()
    }

    suspend fun pull(
        token: String,
        current: SyncedCollection,
        progress: suspend (SyncPullProgress) -> Unit,
    ): PullReport {
        val progressMutex = Mutex()
        suspend fun report(update: SyncPullProgress) = progressMutex.withLock { progress(update) }
        report(SyncPullProgress(SyncPullResource.Manifest, 0, 1))
        val manifestResponse = httpClient.get("$baseUrl/v2/sync/manifest") {
            bearerAuth(token)
            current.serverTime?.let { url.parameters.append("since", it) }
        }
        if (!manifestResponse.status.isSuccess()) {
            val error = runCatching { manifestResponse.body<SyncError>() }.getOrNull()
            throw KelmaSyncException(error?.message?.ifBlank { error.error } ?: "Sync failed")
        }
        val manifest = manifestResponse.body<SyncManifest>()
        report(SyncPullProgress(SyncPullResource.Manifest, 1, 1))
        val request = manifest.toBatchRequest()
        val recordCount = request.notes.size + request.cards.size + request.reviews.size +
            request.notetypes.size + request.decks.size
        val mediaEntries = manifest.media.filter { it.filename.isNotBlank() }.distinctBy(ManifestEntry::filename)
        val mediaCount = mediaEntries.size
        val mediaBytes = mediaEntries.sumOf { it.sizeBytes.coerceAtLeast(0) }
        if (recordCount > 0) report(SyncPullProgress(SyncPullResource.Records, 0, recordCount))
        if (mediaCount > 0) {
            report(SyncPullProgress(SyncPullResource.Media, 0, mediaCount, totalBytes = mediaBytes))
        }
        val downloads = coroutineScope {
            val records = async {
                pullRecords(token, request).also {
                    if (recordCount > 0) {
                        report(SyncPullProgress(SyncPullResource.Records, recordCount, recordCount))
                    }
                }
            }
            val media = async { pullMedia(token, mediaEntries, current, ::report) }
            records.await() to media.await()
        }
        return current.apply(
            manifest = manifest,
            pulled = downloads.first,
            downloadedMedia = downloads.second.files,
            remoteMediaMissing = downloads.second.missing,
        )
    }

    private suspend fun pullRecords(token: String, request: BatchPullRequest): BatchPullResponse {
        val hasRecords = request.notes.isNotEmpty() || request.cards.isNotEmpty() ||
            request.reviews.isNotEmpty() || request.notetypes.isNotEmpty() || request.decks.isNotEmpty()
        if (!hasRecords) return BatchPullResponse()
        val response = httpClient.post("$baseUrl/v2/batch/pull") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) {
            val error = runCatching { response.body<SyncError>() }.getOrNull()
            throw KelmaSyncException(error?.message?.ifBlank { error.error } ?: "Download failed")
        }
        return response.body<BatchPullResponse>().also { validateBatch(request, it) }
    }

    private suspend fun pullMedia(
        token: String,
        entries: List<ManifestEntry>,
        current: SyncedCollection,
        progress: suspend (SyncPullProgress) -> Unit,
    ): MediaPullResult {
        val files = mutableMapOf<String, SyncMediaFile>()
        val missing = mutableSetOf<String>()
        val validEntries = entries.filter { it.filename.isNotBlank() }.distinctBy(ManifestEntry::filename)
        val totalBytes = validEntries.sumOf { it.sizeBytes.coerceAtLeast(0) }
        val progressTracker = MediaPullProgressTracker(validEntries.size, totalBytes, progress)
        if (shouldUseMassMediaTar(validEntries, totalBytes)) {
            try {
                val tarFiles = KelmaSyncTarPuller(baseUrl, httpClient, mediaCache)
                    .pull(token, validEntries, progressTracker)
                return MediaPullResult(tarFiles, emptySet())
            } catch (_: MediaTarUnsupported) {
                // Older KelmaSync servers retain the direct per-file plan path.
            }
        }

        progressTracker.updateTransport("KelmaSync proxy · 64 concurrent")
        val targets = validEntries.map { it.proxiedTarget() }
        pullMediaTargets(token, targets, current, progressTracker).forEach { result ->
            result.file?.let { files[it.filename] = it }
            if (result.missing) missing += result.filename
        }
        return MediaPullResult(files, missing)
    }

    private suspend fun pullMediaTargets(
        token: String,
        targets: List<MediaDownloadTarget>,
        current: SyncedCollection,
        progress: MediaPullProgressTracker,
    ): List<MediaPullFile> = coroutineScope {
        val nextMutex = Mutex()
        var next = 0
        suspend fun nextTarget(): MediaDownloadTarget? = nextMutex.withLock {
            targets.getOrNull(next)?.also { next++ }
        }
        List(minOf(MediaDownloadWorkers, targets.size)) {
            async {
                buildList {
                    while (true) {
                        val target = nextTarget() ?: break
                        val result = pullMediaFile(
                            token,
                            target,
                            current,
                            onBytes = { progress.updateActive(target.entry.filename, it) },
                            onTransientFailure = {
                                progress.updateActive(target.entry.filename, 0)
                            },
                        )
                        add(result)
                        val size = target.expectedSizeBytes ?: target.entry.sizeBytes
                        progress.complete(
                            target.entry.filename,
                            size.takeIf { it > 0 } ?: (result.file?.bytes?.size?.toLong() ?: 0L),
                        )
                    }
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun pullMediaFile(
        token: String,
        target: MediaDownloadTarget,
        current: SyncedCollection,
        onBytes: suspend (Long) -> Unit,
        onTransientFailure: suspend () -> Unit,
    ): MediaPullFile {
        val stagingDataKey = syncMediaStagingDataKey(target.entry.filename)
        val stagingVersionKey = syncMediaStagingVersionKey(target.entry.filename)
        target.expectedSizeBytes?.let { expectedSize ->
            val (version, staged) = withContext(Dispatchers.Default) {
                mediaCache.read(stagingVersionKey)?.decodeToString() to mediaCache.read(stagingDataKey)
            }
            if (version == target.entry.modifiedAt && staged?.size?.toLong() == expectedSize) {
                withContext(Dispatchers.Default) { mediaCache.write(target.entry.filename, staged) }
                return MediaPullFile(
                    target.entry.filename,
                    SyncMediaFile(
                        target.entry.filename,
                        target.entry.modifiedAt,
                        if (mediaCache.retainsWrites) byteArrayOf() else staged,
                        expectedSize,
                    ),
                )
            }
            if (version != null || staged != null) {
                withContext(Dispatchers.Default) {
                    mediaCache.delete(stagingDataKey)
                    mediaCache.delete(stagingVersionKey)
                }
            }
        }

        var lastFailure: Exception? = null
        repeat(MaximumMediaDownloadAttempts) { attempt ->
            try {
                val response = httpClient.get(target.url) { bearerAuth(token) }
                if (response.status.value == 404 && target.entry.filename in current.media) {
                    return MediaPullFile(target.entry.filename, missing = true)
                }
                if (response.status.isSuccess()) {
                    val bytes = target.expectedSizeBytes?.let { expected ->
                        response.readPlannedMediaBytes(expected, onBytes)
                    } ?: response.body<ByteArray>()
                    if (target.expectedSizeBytes == null || bytes.size.toLong() == target.expectedSizeBytes) {
                        if (target.expectedSizeBytes != null) {
                            try {
                                withContext(Dispatchers.Default) {
                                    mediaCache.write(stagingDataKey, bytes)
                                    mediaCache.write(stagingVersionKey, target.entry.modifiedAt.encodeToByteArray())
                                }
                            } catch (failure: Exception) {
                                throw TerminalMediaFailure(failure)
                            }
                        }
                        withContext(Dispatchers.Default) { mediaCache.write(target.entry.filename, bytes) }
                        return MediaPullFile(
                            filename = target.entry.filename,
                            file = SyncMediaFile(
                                target.entry.filename,
                                target.entry.modifiedAt,
                                if (mediaCache.retainsWrites) byteArrayOf() else bytes,
                                bytes.size.toLong(),
                            ),
                        )
                    }
                    val failure = KelmaSyncException("${target.entry.filename}: media size did not match the plan")
                    onTransientFailure()
                    if (attempt == MaximumMediaDownloadAttempts - 1) throw TerminalMediaFailure(failure)
                    lastFailure = failure
                } else {
                    val error = runCatching { response.body<SyncError>() }.getOrNull()
                    val detail = error?.message?.ifBlank { error.error } ?: "media download failed"
                    val failure = KelmaSyncException("${target.entry.filename}: $detail")
                    if (!response.status.isTransientMediaFailure()) throw TerminalMediaFailure(failure)
                    onTransientFailure()
                    if (attempt == MaximumMediaDownloadAttempts - 1) throw TerminalMediaFailure(failure)
                    lastFailure = failure
                }
            } catch (failure: TerminalMediaFailure) {
                throw failure.cause
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                onTransientFailure()
                val safeFailure = KelmaSyncException(
                    "Media connection failed; retry sync to resume completed downloads",
                )
                lastFailure = safeFailure
                if (attempt == MaximumMediaDownloadAttempts - 1) throw safeFailure
            }
            delay(mediaRetryDelayMillis(target.entry.filename, attempt))
        }
        throw lastFailure ?: KelmaSyncException("${target.entry.filename}: media download failed")
    }

    private suspend fun HttpResponse.readPlannedMediaBytes(
        expectedSize: Long,
        onBytes: suspend (Long) -> Unit,
    ): ByteArray {
        val bytes = ByteArray(expectedSize.toInt())
        val channel = bodyAsChannel()
        var offset = 0
        while (offset < bytes.size) {
            val read = channel.readAvailable(bytes, offset, bytes.size - offset)
            if (read < 0) break
            if (read == 0) {
                yield()
            } else {
                offset += read
                onBytes(offset.toLong())
            }
        }
        if (offset != bytes.size) throw KelmaSyncException("Media response ended before its planned size")
        val extra = ByteArray(1)
        while (true) {
            val read = channel.readAvailable(extra, 0, 1)
            if (read < 0) break
            if (read > 0) throw KelmaSyncException("Media response exceeded its planned size")
            yield()
        }
        return bytes
    }

    private fun ManifestEntry.proxiedTarget(): MediaDownloadTarget {
        require(sizeBytes in 0..MaximumProxiedMediaFileBytes) { "KelmaSync returned an invalid media size" }
        return MediaDownloadTarget(
            entry = this,
            url = "$baseUrl/v2/media/${filename.encodeURLPathPart()}",
            expectedSizeBytes = sizeBytes.takeIf { it > 0 },
        )
    }

    private suspend fun ensureSuccess(response: HttpResponse, fallback: String) {
        if (response.status.isSuccess()) return
        val error = runCatching { response.body<SyncError>() }.getOrNull()
        throw KelmaSyncException(error?.message?.ifBlank { error.error } ?: fallback)
    }
}

private data class MediaDownloadTarget(
    val entry: ManifestEntry,
    val url: String,
    val expectedSizeBytes: Long?,
)

private class TerminalMediaFailure(override val cause: Exception) : Exception(cause)

internal class MediaPullProgressTracker(
    private val totalFiles: Int,
    private val totalBytes: Long,
    private val progress: suspend (SyncPullProgress) -> Unit,
) {
    private val mutex = Mutex()
    private val activeBytes = mutableMapOf<String, Long>()
    private var completedFiles = 0
    private var completedBytes = 0L
    private var lastReportedFiles = 0
    private var lastReportedBytes = 0L
    private var lastReportedAt = currentEpochMillis()
    private var transportDetail = ""

    suspend fun updateTransport(detail: String) = mutex.withLock {
        if (detail == transportDetail) return@withLock
        transportDetail = detail
        reportIfNeeded(force = true)
    }

    suspend fun updateActive(filename: String, bytes: Long) = mutex.withLock {
        activeBytes[filename] = bytes.coerceAtLeast(0)
        reportIfNeeded(force = false)
    }

    suspend fun complete(filename: String, bytes: Long) = mutex.withLock {
        activeBytes.remove(filename)
        completedFiles++
        completedBytes += bytes.coerceAtLeast(0)
        reportIfNeeded(force = completedFiles == totalFiles || completedFiles - lastReportedFiles >= MediaProgressWindow)
    }

    private suspend fun reportIfNeeded(force: Boolean) {
        val currentBytes = if (totalBytes > 0) {
            (completedBytes + activeBytes.values.sum()).coerceIn(0, totalBytes)
        } else {
            0L
        }
        val visibleBytes = maxOf(lastReportedBytes, currentBytes)
        val now = currentEpochMillis()
        val byteDelta = visibleBytes - lastReportedBytes
        if (!force && byteDelta < MediaProgressByteWindow && now - lastReportedAt < MediaProgressIntervalMillis) return
        lastReportedFiles = completedFiles
        lastReportedBytes = visibleBytes
        lastReportedAt = now
        progress(
            SyncPullProgress(
                SyncPullResource.Media,
                completedFiles,
                totalFiles,
                visibleBytes,
                totalBytes,
                transportDetail,
            ),
        )
    }
}

private data class MediaPullResult(
    val files: Map<String, SyncMediaFile>,
    val missing: Set<String>,
)

private data class MediaPullFile(
    val filename: String,
    val file: SyncMediaFile? = null,
    val missing: Boolean = false,
)

private fun SyncManifest.toBatchRequest(): BatchPullRequest = BatchPullRequest(
    notes = notes.map { it.guid }.filter(String::isNotBlank).distinct(),
    cards = cards.map { it.cardId }.filter { it != 0L }.distinct(),
    reviews = reviews.map { it.reviewId }.filter { it != 0L }.distinct(),
    notetypes = notetypes.map { it.notetypeId }.filter { it != 0L }.distinct(),
    decks = decks.map { it.name }.filter(String::isNotBlank).distinct(),
)

private fun validateBatch(request: BatchPullRequest, response: BatchPullResponse) {
    val missing = buildList {
        val noteIds = response.notes.mapTo(mutableSetOf(), SyncNote::guid)
        request.notes.filterNotTo(this) { it in noteIds }
        val cardIds = response.cards.mapTo(mutableSetOf(), SyncCard::cardId)
        request.cards.filterNotTo(this) { it in cardIds }
        val reviewIds = response.reviews.mapTo(mutableSetOf(), SyncReview::reviewId)
        request.reviews.filterNotTo(this) { it in reviewIds }
        val notetypeIds = response.notetypes.mapTo(mutableSetOf(), SyncNotetype::notetypeId)
        request.notetypes.filterNotTo(this) { it in notetypeIds }
        val deckNames = response.decks.mapTo(mutableSetOf(), SyncDeck::name)
        request.decks.filterNotTo(this) { it in deckNames }
    }
    if (missing.isNotEmpty()) {
        throw KelmaSyncException("KelmaSync returned an incomplete batch (${missing.size} missing records)")
    }
}

private fun HttpStatusCode.isTransientMediaFailure(): Boolean =
    value == 408 || value == 429 || value in 500..599

private fun mediaRetryDelayMillis(filename: String, attempt: Int): Long =
    250L * (1L shl attempt) + (filename.hashCode().toLong() and 127L)

internal const val MediaDownloadWorkers = 64
private const val MaximumProxiedMediaFileBytes = 100L * 1_024L * 1_024L
private const val MaximumMediaDownloadAttempts = 4
private const val MediaProgressWindow = 16
private const val MediaProgressByteWindow = 8L * 1_024L * 1_024L
private const val MediaProgressIntervalMillis = 500L
