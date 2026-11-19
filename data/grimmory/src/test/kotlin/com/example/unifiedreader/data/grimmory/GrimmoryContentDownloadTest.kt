package com.example.unifiedreader.data.grimmory

import com.example.unifiedreader.core.network.PartialDownloadFile
import com.example.unifiedreader.core.network.isConnectionFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GrimmoryContentDownloadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val book = ByteArray(10_000) { index -> (index % 97).toByte() }
    private val requests = mutableListOf<HttpRequestData>()

    @Test
    fun `a book cut off part way is finished with a Range request for the rest`() = runTest {
        var answers = 0
        val api = api { request ->
            requests += request
            answers += 1
            if (answers == 1) {
                // The connection dropped after 4,000 of the 10,000 bytes it announced.
                respond(
                    content = book.copyOf(4_000),
                    headers = headersOf(
                        HttpHeaders.ContentLength to listOf("10000"),
                        HttpHeaders.LastModified to listOf(LAST_MODIFIED),
                    ),
                )
            } else {
                // What Spring sends Grimmory's content endpoint for a Range request.
                respond(
                    content = book.copyOfRange(4_000, book.size),
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        HttpHeaders.ContentRange to listOf("bytes 4000-9999/10000"),
                        HttpHeaders.LastModified to listOf(LAST_MODIFIED),
                    ),
                )
            }
        }
        val target = PartialDownloadFile(File(folder.root, "70.epub.part"))

        try {
            api.downloadBookContentResumably(accessToken = "token", bookId = "70", target = target, bookType = "EPUB")
            fail("The first try should break off.")
        } catch (failure: Exception) {
            assertTrue("$failure", failure.isConnectionFailure())
        }
        assertEquals(4_000L, target.downloadedBytes)

        api.downloadBookContentResumably(accessToken = "token", bookId = "70", target = target, bookType = "EPUB")

        assertArrayEquals(book, target.dataFile.readBytes())
        val (first, second) = requests
        assertEquals("/api/v1/books/70/content", second.url.encodedPath)
        assertEquals("EPUB", second.url.parameters["bookType"])
        assertEquals("Bearer token", second.headers[HttpHeaders.Authorization])
        assertNull(first.headers[HttpHeaders.Range])
        assertEquals("bytes=4000-", second.headers[HttpHeaders.Range])
        assertEquals(LAST_MODIFIED, second.headers[HttpHeaders.IfRange])
    }

    @Test
    fun `a signed-out answer stays an error and keeps the bytes`() = runTest {
        val api = api { request ->
            requests += request
            respond(content = "", status = HttpStatusCode.Unauthorized)
        }
        val target = PartialDownloadFile(File(folder.root, "70.epub.part"))

        try {
            api.downloadBookContentResumably(accessToken = "old", bookId = "70", target = target)
            fail("A 401 should fail.")
        } catch (_: GrimmoryUnauthorizedException) {
            // The session runner renews the sign-in and calls again; the download then goes on.
        }
        assertEquals(0L, target.downloadedBytes)
    }

    private fun api(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = KtorGrimmoryApi(httpClient = HttpClient(MockEngine(handler)), baseUrl = "http://grimmory.example/")

    private companion object {
        const val LAST_MODIFIED = "Wed, 18 Nov 2026 21:40:00 GMT"
    }
}
