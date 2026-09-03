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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class KelmaAccountClientContractTest {
    @Test
    fun registrationUsesTheFirstPartyFastifyEndpointWithVersionedLegalConsent() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/async/anon/sign_up", request.url.encodedPath)
            val body = request.jsonBody()
            assertEquals("new@example.com", body["email"]?.jsonPrimitive?.content)
            assertEquals("secret1", body["password"]?.jsonPrimitive?.content)
            assertEquals(KelmaAccountLegalConsentVersion, body["legalConsentVersion"]?.jsonPrimitive?.content)
            assertTrue(
                runCatching {
                    Instant.parse(body.getValue("legalConsentAcceptedAt").jsonPrimitive.content)
                }.isSuccess,
            )
            respondAccountJson("""{"message":"Verification email sent"}""")
        }
        val client = accountClient(engine)

        val message = client.register(" NEW@Example.com ", "secret1")

        assertEquals("Verification email sent", message)
        client.close()
    }

    @Test
    fun passwordResetRequestStaysInsideTheFirstPartyApi() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/async/anon/request_password_reset", request.url.encodedPath)
            assertEquals("person@example.com", request.jsonBody()["email"]?.jsonPrimitive?.content)
            respondAccountJson("""{"message":"If the user exists, we sent you an email."}""")
        }
        val client = accountClient(engine)

        val message = client.requestPasswordReset("PERSON@example.com")

        assertEquals("If the user exists, we sent you an email.", message)
        client.close()
    }

    @Test
    fun deletionReauthenticatesAndCallsTheNativeAccountDeletionEndpoint() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/async/anon/log_in" -> {
                    val body = request.jsonBody()
                    assertEquals("person@example.com", body["email"]?.jsonPrimitive?.content)
                    assertEquals("secret1", body["password"]?.jsonPrimitive?.content)
                    respondAccountJson("""{"token":"delete-token","email":"person@example.com"}""")
                }
                "/async/user/delete-account" -> {
                    assertEquals("Bearer delete-token", request.headers[HttpHeaders.Authorization])
                    val body = request.jsonBody()
                    assertEquals("person@example.com", body["email"]?.jsonPrimitive?.content)
                    assertEquals("DELETE", body["confirm"]?.jsonPrimitive?.content)
                    respondAccountJson("""{"ok":true}""")
                }
                else -> error("Unexpected request ${request.url}")
            }
        }
        val client = accountClient(engine)

        client.deleteAccount("person@example.com", "secret1")

        client.close()
    }

    @Test
    fun accountApiErrorsRemainActionableWithoutLeakingResponseDetails() = runBlocking {
        val engine = MockEngine {
            respondAccountJson(
                """{"message":"User with this email already exists!"}""",
                HttpStatusCode.BadRequest,
            )
        }
        val client = accountClient(engine)

        val error = assertFailsWith<KelmaAccountException> {
            client.register("person@example.com", "secret1")
        }

        assertEquals("User with this email already exists!", error.message)
        client.close()
    }
}

private fun accountClient(engine: MockEngine): KelmaAccountClient = KelmaAccountClient(
    endpoint = "https://kelma.invalid/async",
    httpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
    },
)

private fun io.ktor.client.request.HttpRequestData.jsonBody() = Json.parseToJsonElement(
    (body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
).jsonObject

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondAccountJson(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = content,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
