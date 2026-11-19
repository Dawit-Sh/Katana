package com.example.unifiedreader.data.grimmory

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrimmoryAnnotationsApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(answer: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = KtorGrimmoryApi(
        httpClient = HttpClient(
            MockEngine { request ->
                requests += request
                answer(request)
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        },
        baseUrl = "http://grimmory.example/",
    ).annotations

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun HttpRequestData.jsonBody(): JsonObject =
        Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test
    fun `a book's highlights come with their places, notes and server times`() = runBlocking {
        val highlights = api {
            json(
                """[{"id": 7, "userId": 1, "bookId": 88, "cfi": "epubcfi(/6/8!/4/2,/1:3,/1:9)", "text": "a hole",
                   "color": "#4ADE80", "style": "underline", "note": "", "chapterTitle": "One",
                   "createdAt": "2026-11-19T01:02:03", "updatedAt": "2026-11-19T01:02:04.5"}]""",
            )
        }.bookHighlights("token", "88")

        assertEquals("/api/v1/annotations/book/88", requests.single().url.encodedPath)
        val highlight = highlights.single()
        assertEquals("7", highlight.id)
        assertEquals("epubcfi(/6/8!/4/2,/1:3,/1:9)", highlight.cfi)
        assertEquals("#4ADE80", highlight.color)
        assertEquals("underline", highlight.style)
        assertNull("a blank note is no note", highlight.note)
        val expected = java.time.LocalDateTime.of(2026, 11, 19, 1, 2, 4, 500_000_000)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("a time without a zone is the server's, the home network's", expected, highlight.updatedAtEpochMillis)
    }

    // Grimmory writes a highlight's times in its own zone, without saying which.
    @Test
    fun `a highlight's server time is read in the server's zone`() {
        val addisAbaba = java.time.ZoneId.of("Africa/Addis_Ababa")

        assertEquals(1_795_063_484_000L, "2026-11-19T07:44:44".toEpochMillisIn(addisAbaba))
        assertEquals(1_795_063_484_000L, "2026-11-19T04:44:44Z".toEpochMillisIn(addisAbaba))
        assertNull("".toEpochMillisIn(addisAbaba))
    }

    @Test
    fun `every book's highlights come from the notebook in one request, without places`() = runBlocking {
        val highlights = api {
            json("""[{"id": 7, "type": "HIGHLIGHT", "bookId": 88, "text": "a hole", "updatedAt": "2026-11-19T01:02:04"}]""")
        }.allHighlights("token")

        val request = requests.single()
        assertEquals("/api/v1/notebook/export", request.url.encodedPath)
        assertEquals(listOf("HIGHLIGHT"), request.url.parameters.getAll("types"))
        assertEquals("88", highlights.single().bookId)
        assertNull(highlights.single().cfi)
    }

    @Test
    fun `a new highlight is posted with its book, place, words and look`() = runBlocking {
        val written = api {
            json("""{"id": 9, "bookId": 88, "cfi": "epubcfi(/6/8!/4,/1:0,/1:4)", "text": "Once", "color": "#FACC15", "style": "highlight"}""")
        }.create(
            "token",
            GrimmoryAnnotationCreate(
                bookId = "88",
                cfi = "epubcfi(/6/8!/4,/1:0,/1:4)",
                text = "Once",
                color = "#FACC15",
                style = "highlight",
                note = null,
                chapterTitle = "One",
            ),
        )

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v1/annotations", request.url.encodedPath)
        val body = request.jsonBody()
        assertEquals(88L, body.getValue("bookId").jsonPrimitive.content.toLong())
        assertEquals("epubcfi(/6/8!/4,/1:0,/1:4)", body.getValue("cfi").jsonPrimitive.content)
        assertFalse("no note is left out", "note" in body)
        assertEquals("9", (written as GrimmoryAnnotationWrite.Saved).annotation.id)
    }

    // Grimmory leaves a missing note as it was, so a removed note goes as an empty one.
    @Test
    fun `a change sends the look and the note, an empty note to remove it`() = runBlocking {
        api { json("""{"id": 9, "text": "Once"}""") }
            .update("token", "9", GrimmoryAnnotationChange(color = "#38BDF8", style = "squiggly", note = ""))

        val request = requests.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/api/v1/annotations/9", request.url.encodedPath)
        assertEquals("", request.jsonBody().getValue("note").jsonPrimitive.content)
        assertEquals("squiggly", request.jsonBody().getValue("style").jsonPrimitive.content)
    }

    @Test
    fun `a highlight gone from the server or already there says so`() = runBlocking {
        val gone = api { json("""{"message": "Annotation not found"}""", HttpStatusCode.NotFound) }
            .update("token", "9", GrimmoryAnnotationChange(color = "#38BDF8", style = "highlight", note = "x"))
        val there = api { json("""{"message": "Annotation already exists at this location"}""", HttpStatusCode.Conflict) }
            .create("token", GrimmoryAnnotationCreate("88", "epubcfi(/6/2!/4,/1:0,/1:1)", "O", "#FACC15", "highlight", null, null))
        val refused = api { json("""{"message": "CFI must not exceed 1000 characters"}""", HttpStatusCode.BadRequest) }
            .create("token", GrimmoryAnnotationCreate("88", "epubcfi(/6/2!/4,/1:0,/1:1)", "O", "#FACC15", "highlight", null, null))

        assertEquals(GrimmoryAnnotationWrite.Missing, gone)
        assertEquals(GrimmoryAnnotationWrite.AlreadyThere, there)
        assertTrue(refused is GrimmoryAnnotationWrite.Refused)
    }

    @Test
    fun `removing a highlight the server no longer has counts as removed`() = runBlocking {
        val removed = api { respond("", HttpStatusCode.NoContent) }.delete("token", "9")
        val missing = api { json("""{"message": "not found"}""", HttpStatusCode.NotFound) }.delete("token", "10")

        assertTrue(removed)
        assertFalse(missing)
        assertEquals(HttpMethod.Delete, requests.first().method)
        assertEquals("/api/v1/annotations/9", requests.first().url.encodedPath)
    }

    @Test(expected = GrimmoryUnauthorizedException::class)
    fun `an expired token asks for a renewal`() {
        runBlocking { api { json("{}", HttpStatusCode.Unauthorized) }.delete("token", "9") }
    }
}
