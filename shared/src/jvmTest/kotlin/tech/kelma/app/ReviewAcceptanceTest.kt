package tech.kelma.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import tech.kelma.db.KelmaDatabase

class ReviewAcceptanceTest {
    @Test
    fun syncReviewRestartResyncAndUndoWorkAsOneFlow() = runBlocking {
        val fixture = AcceptanceSyncFixture()
        val httpClient = HttpClient(fixture.engine) {
            install(ContentNegotiation) { json(AcceptanceJson) }
        }
        val syncClient = KelmaSyncClient(
            endpoint = "https://acceptance.kelma.invalid",
            clientLabel = "Kelma acceptance test",
            httpClient = httpClient,
        )
        val directory = Files.createTempDirectory("kelma-acceptance").toFile()
        val databaseFile = directory.resolve("kelma.db")
        val credentialVault = InMemoryCredentialVault()

        try {
            val auth = syncClient.login("reviewer@example.com", "not-persisted")
            assertEquals(AcceptanceToken, auth.token)
            val firstPull = syncClient.pull(auth.token, SyncedCollection())
            assertEquals(5, firstPull.collection.cards.size)
            assertEquals(1, firstPull.collection.reviews.size)
            assertEquals(1, firstPull.collection.studyDays.size)
            assertEquals(1, firstPull.collection.notetypes.size)
            assertEquals(1, firstPull.collection.deckRecords.size)
            assertContentEquals(
                AcceptanceMedia,
                firstPull.collection.media.getValue(AcceptanceMediaName).bytes,
            )

            var driver = openDesktopDatabase(databaseFile)
            var store = PersistentCollectionStore(KelmaDatabase(driver), credentialVault = credentialVault)
            store.observeCloudStudyDayPolicy(
                AccountStudyDayPolicy(version = 1, timezoneId = "UTC", dayStartHour = 0),
            )
            store.saveSignedInState(
                StoredSyncAuth(
                    token = auth.token,
                    clientId = auth.clientId,
                    endpoint = "https://acceptance.kelma.invalid",
                    username = "reviewer@example.com",
                ),
                firstPull.collection,
                AcceptanceNow,
            )

            val initialState = store.load(AcceptanceNow)
            val initialDeck = firstPull.collection.asDecks(
                initialState.localReviews.schedules,
                AcceptanceNow,
            ).single()
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), initialDeck.cards.map { it.id }.sorted())
            assertEquals(4, initialDeck.newCount)
            assertEquals(1, initialDeck.learningCount)
            assertEquals(0, initialDeck.dueCount)

            val ratings = mapOf(
                1L to Rating.Again,
                2L to Rating.Hard,
                3L to Rating.Good,
                4L to Rating.Easy,
            )
            ratings.forEach { (cardId, rating) ->
                store.recordReview(
                    card = firstPull.collection.cards.getValue(cardId),
                    rating = rating,
                    reviewedAtMillis = AcceptanceNow,
                    durationMillis = cardId * 1_000,
                )
            }
            val localAdded = store.addLocalNote(
                AddNoteDraft(AcceptanceDeck, "local front", "local back", listOf("acceptance")),
                AcceptanceNow,
                "local-acceptance-note",
            )
            val localCardId = localAdded.cardId
            val saved = store.load(AcceptanceNow)
            assertEquals(1, saved.localContent.cardCount)
            assertEquals(5, saved.localReviews.reviewedToday)
            assertEquals(ReviewPhase.Learning, saved.localReviews.schedules.getValue(1).phase)
            assertEquals(AcceptanceNow + 60_000, saved.localReviews.schedules.getValue(1).dueAtMillis)
            assertEquals(AcceptanceNow + 330_000, saved.localReviews.schedules.getValue(2).dueAtMillis)
            assertEquals(AcceptanceNow + 10 * 60_000, saved.localReviews.schedules.getValue(3).dueAtMillis)
            assertEquals(
                (epochDayAt(AcceptanceNow) + 8) * MillisPerDay,
                saved.localReviews.schedules.getValue(4).dueAtMillis,
            )
            assertEquals(
                listOf(localCardId, 1L, 2L, 3L, 5L),
                firstPull.collection.withLocalContent(saved.localContent)
                    .asDecks(saved.localReviews.schedules, AcceptanceNow).single().cards.map { it.id },
            )

            val uploadPlan = store.prepareSyncUpload()
            assertEquals(4, uploadPlan.reviews.size)
            assertEquals(listOf("local-acceptance-note"), uploadPlan.notes.map(PendingNoteUpload::guid))
            val pushed = syncClient.push(auth.token, uploadPlan)
            store.applySyncPushResult(pushed)
            assertEquals(5, pushed.uploadedCount)
            assertTrue(pushed.conflicts.isEmpty())

            driver.close()
            driver = openDesktopDatabase(databaseFile)
            store = PersistentCollectionStore(KelmaDatabase(driver), credentialVault = credentialVault)
            val restored = store.load(AcceptanceNow + 60_001)
            assertEquals(AcceptanceToken, restored.auth?.token)
            assertEquals(5, restored.localReviews.reviewedToday)
            assertEquals(1, restored.localContent.cardCount)
            assertTrue(store.prepareSyncUpload().isEmpty)
            assertEquals(
                listOf(localCardId, 1L, 2L, 3L, 5L).sorted(),
                restored.collection.withLocalContent(restored.localContent)
                    .asDecks(restored.localReviews.schedules, AcceptanceNow + 60_001)
                    .single().cards.map { it.id }.sorted(),
            )

            val graduated = store.recordReview(
                card = restored.collection.cards.getValue(1),
                rating = Rating.Easy,
                reviewedAtMillis = AcceptanceNow + 60_001,
            )
            assertEquals(ReviewPhase.Review, graduated.schedule.phase)
            assertEquals(1, graduated.schedule.scheduledDays)

            val incremental = syncClient.pull(auth.token, restored.collection)
            assertEquals("cursor-2", incremental.collection.serverTime)
            val afterSync = store.replaceCollection(incremental.collection, AcceptanceNow + 60_001)
            assertEquals(graduated.schedule, afterSync.schedules[1])
            assertEquals(6, afterSync.reviewedToday)
            assertEquals(1, store.loadLocalContent().cardCount)

            val undone = assertNotNull(store.undoLastReview(AcceptanceDeck, AcceptanceNow + 60_001))
            assertEquals(1L, undone.cardId)
            assertEquals(5, undone.snapshot.reviewedToday)
            assertEquals(AcceptanceNow + 60_000, undone.snapshot.schedules.getValue(1).dueAtMillis)
            assertEquals(
                listOf(localCardId, 1L, 2L, 3L, 5L).sorted(),
                incremental.collection.withLocalContent(store.loadLocalContent())
                    .asDecks(undone.snapshot.schedules, AcceptanceNow + 60_001)
                    .single().cards.map { it.id }.sorted(),
            )

            driver.close()
            driver = openDesktopDatabase(databaseFile)
            store = PersistentCollectionStore(KelmaDatabase(driver), credentialVault = credentialVault)
            val secondRestart = store.load(AcceptanceNow + 60_001)
            assertEquals(5, secondRestart.localReviews.reviewedToday)
            assertEquals(1, secondRestart.localContent.cardCount)
            assertEquals(AcceptanceNow + 60_000, secondRestart.localReviews.schedules.getValue(1).dueAtMillis)

            store.clearAll()
            val signedOut = store.load(AcceptanceNow + 60_001)
            assertNull(signedOut.auth)
            assertTrue(signedOut.collection.cards.isEmpty())
            assertTrue(signedOut.localReviews.schedules.isEmpty())
            assertFalse(signedOut.localReviews.canUndo)
            driver.close()

            assertEquals(2, fixture.manifestRequests)
            assertEquals(1, fixture.batchRequests)
            assertEquals(1, fixture.mediaRequests)
            assertEquals(4, fixture.pushRequests)
            assertTrue(fixture.incrementalCursorWasSent)
        } finally {
            syncClient.close()
            directory.deleteRecursively()
        }
    }
}

private class AcceptanceSyncFixture {
    var manifestRequests = 0
    var batchRequests = 0
    var mediaRequests = 0
    var pushRequests = 0
    var incrementalCursorWasSent = false

    val engine = MockEngine { request ->
        when (request.url.encodedPath) {
            "/v2/auth/login" -> respondJson(LoginResponse(AcceptanceToken, "client-acceptance"))
            "/v2/sync/manifest" -> {
                assertBearer(request.headers[HttpHeaders.Authorization])
                manifestRequests++
                if (manifestRequests == 1) {
                    respondJson(acceptanceManifest())
                } else {
                    incrementalCursorWasSent = request.url.parameters["since"] == "cursor-1"
                    respondJson(SyncManifest(serverTime = "cursor-2"))
                }
            }
            "/v2/batch/pull" -> {
                assertBearer(request.headers[HttpHeaders.Authorization])
                batchRequests++
                respondJson(acceptanceBatch())
            }
            "/v2/media/download-plan" -> respond(
                content = byteArrayOf(),
                status = HttpStatusCode.MethodNotAllowed,
            )
            "/v2/batch/push" -> {
                assertBearer(request.headers[HttpHeaders.Authorization])
                val accepted = when (pushRequests++) {
                    0 -> mapOf("reviews" to 4)
                    1 -> mapOf("notetypes" to 1)
                    2 -> mapOf("notes" to 1)
                    3 -> mapOf("cards" to 1)
                    else -> error("Unexpected extra batch push")
                }
                respondJson(BatchPushResponse(accepted = accepted))
            }
            else -> {
                if (request.url.encodedPath.contains("/v2/media/")) {
                    assertBearer(request.headers[HttpHeaders.Authorization])
                    mediaRequests++
                    respond(
                        content = AcceptanceMedia,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                    )
                } else {
                    error("Unexpected acceptance request: ${request.method.value} ${request.url}")
                }
            }
        }
    }

    private fun assertBearer(value: String?) {
        assertEquals("Bearer $AcceptanceToken", value)
    }

    private fun acceptanceManifest() = SyncManifest(
        notes = (1L..5L).map { ManifestEntry(guid = "note-$it") },
        cards = (1L..5L).map { ManifestEntry(cardId = it) },
        reviews = listOf(ManifestEntry(reviewId = AcceptanceNow)),
        studyDays = listOf(SyncStudyDay(epochDayAt(AcceptanceNow), AcceptanceDeck, reviewStudied = 1)),
        notetypes = listOf(ManifestEntry(notetypeId = 100)),
        decks = listOf(ManifestEntry(name = AcceptanceDeck)),
        media = listOf(ManifestEntry(filename = AcceptanceMediaName, modifiedAt = "media-v1")),
        serverTime = "cursor-1",
    )

    private fun acceptanceBatch() = BatchPullResponse(
        notes = (1L..5L).map { id ->
            SyncNote("note-$id", notetypeId = 100, fields = listOf("front $id", "back $id"))
        },
        cards = (1L..4L).map { id ->
            SyncCard(id, "note-$id", AcceptanceDeck, scheduling = newScheduling())
        } + SyncCard(5, "note-5", AcceptanceDeck, scheduling = reviewScheduling()),
        reviews = listOf(
            SyncReview(
                reviewId = AcceptanceNow,
                sourceCardId = 5,
                noteGuid = "note-5",
                cardOrd = 0,
                deckName = AcceptanceDeck,
                ease = 3,
                interval = 30,
            ),
        ),
        notetypes = listOf(
            SyncNotetype(
                100,
                "Basic",
                Json.parseToJsonElement(
                    """{"flds":[{"name":"Front"},{"name":"Back"}],"tmpls":[{"ord":0,"qfmt":"{{Front}}","afmt":"{{FrontSide}}<hr id=answer>{{Back}}"}]}""",
                ).jsonObject,
            ),
        ),
        decks = listOf(SyncDeck(AcceptanceDeck)),
    )

    private fun newScheduling() = buildJsonObject {
        put("queue", 0)
        put("ivl", 0)
        put("reps", 0)
    }

    private fun reviewScheduling() = buildJsonObject {
        put("queue", 2)
        put("ivl", 30)
        put("reps", 1)
    }

    private inline fun <reified T> io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(value: T) =
        respond(
            content = AcceptanceJson.encodeToString(value),
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
}

private val AcceptanceJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
private const val AcceptanceToken = "acceptance-token"
private const val AcceptanceDeck = "Acceptance"
private const val AcceptanceMediaName = "hello world.mp3"
private const val AcceptanceNow = 1_800_000_000_000L
private val AcceptanceMedia = byteArrayOf(1, 2, 3, 4, 5)
