package com.example.unifiedreader.data.grimmory

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A book file on Grimmory is read in small ranges only, never downloaded, to tell whether a file here is the same. */
class GrimmoryBookFilesApiTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val file = ByteArray(3_000) { index -> (index % 199).toByte() }

    private fun api(partial: Boolean = true) = KtorGrimmoryApi(
        httpClient = HttpClient(
            MockEngine { request ->
                requests += request
                val range = request.headers[HttpHeaders.Range].orEmpty().removePrefix("bytes=").split('-').map(String::toInt)
                if (partial) {
                    respond(
                        content = file.copyOfRange(range[0], range[1] + 1),
                        status = HttpStatusCode.PartialContent,
                        headers = headersOf(HttpHeaders.ContentRange, "bytes ${range[0]}-${range[1]}/${file.size}"),
                    )
                } else {
                    respond(content = file, status = HttpStatusCode.OK)
                }
            },
        ),
        baseUrl = "http://grimmory.example/",
    ).files

    @Test
    fun `each sample is one range request of the book's content, with the file's exact size`() = runBlocking {
        val samples = api().readSamples("token", "34", listOf(0L..1_023L, 1_024L..2_047L))!!

        assertEquals(3_000L, samples.fileSize)
        assertArrayEquals(file.copyOfRange(1_024, 2_048), samples.samples[1])
        assertEquals("/api/v1/books/34/content", requests.first().url.encodedPath)
        assertEquals(listOf("bytes=0-1023", "bytes=1024-2047"), requests.map { it.headers[HttpHeaders.Range] })
    }

    @Test
    fun `a server that answers with the whole file is not read`() = runBlocking {
        assertNull(api(partial = false).readSamples("token", "34", listOf(0L..1_023L, 1_024L..2_047L)))
        assertEquals("it stops at the first answer", 1, requests.size)
    }
}
