package tech.kelma.app

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlinx.serialization.json.Json

internal const val DefaultKelmaAccountEndpoint = "https://kelma.tech/async"
internal const val KelmaAccountLegalConsentVersion = "2026-08-10"

internal interface KelmaAccountService : AutoCloseable {
    suspend fun register(email: String, password: String): String
    suspend fun requestPasswordReset(email: String): String
    suspend fun deleteAccount(email: String, password: String)
}

internal class KelmaAccountException(message: String) : Exception(message)

internal class KelmaAccountClient(
    endpoint: String = DefaultKelmaAccountEndpoint,
    private val httpClient: HttpClient = defaultAccountHttpClient(),
) : KelmaAccountService {
    private val baseUrl = endpoint.trimEnd('/')

    override suspend fun register(email: String, password: String): String {
        val response = httpClient.post("$baseUrl/anon/sign_up") {
            contentType(ContentType.Application.Json)
            setBody(
                AccountRegistrationRequest(
                    email = email.normalizedAccountEmail(),
                    password = password,
                    legalConsentVersion = KelmaAccountLegalConsentVersion,
                    legalConsentAcceptedAt = Instant.fromEpochMilliseconds(currentEpochMillis()).toString(),
                ),
            )
        }
        return response.accountMessage("Could not create the Kelma account")
    }

    override suspend fun requestPasswordReset(email: String): String {
        val response = httpClient.post("$baseUrl/anon/request_password_reset") {
            contentType(ContentType.Application.Json)
            setBody(AccountEmailRequest(email.normalizedAccountEmail()))
        }
        return response.accountMessage("Could not request a password reset")
    }

    override suspend fun deleteAccount(email: String, password: String) {
        val normalizedEmail = email.normalizedAccountEmail()
        val login = httpClient.post("$baseUrl/anon/log_in") {
            contentType(ContentType.Application.Json)
            setBody(AccountLoginRequest(normalizedEmail, password))
        }
        if (!login.status.isSuccess()) {
            throw KelmaAccountException(login.errorMessage("Could not verify the account password"))
        }
        val token = login.body<AccountLoginResponse>().token
        if (token.isBlank()) throw KelmaAccountException("Account verification did not return a token")

        val deletion = httpClient.post("$baseUrl/user/delete-account") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(AccountDeletionRequest(normalizedEmail, "DELETE"))
        }
        if (!deletion.status.isSuccess()) {
            throw KelmaAccountException(deletion.errorMessage("Could not delete the Kelma account"))
        }
    }

    override fun close() {
        httpClient.close()
    }
}

@Serializable
private data class AccountRegistrationRequest(
    val email: String,
    val password: String,
    val legalConsentVersion: String,
    val legalConsentAcceptedAt: String,
)

@Serializable
private data class AccountEmailRequest(val email: String)

@Serializable
private data class AccountLoginRequest(val email: String, val password: String)

@Serializable
private data class AccountLoginResponse(val token: String = "")

@Serializable
private data class AccountDeletionRequest(val email: String, val confirm: String)

@Serializable
private data class AccountApiResponse(val message: String = "", val error: String = "")

private suspend fun io.ktor.client.statement.HttpResponse.accountMessage(fallback: String): String {
    val payload = runCatching { body<AccountApiResponse>() }.getOrNull()
    if (!status.isSuccess()) throw KelmaAccountException(payload?.displayMessage().orEmpty().ifBlank { fallback })
    return payload?.displayMessage().orEmpty().ifBlank { "Request completed" }
}

private suspend fun io.ktor.client.statement.HttpResponse.errorMessage(fallback: String): String =
    runCatching { body<AccountApiResponse>().displayMessage() }.getOrNull().orEmpty().ifBlank { fallback }

private fun AccountApiResponse.displayMessage(): String = message.ifBlank { error }

internal fun String.normalizedAccountEmail(): String = trim().lowercase()

private fun defaultAccountHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = 120_000
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
