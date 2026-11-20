package com.example.unifiedreader.data.grimmory

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

/** A few small pieces of a book's file on the server ([samples], in the order asked), and the file's exact size. */
class GrimmoryFileSamples(val fileSize: Long, val samples: List<ByteArray>)

/** Small reads of a book's file on Grimmory, to tell whether a file here is the same file, without downloading it. */
interface GrimmoryBookFilesApi {
    /**
     * The bytes of book [bookId]'s file (its main one) at [ranges], one HTTP Range request each (`/content`,
     * which its web reader streams from). Null when the server answers a range with anything but that range
     * (a server sending the whole file is never read on), or the ranges don't fit the file.
     */
    suspend fun readSamples(accessToken: String, bookId: String, ranges: List<LongRange>): GrimmoryFileSamples?
}

class KtorGrimmoryBookFilesApi(
    private val httpClient: HttpClient,
    baseUrl: String,
) : GrimmoryBookFilesApi {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun readSamples(accessToken: String, bookId: String, ranges: List<LongRange>): GrimmoryFileSamples? =
        authenticatedRequest {
            var fileSize: Long? = null
            val samples = mutableListOf<ByteArray>()
            for (range in ranges) {
                val sample = readRange(accessToken, bookId, range) ?: return@authenticatedRequest null
                if (fileSize != null && fileSize != sample.first) return@authenticatedRequest null
                fileSize = sample.first
                samples += sample.second
            }
            fileSize?.let { size -> GrimmoryFileSamples(size, samples) }
        }

    // (the file's size, the bytes) of one range, or null for any answer but exactly that range.
    private suspend fun readRange(accessToken: String, bookId: String, range: LongRange): Pair<Long, ByteArray>? = try {
        httpClient.prepareGet("$baseUrl/api/v1/books/$bookId/content") {
            bearerAuth(accessToken)
            header(HttpHeaders.Range, "bytes=${range.first}-${range.last}")
            header(HttpHeaders.AcceptEncoding, "identity")
        }.execute { response ->
            if (response.status != HttpStatusCode.PartialContent) {
                // A 401 asks for a renewal; a whole file (200) is left unread: the answer is dropped here.
                response.throwIfGrimmoryError()
                return@execute null
            }
            val contentRange = response.headers[HttpHeaders.ContentRange].orEmpty()
            val fileSize = contentRange.substringAfterLast('/').trim().toLongOrNull()
            val bytes = response.body<ByteArray>()
            val expected = range.last - range.first + 1
            if (fileSize == null || !contentRange.startsWith("bytes ${range.first}-") || bytes.size.toLong() != expected) {
                null
            } else {
                fileSize to bytes
            }
        }
    } catch (refused: ClientRequestException) {
        if (refused.response.status == HttpStatusCode.Unauthorized) throw refused
        // 416: the range is past the server file's end, so it isn't the same file.
        null
    }
}
