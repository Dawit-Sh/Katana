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

class GrimmoryBookmarksApiTest {
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
    ).annotations.bookmarks

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun HttpRequestData.jsonBody(): JsonObject = Json.parseToJsonElement((body as TextContent).text).jsonObject

    @Test
    fun `a book's bookmarks come with their places, names, notes and server times`() = runBlocking {
        val bookmarks = api {
            json(
                """[{"id": 12, "userId": 1, "bookId": 34, "cfi": "epubcfi(/6/8!/4,/2/1:0,/24/1:118)", "positionMs": null,
                   "trackIndex": null, "pageNumber": null, "title": "Chapter 3", "color": null, "notes": "",
                   "priority": 3, "createdAt": "2026-11-19T14:49:46.12", "updatedAt": "2026-11-19T14:49:46.123456"}]""",
            )
        }.bookBookmarks("token", "34")

        assertEquals("/api/v1/bookmarks/book/34", requests.single().url.encodedPath)
        val bookmark = bookmarks.single()
        assertEquals("12", bookmark.id)
        assertEquals("epubcfi(/6/8!/4,/2/1:0,/24/1:118)", bookmark.cfi)
        assertEquals("Chapter 3", bookmark.title)
        assertNull("a blank note is no note", bookmark.notes)
        assertTrue(bookmark.updatedAtEpochMillis != null)
    }

    @Test
    fun `every book's bookmarks come from the notebook, without places`() = runBlocking {
        val bookmarks = api {
            json("""[{"id": 5, "type": "BOOKMARK", "bookId": 34, "text": "Page 3", "note": null, "updatedAt": "2026-11-19T10:00:00"}]""")
        }.allBookmarks("token")

        val request = requests.single()
        assertEquals("/api/v1/notebook/export", request.url.encodedPath)
        assertEquals("BOOKMARK", request.url.parameters["types"])
        assertEquals("34", bookmarks.single().bookId)
        assertNull(bookmarks.single().cfi)
    }

    @Test
    fun `a new bookmark goes with its book, place and name, and a place already taken says so`() = runBlocking {
        val saved = api { json("""{"id": 40, "bookId": 34, "cfi": "epubcfi(/6/8!/4/2,/1:0,/1:9)", "title": "XLIII"}""") }
            .create("token", "34", GrimmoryBookmarkPlace.Cfi("epubcfi(/6/8!/4/2,/1:0,/1:9)"), "XLIII")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v1/bookmarks", request.url.encodedPath)
        assertEquals("34", request.jsonBody()["bookId"]!!.jsonPrimitive.content)
        assertEquals("XLIII", request.jsonBody()["title"]!!.jsonPrimitive.content)
        assertEquals("40", (saved as GrimmoryBookmarkWrite.Saved).bookmark.id)

        val taken = api { json("""{"status": 409, "message": "Bookmark already exists at this location"}""", HttpStatusCode.Conflict) }
            .create("token", "34", GrimmoryBookmarkPlace.Cfi("epubcfi(/6/8!/4/2,/1:0,/1:9)"), null)
        assertEquals(GrimmoryBookmarkWrite.AlreadyThere, taken)
    }

    @Test
    fun `a PDF page bookmark goes as its page number with no CFI, and a page already bookmarked says so`() = runBlocking {
        val saved = api { json("""{"id": 41, "bookId": 35, "pageNumber": 12, "title": "Page 12"}""") }
            .create("token", "35", GrimmoryBookmarkPlace.PdfPage(12), "Page 12")

        val body = requests.single().jsonBody()
        assertEquals("12", body["pageNumber"]!!.jsonPrimitive.content)
        assertFalse("a PDF bookmark has no CFI", body.containsKey("cfi"))
        assertEquals(12, (saved as GrimmoryBookmarkWrite.Saved).bookmark.pageNumber)

        val taken = api { json("""{"status": 409, "message": "A bookmark already exists on this page"}""", HttpStatusCode.Conflict) }
            .create("token", "35", GrimmoryBookmarkPlace.PdfPage(12), null)
        assertEquals(GrimmoryBookmarkWrite.AlreadyThere, taken)
    }

    @Test
    fun `a comic page bookmark keeps its page in the CFI field, as Grimmory's comic reader does`() = runBlocking {
        api { json("""{"id": 42, "bookId": 36, "cfi": "7", "title": "Page 7"}""") }
            .create("token", "36", GrimmoryBookmarkPlace.Cfi("7"), "Page 7")

        val body = requests.single().jsonBody()
        assertEquals("7", body["cfi"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("pageNumber"))
    }

    @Test
    fun `a name and note change is a PUT, and a bookmark gone from the server is missing`() = runBlocking {
        api { json("""{"id": 40, "title": "Darcy", "notes": "the letter"}""") }.update("token", "40", title = "Darcy", notes = "the letter")
        val request = requests.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/api/v1/bookmarks/40", request.url.encodedPath)
        assertEquals("the letter", request.jsonBody()["notes"]!!.jsonPrimitive.content)

        val gone = api { json("""{"status": 404}""", HttpStatusCode.NotFound) }.update("token", "41", title = "", notes = "")
        assertEquals(GrimmoryBookmarkWrite.Missing, gone)
    }

    @Test
    fun `removing one the server no longer has is as good as removed`() = runBlocking {
        assertTrue(api { respond("", HttpStatusCode.NoContent) }.delete("token", "40"))
        assertFalse(api { json("""{"status": 404}""", HttpStatusCode.NotFound) }.delete("token", "41"))
        assertEquals(HttpMethod.Delete, requests.first().method)
    }
}
