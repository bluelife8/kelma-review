package tech.kelma.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KelmaSyncClientContractTest {
    @Test
    fun incompleteBatchCannotAdvanceCursor() = runBlocking {
        val current = SyncedCollection(serverTime = "old-cursor")
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v2/sync/manifest" -> respondJson(
                    SyncManifest(
                        notes = listOf(ManifestEntry(guid = "missing-note")),
                        serverTime = "new-cursor",
                    ),
                )
                "/v2/batch/pull" -> respondJson(BatchPullResponse())
                else -> error("Unexpected request ${request.url}")
            }
        }
        val client = contractClient(engine)

        val error = assertFailsWith<KelmaSyncException> { client.pull("token", current) }

        assertEquals("old-cursor", current.serverTime)
        assertEquals("KelmaSync returned an incomplete batch (1 missing records)", error.message)
        client.close()
    }

    @Test
    fun failedMediaDownloadFailsTheWholePull() = runBlocking {
        val current = SyncedCollection(serverTime = "old-cursor")
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v2/sync/manifest" -> respondJson(
                    SyncManifest(
                        media = listOf(ManifestEntry(filename = "missing.mp3")),
                        serverTime = "new-cursor",
                    ),
                )
                else -> respondJson(
                    SyncError("media_missing", "Media is unavailable"),
                    status = HttpStatusCode.NotFound,
                )
            }
        }
        val client = contractClient(engine)

        val error = assertFailsWith<KelmaSyncException> { client.pull("token", current) }

        assertEquals("old-cursor", current.serverTime)
        assertEquals("missing.mp3: Media is unavailable", error.message)
        client.close()
    }

    @Test
    fun missingRemoteMediaWithLocalBytesIsReportedForDurableRepair() = runBlocking {
        val bytes = byteArrayOf(4, 5, 6)
        val current = SyncedCollection(
            media = mapOf("repair.mp3" to SyncMediaFile("repair.mp3", "old", bytes)),
            serverTime = "old-cursor",
        )
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v2/sync/manifest" -> respondJson(
                    SyncManifest(
                        media = listOf(ManifestEntry(filename = "repair.mp3")),
                        serverTime = "new-cursor",
                    ),
                )
                "/v2/media/repair.mp3" -> respondJson(
                    SyncError("not_found", "media blob missing"),
                    status = HttpStatusCode.NotFound,
                )
                else -> error("Unexpected media repair request ${request.url}")
            }
        }
        val client = contractClient(engine)
        val progress = mutableListOf<SyncPullProgress>()

        val report = client.pull("token", current) { progress += it }

        assertEquals(setOf("repair.mp3"), report.remoteMediaMissing)
        assertContentEquals(bytes, report.collection.media.getValue("repair.mp3").bytes)
        assertEquals("new-cursor", report.collection.serverTime)
        assertEquals(listOf(0, 1), progress.filter {
            it.resource == SyncPullResource.Manifest
        }.map(SyncPullProgress::completed))
        assertEquals(listOf(0, 0, 1), progress.filter {
            it.resource == SyncPullResource.Media
        }.map(SyncPullProgress::completed))
        client.close()
    }

    @Test
    fun ordinaryMediaPullUses64AuthenticatedKelmaSyncWorkers() = runBlocking {
        val filenames = (1..501).map { "audio-$it.mp3" }
        val mediaCache = RecordingContractMediaCache()
        val mediaRequests = AtomicInteger()
        val activeRequests = AtomicInteger()
        val maximumActive = AtomicInteger()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v2/sync/manifest" -> respondJson(
                    SyncManifest(
                        media = filenames.map { ManifestEntry(filename = it, sizeBytes = 1) },
                        serverTime = "media-cursor",
                    ),
                )
                else -> {
                    assertTrue(request.url.encodedPath.startsWith("/v2/media/audio-"))
                    assertEquals("contract.kelma.invalid", request.url.host)
                    assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
                    mediaRequests.incrementAndGet()
                    val active = activeRequests.incrementAndGet()
                    maximumActive.updateAndGet { maxOf(it, active) }
                    delay(5)
                    activeRequests.decrementAndGet()
                    respond(
                        content = byteArrayOf(1),
                        headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                    )
                }
            }
        }
        val client = contractClient(engine, mediaCache)
        val progress = mutableListOf<SyncPullProgress>()

        val report = client.pull("token", SyncedCollection()) { progress += it }

        assertEquals(501, report.collection.media.size)
        val mediaProgress = progress.filter { it.resource == SyncPullResource.Media }.map(SyncPullProgress::completed)
        assertEquals(0, mediaProgress.first())
        assertEquals(501, mediaProgress.last())
        assertEquals(501L, progress.last { it.resource == SyncPullResource.Media }.completedBytes)
        assertEquals(501L, progress.last { it.resource == SyncPullResource.Media }.totalBytes)
        assertTrue(mediaProgress.zipWithNext().all { (before, after) -> after - before in 0..16 })
        assertEquals(501, mediaRequests.get())
        assertTrue(maximumActive.get() in 32..64)

        val resumed = client.pull("token", SyncedCollection())
        assertEquals(501, resumed.collection.media.size)
        assertEquals(501, mediaRequests.get())
        client.close()
    }

    @Test
    fun massMediaPullUsesAuthenticatedStreamingTar() = runBlocking {
        val filenames = (0 until 1_000).map { "mass-$it.bin" }
        val tarRequests = mutableListOf<String>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v2/sync/manifest" -> respondJson(
                    SyncManifest(
                        media = filenames.map { ManifestEntry(filename = it, sizeBytes = 1, modifiedAt = "v1") },
                        serverTime = "mass-cursor",
                    ),
                )
                "/v2/media/prepare-tar" -> {
                    tarRequests += "prepare"
                    assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
                    val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    val requested = Json.decodeFromString<MediaFilenamesRequest>(body).filenames
                    assertEquals(filenames, requested)
                    respondJson(
                        PreparedMediaTarResponse("prepared-job", 1_025_024, 1_000, 1_000, "later"),
                        status = HttpStatusCode.Created,
                    )
                }
                "/v2/media/prepared-tar/prepared-job" -> when (request.method.value) {
                    "GET" -> {
                        tarRequests += "download"
                        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
                        respond(
                            content = testMediaTar(filenames.indices.map { byteArrayOf((it % 251).toByte()) }),
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf("application/x-tar"),
                                "X-Kelma-Media-Files" to listOf("1000"),
                                "X-Kelma-Media-Bytes" to listOf("1000"),
                            ),
                        )
                    }
                    "DELETE" -> {
                        tarRequests += "delete"
                        respond(content = byteArrayOf(), status = HttpStatusCode.NoContent)
                    }
                    else -> error("Unexpected prepared media method ${request.method}")
                }
                else -> error("Unexpected mass media request ${request.url}")
            }
        }
        val mediaCache = RecordingContractMediaCache()
        val client = contractClient(engine, mediaCache)
        val progress = mutableListOf<SyncPullProgress>()

        val report = client.pull("token", SyncedCollection(), progress::add)

        assertEquals(listOf("prepare", "download", "delete"), tarRequests)
        assertEquals(1_000, report.collection.media.size)
        assertTrue(report.collection.media.values.all { it.bytes.isEmpty() && it.sizeBytes == 1L })
        assertContentEquals(byteArrayOf(0), mediaCache.read("mass-0.bin"))
        assertContentEquals(byteArrayOf((999 % 251).toByte()), mediaCache.read("mass-999.bin"))
        val finalProgress = progress.last { it.resource == SyncPullResource.Media }
        assertEquals(1_000, finalProgress.completed)
        assertEquals(1_000L, finalProgress.completedBytes)
        assertEquals(1_000L, finalProgress.totalBytes)
        client.close()
    }

    @Test
    fun massMediaDownloadsWhicheverPreparedTarFinishesFirst() = runBlocking {
        val filenames = (0..10_000).map { "pipeline-$it.bin" }
        val secondPreparationStarted = CompletableDeferred<Unit>()
        val downloads = mutableListOf<String>()
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v2/sync/manifest" -> respondJson(
                    SyncManifest(
                        media = filenames.map { ManifestEntry(filename = it, sizeBytes = 1, modifiedAt = "v1") },
                        serverTime = "pipeline-cursor",
                    ),
                )
                "/v2/media/prepare-tar" -> {
                    val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    val requested = Json.decodeFromString<MediaFilenamesRequest>(body).filenames
                    if (requested.size == 10_000) {
                        secondPreparationStarted.await()
                        delay(25)
                        respondJson(PreparedMediaTarResponse("large-job", 10_241_024, 10_000, 10_000, "later"),
                            status = HttpStatusCode.Created)
                    } else {
                        secondPreparationStarted.complete(Unit)
                        respondJson(PreparedMediaTarResponse("small-job", 2_048, 1, 1, "later"),
                            status = HttpStatusCode.Created)
                    }
                }
                "/v2/media/prepared-tar/small-job" -> when (request.method.value) {
                    "GET" -> {
                        downloads += "small-job"
                        respond(
                            testMediaTar(listOf(byteArrayOf(7))),
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf("application/x-tar"),
                                "X-Kelma-Media-Files" to listOf("1"),
                                "X-Kelma-Media-Bytes" to listOf("1"),
                            ),
                        )
                    }
                    else -> respond(content = byteArrayOf(), status = HttpStatusCode.NotFound)
                }
                "/v2/media/prepared-tar/large-job" -> when (request.method.value) {
                    "GET" -> {
                        downloads += "large-job"
                        respond(
                            testMediaTar(List(10_000) { byteArrayOf(3) }),
                            headers = headersOf(
                                HttpHeaders.ContentType to listOf("application/x-tar"),
                                "X-Kelma-Media-Files" to listOf("10000"),
                                "X-Kelma-Media-Bytes" to listOf("10000"),
                            ),
                        )
                    }
                    else -> respond(content = byteArrayOf(), status = HttpStatusCode.NotFound)
                }
                else -> error("Unexpected pipeline request ${request.method} ${request.url}")
            }
        }
        val client = contractClient(engine, RecordingContractMediaCache())

        val report = client.pull("token", SyncedCollection())

        assertEquals(listOf("small-job", "large-job"), downloads)
        assertEquals(10_001, report.collection.media.size)
        client.close()
    }

    @Test
    fun proxiedMediaConnectionFailureDoesNotExposeRequestDetails() = runBlocking {
        var proxiedAttempts = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v2/sync/manifest" -> respondJson(
                    SyncManifest(
                        media = listOf(ManifestEntry(filename = "private.jpg", sizeBytes = 1, modifiedAt = "v1")),
                        serverTime = "cursor",
                    ),
                )
                else -> {
                    assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
                    proxiedAttempts++
                    error("connect timeout for ${request.url}")
                }
            }
        }
        val client = contractClient(engine)

        val failure = assertFailsWith<KelmaSyncException> {
            client.pull("token", SyncedCollection())
        }

        assertEquals(4, proxiedAttempts)
        assertEquals("Media connection failed; retry sync to resume completed downloads", failure.message)
        assertTrue("private.jpg" !in failure.message.orEmpty())
        client.close()
    }

    @Test
    fun pushAcknowledgesAcceptedRowsAndPreservesConflicts() = runBlocking {
        val requests = mutableListOf<String>()
        var batchPushCount = 0
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            when (request.url.encodedPath) {
                "/v2/batch/push" -> when (batchPushCount++) {
                    0 -> respondJson(BatchPushResponse(accepted = mapOf("reviews" to 1)))
                    1 -> respondJson(
                        BatchPushResponse(
                            accepted = mapOf("notes" to 0),
                            conflicts = mapOf(
                                "notes" to listOf(
                                    SyncPushConflictEntry(
                                        guid = "conflicted-note",
                                        server = buildJsonObject { put("guid", "conflicted-note") },
                                    ),
                                ),
                            ),
                        ),
                    )
                    2 -> respondJson(BatchPushResponse(accepted = mapOf("decks" to 1)))
                    3 -> {
                        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        val batch = ContractJson.decodeFromString<BatchPushRequest>(body)
                        assertEquals(listOf(9L), batch.cards.map(BatchCardPushItem::cardId))
                        assertEquals(123L, batch.cards.single().scheduleResetThroughReviewId)
                        respondJson(BatchPushResponse(accepted = mapOf("cards" to 1)))
                    }
                    else -> error("Unexpected batch push")
                }
                "/v2/media/sound%20clip.mp3" -> respondJson(buildJsonObject { put("filename", "sound clip.mp3") })
                "/v2/batch/delete" -> respondJson(
                    BatchDeleteResponse(
                        requested = mapOf("notes" to 0, "cards" to 0, "notetypes" to 0, "decks" to 1),
                        deleted = mapOf("notes" to 0, "cards" to 0, "notetypes" to 0, "decks" to 1),
                    ),
                )
                else -> error("Unexpected push request ${request.url}")
            }
        }
        val client = contractClient(engine)
        val timestamp = "2027-01-15T08:00:00.000Z"
        val plan = SyncUploadPlan(
            reviews = listOf(ReviewPushBody(100, 7, "note", 0, "Deck", 3, 5, 1, 2_500, 500, 1)),
            media = listOf(
                PendingMediaUpload("sound clip.mp3", "audio/mpeg", "checksum", byteArrayOf(1, 2, 3)),
            ),
            cardScheduleResets = listOf(
                PendingCardResetUpload(
                    "note\u00000",
                    9L,
                    CardPushBody(
                        "note", "New", 0, JsonObject(emptyMap()), timestamp,
                        scheduleResetThroughReviewId = 123L,
                        scheduleResetClientModifiedAt = timestamp,
                    ),
                ),
            ),
            notes = listOf(
                PendingNoteUpload(
                    guid = "conflicted-note",
                    operation = "upsert",
                    body = NotePushBody(1, listOf("local"), emptyList(), timestamp, "base"),
                    notetype = null,
                    deck = null,
                    cards = listOf(
                        8L to CardPushBody("conflicted-note", "Deck", 0, JsonObject(emptyMap()), timestamp),
                    ),
                    forceOverride = false,
                ),
            ),
            decks = listOf(
                PendingDeckUpload(
                    sourceName = "Old",
                    operation = "rename",
                    targetName = "New",
                    targetBody = DeckPushBody(JsonObject(emptyMap()), timestamp, ""),
                    cards = listOf(9L to CardPushBody("note", "New", 0, JsonObject(emptyMap()), timestamp)),
                    deleteRequest = BatchDeleteRequest(decks = listOf("Old")),
                    forceOverride = false,
                ),
            ),
        )

        val result = client.push("token", plan)

        assertEquals(setOf(100L), result.uploadedReviewIds)
        assertEquals(emptySet(), result.uploadedNoteGuids)
        assertEquals(setOf("Old"), result.uploadedDeckSources)
        assertEquals(setOf("note\u00000"), result.uploadedCardResetKeys)
        assertEquals(setOf("sound clip.mp3"), result.uploadedMediaFilenames)
        assertEquals("conflicted-note", result.conflicts.single().resourceKey)
        assertEquals(
            listOf(
                "POST /v2/batch/push",
                "PUT /v2/media/sound%20clip.mp3",
                "POST /v2/batch/push",
                "POST /v2/batch/push",
                "POST /v2/batch/push",
                "POST /v2/batch/delete",
            ),
            requests,
        )
        assertTrue(result.conflicts.single().serverJson.contains("conflicted-note"))
        client.close()
    }

    @Test
    fun largeContentUploadUsesBoundedBatchesAndAggregateProgress() = runBlocking {
        val responses = listOf(
            "decks" to 1,
            "notetypes" to 1,
            "notes" to 500,
            "notes" to 500,
            "notes" to 201,
            "cards" to 500,
            "cards" to 500,
            "cards" to 201,
        )
        var batchIndex = 0
        val engine = MockEngine { request ->
            assertEquals("/v2/batch/push", request.url.encodedPath)
            val (kind, count) = responses[batchIndex++]
            val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            val batch = ContractJson.decodeFromString<BatchPushRequest>(body)
            val actualCount = when (kind) {
                "decks" -> batch.decks.size
                "notetypes" -> batch.notetypes.size
                "notes" -> batch.notes.size
                else -> batch.cards.size
            }
            assertEquals(count, actualCount)
            respondJson(BatchPushResponse(accepted = mapOf(kind to count)))
        }
        val client = contractClient(engine)
        val timestamp = "2027-01-15T08:00:00.000Z"
        val plan = SyncUploadPlan(
            notes = (1..1_201).map { index ->
                PendingNoteUpload(
                    guid = "note-$index",
                    operation = "upsert",
                    body = NotePushBody(1, listOf("front-$index", "back-$index"), emptyList(), timestamp, ""),
                    notetype = 1L to NotetypePushBody(
                        "Basic",
                        JsonObject(emptyMap()),
                        timestamp,
                    ),
                    deck = "Deck" to DeckPushBody(JsonObject(emptyMap()), timestamp, ""),
                    cards = listOf(
                        index.toLong() to CardPushBody(
                            "note-$index",
                            "Deck",
                            0,
                            JsonObject(emptyMap()),
                            timestamp,
                        ),
                    ),
                    forceOverride = false,
                )
            },
        )
        val progress = mutableListOf<SyncPushProgress>()

        val result = client.push("token", plan, progress::add)

        assertEquals(8, batchIndex)
        assertEquals(1_201, result.uploadedNoteGuids.size)
        assertEquals(listOf(0, 1, 2), progress.filter {
            it.resource == SyncPushResource.Dependencies
        }.map(SyncPushProgress::completed))
        assertEquals(listOf(0, 500, 1_000, 1_201), progress.filter {
            it.resource == SyncPushResource.Cards
        }.map(SyncPushProgress::completed))
        client.close()
    }

    @Test
    fun forcedConflictResolutionKeepsItsBatchOverrideHeader() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("true", request.headers["Force-Override"])
            respondJson(BatchPushResponse(accepted = mapOf("notes" to 1)))
        }
        val client = contractClient(engine)
        val plan = SyncUploadPlan(
            notes = listOf(
                PendingNoteUpload(
                    guid = "forced-note",
                    operation = "upsert",
                    body = NotePushBody(1, listOf("front", "back"), emptyList(), "now", "old"),
                    notetype = null,
                    deck = null,
                    cards = emptyList(),
                    forceOverride = true,
                ),
            ),
        )

        val result = client.push("token", plan)

        assertEquals(setOf("forced-note"), result.uploadedNoteGuids)
        client.close()
    }

    @Test
    fun schedulerProfileGetPutAndConflictUseVersionedContract() = runBlocking {
        val requests = mutableListOf<String>()
        var profilePutCount = 0
        val acknowledged = contractProfileResponse(
            version = 4,
            idempotencyKey = "profile-request",
            desiredRetention = 0.93,
        )
        val conflictServer = contractProfileResponse(version = 5, desiredRetention = 0.88)
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            when {
                request.method.value == "GET" -> respondJson(contractProfileResponse(version = 3))
                profilePutCount++ == 0 -> respondJson(acknowledged)
                else -> respondJson(
                    SchedulerProfileConflictResponse(
                        error = "scheduler_profile_conflict",
                        server = conflictServer.profile,
                        client = SchedulerProfileSettings(
                            parameterSource = SchedulerProfileSource.Manual,
                            desiredRetention = 0.93,
                            retentionSource = SchedulerProfileSource.Manual,
                        ).toCandidate(3, "profile-request-2"),
                    ),
                    status = HttpStatusCode.Conflict,
                )
            }
        }
        val client = contractClient(engine)
        val downloaded = client.getSchedulerProfile("token")
        assertEquals(3, downloaded.profile.version)

        val candidate = SchedulerProfileSettings(
            parameterSource = SchedulerProfileSource.Manual,
            desiredRetention = 0.93,
            retentionSource = SchedulerProfileSource.Manual,
        ).toCandidate(3, "profile-request")
        val accepted = client.push("token", SyncUploadPlan(schedulerProfile = candidate))
        assertEquals(4, accepted.acknowledgedSchedulerProfile?.profile?.version)
        assertEquals(1, accepted.uploadedCount)

        val conflicting = client.push(
            "token",
            SyncUploadPlan(schedulerProfile = candidate.copy(idempotencyKey = "profile-request-2")),
        )
        assertEquals(SchedulerProfileConflictKind, conflicting.conflicts.single().kind)
        assertTrue(conflicting.conflicts.single().serverJson.contains("\"version\":5"))
        assertEquals(
            listOf(
                "GET /v2/scheduler-profile",
                "PUT /v2/scheduler-profile",
                "PUT /v2/scheduler-profile",
            ),
            requests,
        )
        client.close()
    }

    @Test
    fun studyDayPolicyHasAnIndependentFetchAndUpdateContract() = runBlocking {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            if (request.method.value == "GET") {
                respondJson(
                    AccountStudyDayPolicy(
                        version = 2,
                        timezoneId = "America/New_York",
                        dayStartHour = 4,
                    ),
                )
            } else {
                val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                val candidate = ContractJson.decodeFromString<StudyDayPolicyCandidate>(body)
                respondJson(
                    AccountStudyDayPolicy(
                        version = candidate.baseVersion + 1,
                        timezoneId = candidate.timezoneId,
                        dayStartHour = candidate.dayStartHour,
                        idempotencyKey = candidate.idempotencyKey,
                    ),
                )
            }
        }
        val client = contractClient(engine)

        val downloaded = client.getStudyDayPolicy("token")
        val saved = client.putStudyDayPolicy(
            "token",
            downloaded.copy(dayStartHour = 5).toCandidate("policy-request"),
        )

        assertEquals(3, saved.version)
        assertEquals(5, saved.dayStartHour)
        assertEquals(
            listOf("GET /v2/study-day-policy", "PUT /v2/study-day-policy"),
            requests,
        )
        client.close()
    }

    @Test
    fun loginErrorUsesServerMessage() = runBlocking {
        val engine = MockEngine {
            respondJson(
                SyncError("invalid_credentials", "Incorrect username or password"),
                status = HttpStatusCode.Unauthorized,
            )
        }
        val client = contractClient(engine)

        val error = assertFailsWith<KelmaSyncException> {
            client.login("wrong@example.com", "wrong")
        }

        assertEquals("Incorrect username or password", error.message)
        client.close()
    }
}

private fun contractClient(
    engine: MockEngine,
    mediaCache: MediaCache = NoOpMediaCache,
): KelmaSyncClient {
    val httpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(ContractJson) }
    }
    return KelmaSyncClient(
        endpoint = "https://contract.kelma.invalid",
        httpClient = httpClient,
        mediaCache = mediaCache,
    )
}

private class RecordingContractMediaCache : MediaCache {
    private val files = ConcurrentHashMap<String, ByteArray>()

    override fun read(filename: String): ByteArray? = files[filename]

    override fun write(filename: String, bytes: ByteArray) {
        files[filename] = bytes
    }

    override fun delete(filename: String) {
        files.remove(filename)
    }

    override fun clear() {
        files.clear()
    }
}

private inline fun <reified T> io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = ContractJson.encodeToString(value),
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private fun contractProfileResponse(
    version: Long,
    idempotencyKey: String = "",
    desiredRetention: Double = 0.90,
): SchedulerProfileResponse = SchedulerProfileResponse(
    profile = CloudSchedulerProfile(
        version = version,
        schedulerVersion = "6.0.0-kelma.1",
        idempotencyKey = idempotencyKey,
        desiredRetention = desiredRetention,
        retentionSource = if (desiredRetention == 0.90) {
            SchedulerProfileSource.Default
        } else {
            SchedulerProfileSource.Manual
        },
        configHash = "contract-$version-$desiredRetention",
    ),
)

private fun testMediaTar(files: List<ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    files.forEachIndexed { index, bytes ->
        val header = ByteArray(512)
        header.writeTarField(0, 100, index.toString())
        header.writeTarOctal(100, 8, 0x180)
        header.writeTarOctal(108, 8, 0)
        header.writeTarOctal(116, 8, 0)
        header.writeTarOctal(124, 12, bytes.size.toLong())
        header.writeTarOctal(136, 12, 0)
        for (offset in 148..155) header[offset] = ' '.code.toByte()
        header[156] = '0'.code.toByte()
        val checksum = header.sumOf { it.toInt() and 0xff }
        (checksum.toString(8).padStart(6, '0') + "\u0000 ")
            .encodeToByteArray()
            .copyInto(header, 148)
        output.write(header)
        output.write(bytes)
        repeat((512 - bytes.size % 512) % 512) { output.write(0) }
    }
    output.write(ByteArray(1_024))
    return output.toByteArray()
}

private fun ByteArray.writeTarField(offset: Int, length: Int, value: String) {
    val encoded = value.encodeToByteArray()
    encoded.copyInto(this, offset, endIndex = minOf(encoded.size, length - 1))
}

private fun ByteArray.writeTarOctal(offset: Int, length: Int, value: Long) {
    writeTarField(offset, length, value.toString(8).padStart(length - 1, '0'))
}

private val ContractJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
