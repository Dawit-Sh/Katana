package com.example.unifiedreader.core.network

import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Downloads against a real HTTP server (MockWebServer) that cuts connections, stalls, throttles and answers
 * Range requests the ways real servers do. The client is the app's own ([buildHttpClient]) with short limits.
 */
class ResumableDownloadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val client = buildHttpClient(connectTimeoutMillis = 2_000, socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS)
    private val book = ByteArray(BOOK_SIZE) { index -> (index % 251).toByte() }

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        client.close()
        server.shutdown()
    }

    @Test
    fun `a whole file downloads in one go`() = runBlocking {
        server.enqueue(wholeBook())
        val target = target()

        download(target)

        assertArrayEquals(book, target.dataFile.readBytes())
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals(PartialDownloadInfo(BOOK_SIZE.toLong(), null, LAST_MODIFIED), target.readInfo())
    }

    @Test
    fun `a connection cut mid-body keeps what arrived, and the next try finishes the same file`() = runBlocking {
        server.enqueue(wholeBook().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val target = target()

        val failure = downloadFailure(target)

        assertTrue("$failure", failure.isConnectionFailure())
        val kept = target.downloadedBytes
        assertTrue("kept $kept bytes", kept < BOOK_SIZE)
        assertArrayEquals(book.copyOf(kept.toInt()), target.dataFile.readBytes())
        server.takeRequest()

        // The server is back: it sends the rest, or the whole file when nothing had arrived.
        server.enqueue(if (kept > 0) restOfBook(from = kept) else wholeBook())
        download(target)

        assertArrayEquals(book, target.dataFile.readBytes())
        val resumed = server.takeRequest()
        assertEquals(if (kept > 0) "bytes=$kept-" else null, resumed.getHeader("Range"))
    }

    @Test
    fun `a broken-off download asks for the rest only and appends it`() = runBlocking {
        val target = partiallyDownloaded(bytes = 40_000)
        server.enqueue(restOfBook(from = 40_000))

        download(target)

        assertArrayEquals(book, target.dataFile.readBytes())
        val request = server.takeRequest()
        assertEquals("bytes=40000-", request.getHeader("Range"))
        assertEquals(LAST_MODIFIED, request.getHeader("If-Range"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a whole-file answer to a Range request replaces the bytes already there`() = runBlocking {
        // A server without Range support, or one whose file changed and that honours If-Range.
        val target = partiallyDownloaded(bytes = 40_000, content = ByteArray(40_000) { 7 })
        server.enqueue(wholeBook())

        download(target)

        assertArrayEquals(book, target.dataFile.readBytes())
        assertEquals("bytes=40000-", server.takeRequest().getHeader("Range"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a partial answer for a changed file starts over`() = runBlocking {
        val newBook = ByteArray(BOOK_SIZE + 1_000) { index -> (index % 13).toByte() }
        val target = partiallyDownloaded(bytes = 40_000)
        // Grimmory's Spring server answers any Range with 206, whatever If-Range says: the length shows the change.
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 40000-${newBook.size - 1}/${newBook.size}")
                .setHeader("Last-Modified", "Thu, 19 Nov 2026 09:00:00 GMT")
                .setBody(Buffer().write(newBook.copyOfRange(40_000, newBook.size))),
        )
        server.enqueue(MockResponse().setBody(Buffer().write(newBook)))

        download(target)

        assertArrayEquals(newBook, target.dataFile.readBytes())
        assertEquals("bytes=40000-", server.takeRequest().getHeader("Range"))
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `a partial answer with another date starts over`() = runBlocking {
        val target = partiallyDownloaded(bytes = 40_000)
        server.enqueue(restOfBook(from = 40_000).setHeader("Last-Modified", "Thu, 19 Nov 2026 09:00:00 GMT"))
        server.enqueue(wholeBook())

        download(target)

        assertArrayEquals(book, target.dataFile.readBytes())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a range the server can't satisfy starts over`() = runBlocking {
        val target = partiallyDownloaded(bytes = 40_000)
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */30000"))
        server.enqueue(wholeBook())

        download(target)

        assertArrayEquals(book, target.dataFile.readBytes())
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `bytes kept without what the server said about the file are not trusted`() = runBlocking {
        val target = target()
        target.dataFile.writeBytes(ByteArray(40_000) { 9 })
        server.enqueue(wholeBook())

        download(target)

        assertArrayEquals(book, target.dataFile.readBytes())
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `an error answer keeps the bytes for a later try`() = runBlocking {
        val target = partiallyDownloaded(bytes = 40_000)
        server.enqueue(MockResponse().setResponseCode(503))

        val failure = downloadFailure(target)

        assertFalse(failure.isConnectionFailure())
        assertEquals(40_000L, target.downloadedBytes)
    }

    @Test
    fun `a slow body that keeps coming is never cut by a total time limit`() = runBlocking {
        // 16 chunks a quarter of the socket timeout apart: about 4 s in all, four times the socket timeout.
        server.enqueue(wholeBook().throttleBody(BOOK_SIZE / 16L, SOCKET_TIMEOUT_MILLIS / 4, TimeUnit.MILLISECONDS))
        val target = target()

        download(target)

        assertArrayEquals(book, target.dataFile.readBytes())
    }

    @Test
    fun `a body that stalls times out, keeps what arrived and goes on when the server is back`() = runBlocking {
        // 16 KB, then silence longer than the socket timeout.
        server.enqueue(wholeBook().throttleBody(16_384L, SOCKET_TIMEOUT_MILLIS * 3, TimeUnit.MILLISECONDS))
        val target = target()

        val failure = downloadFailure(target)

        assertTrue("$failure", failure.isConnectionFailure())
        val kept = target.downloadedBytes
        assertTrue("kept $kept bytes", kept in 0L..16_384L)
        server.takeRequest()

        server.enqueue(if (kept > 0) restOfBook(from = kept) else wholeBook())
        download(target)

        assertArrayEquals(book, target.dataFile.readBytes())
    }

    @Test
    fun `a file already complete is not asked for again`() = runBlocking {
        val target = partiallyDownloaded(bytes = BOOK_SIZE)

        download(target)

        assertArrayEquals(book, target.dataFile.readBytes())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `content ranges are read the way servers write them`() {
        assertEquals(ContentRange(100, 999, 1000), parseContentRange("bytes 100-999/1000"))
        assertEquals(ContentRange(0, 9, null), parseContentRange("bytes 0-9/*"))
        assertNull(parseContentRange("bytes */1000"))
        assertNull(parseContentRange("items 0-9/10"))
    }

    private fun target() = PartialDownloadFile(File(folder.root, "book.epub.part"))

    private fun partiallyDownloaded(
        bytes: Int,
        content: ByteArray = book.copyOf(bytes),
    ): PartialDownloadFile = target().apply {
        dataFile.writeBytes(content)
        writeInfo(PartialDownloadInfo(totalBytes = BOOK_SIZE.toLong(), entityTag = null, lastModified = LAST_MODIFIED))
    }

    private suspend fun download(target: PartialDownloadFile) = client.downloadResumably(
        target = target,
        request = { url(server.url("/api/v1/books/70/content").toString()) },
        throwIfError = { response: HttpResponse ->
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
        },
    )

    private suspend fun downloadFailure(target: PartialDownloadFile): Throwable {
        try {
            download(target)
        } catch (failure: Exception) {
            return failure
        }
        fail("The download should have failed.")
        error("unreachable")
    }

    private fun wholeBook(): MockResponse = MockResponse()
        .setHeader("Last-Modified", LAST_MODIFIED)
        .setHeader("Accept-Ranges", "bytes")
        .setBody(Buffer().write(book))

    private fun restOfBook(from: Long): MockResponse = MockResponse()
        .setResponseCode(206)
        .setHeader("Last-Modified", LAST_MODIFIED)
        .setHeader("Content-Range", "bytes $from-${BOOK_SIZE - 1}/$BOOK_SIZE")
        .setBody(Buffer().write(book.copyOfRange(from.toInt(), BOOK_SIZE)))

    private companion object {
        const val BOOK_SIZE = 256 * 1024
        const val SOCKET_TIMEOUT_MILLIS = 1_000L
        const val LAST_MODIFIED = "Wed, 18 Nov 2026 21:40:00 GMT"
    }
}
