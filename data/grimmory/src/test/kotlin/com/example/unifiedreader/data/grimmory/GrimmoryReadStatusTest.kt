package com.example.unifiedreader.data.grimmory

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** A book's reading status goes to Grimmory (and Katana Desk) the way Grimmory's web app sends it. */
class GrimmoryReadStatusTest {
    private val requests = mutableListOf<Pair<HttpRequestData, String>>()

    private fun api(status: HttpStatusCode = HttpStatusCode.OK, answer: String) = KtorGrimmoryApi(
        httpClient = HttpClient(
            MockEngine { request ->
                requests += request to String(request.body.toByteArray())
                respond(answer, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        },
        baseUrl = "http://grimmory.example/",
    )

    @Test
    fun `a status is one POST to books status with the book and the status`() = runBlocking {
        val answer = api(answer = """[{"bookId": 34, "readStatus": "RE_READING", "readStatusModifiedTime": "2026-11-21T10:15:30Z"}]""")
            .setReadStatus(accessToken = "token", bookId = "34", status = "RE_READING")

        assertEquals(GrimmoryReadStatusAnswer(status = "RE_READING", setAtEpochMillis = 1_795_256_130_000L), answer)
        val (request, body) = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v1/books/status", request.url.encodedPath)
        assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals(listOf(34L), sent.getValue("bookIds").jsonArray.map { it.jsonPrimitive.content.toLong() })
        assertEquals("RE_READING", sent.getValue("status").jsonPrimitive.content)
        assertEquals(setOf("bookIds", "status"), (sent as JsonObject).keys)
    }

    @Test
    fun `the answer for the book is read, and an answer without one is the status asked for`() = runBlocking {
        // Katana Desk answers without a time.
        assertEquals(
            GrimmoryReadStatusAnswer(status = "PAUSED"),
            api(answer = """[{"bookId": 7, "readStatus": "READ"}, {"bookId": 34, "readStatus": "PAUSED"}]""")
                .setReadStatus("token", "34", "PAUSED"),
        )
        assertEquals(GrimmoryReadStatusAnswer(status = "WONT_READ"), api(answer = "").setReadStatus("token", "34", "WONT_READ"))
        assertEquals(GrimmoryReadStatusAnswer(status = "ABANDONED"), api(answer = "{}").setReadStatus("token", "34", "ABANDONED"))
        assertEquals(
            GrimmoryReadStatusAnswer(status = "READ"),
            api(answer = """[{"bookId": 34, "readStatus": "READ", "readStatusModifiedTime": "not a time"}]""")
                .setReadStatus("token", "34", "READ"),
        )
        assertNull(GrimmoryReadStatusAnswer(status = "READ").setAtEpochMillis)
    }

    @Test
    fun `an expired sign-in asks for a renewal and a refusal is an error`() = runBlocking {
        try {
            api(status = HttpStatusCode.Unauthorized, answer = "").setReadStatus("token", "34", "READ")
            fail("expected a renewal")
        } catch (expected: GrimmoryUnauthorizedException) {
            assertEquals("Grimmory access token expired.", expected.message)
        }
        try {
            api(status = HttpStatusCode.BadRequest, answer = """{"message": "SKIMMED isn't a reading status"}""")
                .setReadStatus("token", "34", "SKIMMED")
            fail("expected the server's refusal")
        } catch (expected: IllegalStateException) {
            assertEquals("SKIMMED isn't a reading status", expected.message)
        }
        requests.clear()
        try {
            api(answer = "[]").setReadStatus("token", "not-a-number", "READ")
            fail("expected a numeric book id")
        } catch (expected: IllegalStateException) {
            assertEquals("nothing was sent", 0, requests.size)
        }
    }

    @Test
    fun `a book's status comes with the time it was set when the server says`() {
        val entry = GrimmoryBookDto(
            id = 34,
            libraryId = 1,
            primaryFile = GrimmoryBookFileDto(id = 34, bookType = "EPUB", fileName = "a.epub"),
            readStatus = "PAUSED",
            readStatusModifiedTime = "2026-11-21T10:15:30Z",
        ).toCatalogEntry(baseUrl = "http://grimmory.example")!!
        assertEquals("PAUSED", entry.readStatus)
        assertEquals("2026-11-21T10:15:30Z", entry.readStatusModifiedTime)
    }
}
