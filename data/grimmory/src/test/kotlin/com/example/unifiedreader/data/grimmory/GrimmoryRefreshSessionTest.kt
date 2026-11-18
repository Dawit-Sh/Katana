package com.example.unifiedreader.data.grimmory

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GrimmoryRefreshSessionTest {
    private fun api(status: HttpStatusCode, body: String) = KtorGrimmoryApi(
        httpClient = HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        },
        baseUrl = "http://grimmory.example",
    )

    @Test
    fun `a renewal returns the new tokens`() = runBlocking {
        val session = api(
            HttpStatusCode.OK,
            """{"accessToken": "new-access", "refreshToken": "new-refresh", "expires": 7200}""",
        ).refreshSession("old-refresh")

        assertEquals("new-access", session.accessToken)
        assertEquals("new-refresh", session.refreshToken)
    }

    // Grimmory answers 401 "Invalid credentials" for a refresh token it no longer takes.
    @Test
    fun `a turned-down renewal says the device was signed out and how to sign in again`() = runBlocking {
        try {
            api(HttpStatusCode.Unauthorized, """{"status": 401, "message": "Invalid credentials"}""")
                .refreshSession("old-refresh")
            fail("A refused refresh token signs the device out")
        } catch (expected: GrimmorySignedOutException) {
            assertTrue(expected.message.orEmpty().contains("Sign in again in Settings › Sources › Grimmory"))
        }
    }

    @Test
    fun `a renewal locked out for too many tries keeps the server's message`() = runBlocking {
        try {
            api(
                HttpStatusCode.TooManyRequests,
                """{"status": 429, "message": "Too many failed login attempts. Please try again later."}""",
            ).refreshSession("old-refresh")
            fail("A locked-out renewal fails")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Too many failed login attempts"))
        }
    }
}
