package tech.kelma.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface KelmaSyncService {
    suspend fun login(username: String, password: String): LoginResponse
    suspend fun push(token: String, plan: SyncUploadPlan): SyncPushResult
    suspend fun push(
        token: String,
        plan: SyncUploadPlan,
        onProgress: suspend (SyncPushProgress) -> Unit,
    ): SyncPushResult {
        val result = push(token, plan)
        return result.also {
            plan.progressTotals().forEach { (resource, total) ->
                onProgress(SyncPushProgress(resource, total, total))
            }
        }
    }

    suspend fun pull(token: String, current: SyncedCollection): PullReport
    suspend fun pull(
        token: String,
        current: SyncedCollection,
        onProgress: suspend (SyncPullProgress) -> Unit,
    ): PullReport = pull(token, current)

    suspend fun getSchedulerProfile(token: String): SchedulerProfileResponse = SchedulerProfileResponse()
    suspend fun getStudyDayPolicy(token: String): AccountStudyDayPolicy = AccountStudyDayPolicy.systemDefault()
    suspend fun putStudyDayPolicy(
        token: String,
        candidate: StudyDayPolicyCandidate,
    ): AccountStudyDayPolicy = AccountStudyDayPolicy(
        version = candidate.baseVersion + 1L,
        timezoneId = candidate.timezoneId,
        dayStartHour = candidate.dayStartHour,
        idempotencyKey = candidate.idempotencyKey,
    )
}

class KelmaSyncException(message: String) : Exception(message)

class KelmaSyncClient(
    endpoint: String = DefaultKelmaSyncEndpoint,
    private val clientLabel: String = "Kelma Review",
    private val httpClient: HttpClient = defaultHttpClient(),
    mediaCache: MediaCache = NoOpMediaCache,
) : KelmaSyncService, AutoCloseable {
    private val baseUrl = endpoint.trimEnd('/')
    private val pusher = KelmaSyncPusher(baseUrl, httpClient)
    private val puller = KelmaSyncPuller(baseUrl, httpClient, mediaCache)

    override suspend fun login(username: String, password: String): LoginResponse {
        val response = httpClient.post("$baseUrl/v2/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(
                LoginRequest(
                    username = username.trim(),
                    password = password,
                    clientLabel = clientLabel,
                ),
            )
        }
        if (!response.status.isSuccess()) {
            val error = runCatching { response.body<SyncError>() }.getOrNull()
            throw KelmaSyncException(error?.message?.ifBlank { error.error } ?: "Sign-in failed")
        }
        return response.body()
    }

    override suspend fun push(token: String, plan: SyncUploadPlan): SyncPushResult =
        pusher.push(token, plan) {}

    override suspend fun push(
        token: String,
        plan: SyncUploadPlan,
        onProgress: suspend (SyncPushProgress) -> Unit,
    ): SyncPushResult = pusher.push(token, plan, onProgress)

    override suspend fun pull(token: String, current: SyncedCollection): PullReport =
        puller.pull(token, current) {}

    override suspend fun pull(
        token: String,
        current: SyncedCollection,
        onProgress: suspend (SyncPullProgress) -> Unit,
    ): PullReport = puller.pull(token, current, onProgress)

    override suspend fun getSchedulerProfile(token: String): SchedulerProfileResponse =
        puller.getSchedulerProfile(token)

    override suspend fun getStudyDayPolicy(token: String): AccountStudyDayPolicy =
        puller.getStudyDayPolicy(token)

    override suspend fun putStudyDayPolicy(
        token: String,
        candidate: StudyDayPolicyCandidate,
    ): AccountStudyDayPolicy {
        val response = httpClient.put("$baseUrl/v2/study-day-policy") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(candidate)
        }
        if (!response.status.isSuccess()) {
            val error = runCatching { response.body<SyncError>() }.getOrNull()
            val message = if (error?.error == "study_day_policy_conflict") {
                "Study-day policy changed on another device; sync and try again"
            } else {
                error?.message?.ifBlank { error.error } ?: "Study-day policy upload failed"
            }
            throw KelmaSyncException(message)
        }
        return response.body<AccountStudyDayPolicy>().validated()
    }

    override fun close() {
        httpClient.close()
    }
}

private fun SyncUploadPlan.progressTotals(): Map<SyncPushResource, Int> = buildMap {
    if (reviews.isNotEmpty()) put(SyncPushResource.Reviews, reviews.size)
    if (notes.isNotEmpty()) put(SyncPushResource.Notes, notes.size)
    val cardCount = cardStudyStates.size + cardScheduleResets.size + cardDueDates.size +
        notes.sumOf { it.cards.size } + decks.sumOf { it.cards.size }
    if (cardCount > 0) put(SyncPushResource.Cards, cardCount)
    if (decks.isNotEmpty()) put(SyncPushResource.Decks, decks.size)
    if (media.isNotEmpty()) put(SyncPushResource.Media, media.size)
    if (schedulerProfile != null) put(SyncPushResource.SchedulerProfile, 1)
}

private fun defaultHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = 300_000
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }
}
