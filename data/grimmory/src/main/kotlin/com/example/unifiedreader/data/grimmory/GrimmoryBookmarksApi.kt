package com.example.unifiedreader.data.grimmory

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * A bookmark as Grimmory keeps it (`/api/v1/bookmarks`, the web reader's): an EPUB's place is an EPUB
 * CFI ([cfi]); a PDF's is [pageNumber]. [title] and [notes] are the reader's own. [cfi] and
 * [pageNumber] are null when it came from the notebook list, which leaves places out. Times are the
 * server's.
 */
data class GrimmoryBookmark(
    val id: String,
    val bookId: String?,
    val cfi: String?,
    val pageNumber: Int?,
    val title: String?,
    val notes: String?,
    val createdAtEpochMillis: Long?,
    val updatedAtEpochMillis: Long?,
)

/** What Grimmory said to a new or changed bookmark. */
sealed interface GrimmoryBookmarkWrite {
    data class Saved(val bookmark: GrimmoryBookmark) : GrimmoryBookmarkWrite

    /** The bookmark (or its book) is no longer on the server. */
    data object Missing : GrimmoryBookmarkWrite

    /** The book already has a bookmark at this place. */
    data object AlreadyThere : GrimmoryBookmarkWrite

    /** Grimmory turned it down as it is (a place too long, say); sending it again won't help. */
    data class Refused(val message: String) : GrimmoryBookmarkWrite
}

/** Grimmory's bookmarks: one book's with their places, every book's without, and one by one changes. */
interface GrimmoryBookmarksApi {
    suspend fun bookBookmarks(accessToken: String, bookId: String): List<GrimmoryBookmark>

    /** Every bookmark of every book in one request, without places (Grimmory's notebook). */
    suspend fun allBookmarks(accessToken: String): List<GrimmoryBookmark>

    /** A new bookmark at [cfi]; Grimmory takes no notes here (they go in an [update] after). */
    suspend fun create(accessToken: String, bookId: String, cfi: String, title: String?): GrimmoryBookmarkWrite

    /** A bookmark's name and note; an empty one clears it (Grimmory keeps a missing one as it is). */
    suspend fun update(accessToken: String, bookmarkId: String, title: String, notes: String): GrimmoryBookmarkWrite

    /** False when the server no longer had it, which is as good as removed. */
    suspend fun delete(accessToken: String, bookmarkId: String): Boolean
}

/** [serverZone]: Grimmory's clock, as for highlights (its bookmark times have no zone either). */
class KtorGrimmoryBookmarksApi(
    private val httpClient: HttpClient,
    baseUrl: String,
    private val serverZone: ZoneId = ZoneId.systemDefault(),
) : GrimmoryBookmarksApi {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun bookBookmarks(accessToken: String, bookId: String): List<GrimmoryBookmark> = authenticatedRequest {
        val response = httpClient.get("$baseUrl/api/v1/bookmarks/book/$bookId") { bearerAuth(accessToken) }
        response.throwIfGrimmoryError()
        response.body<List<GrimmoryBookmarkDto>>().mapNotNull { dto -> dto.toBookmark(serverZone) }
    }

    override suspend fun allBookmarks(accessToken: String): List<GrimmoryBookmark> = authenticatedRequest {
        val response = httpClient.get("$baseUrl/api/v1/notebook/export") {
            bearerAuth(accessToken)
            url {
                parameters.append("types", NOTEBOOK_BOOKMARK)
                parameters.append("sort", "asc")
            }
        }
        response.throwIfGrimmoryError()
        response.body<List<GrimmoryNotebookEntryDto>>()
            .filter { entry -> entry.type == null || entry.type == NOTEBOOK_BOOKMARK }
            .mapNotNull { entry ->
                entry.id?.let { id ->
                    GrimmoryBookmark(
                        id = id.toString(),
                        bookId = entry.bookId?.toString(),
                        cfi = null,
                        pageNumber = null,
                        title = entry.text?.takeIf(String::isNotBlank),
                        notes = entry.note?.takeIf(String::isNotBlank),
                        createdAtEpochMillis = entry.createdAt.toEpochMillisIn(serverZone),
                        updatedAtEpochMillis = entry.updatedAt.toEpochMillisIn(serverZone),
                    )
                }
            }
    }

    override suspend fun create(accessToken: String, bookId: String, cfi: String, title: String?): GrimmoryBookmarkWrite =
        writeRequest {
            httpClient.post("$baseUrl/api/v1/bookmarks") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    GrimmoryCreateBookmarkRequestDto(
                        bookId = bookId.toLongOrNull() ?: error("Grimmory bookId must be numeric: $bookId"),
                        cfi = cfi,
                        title = title?.takeIf(String::isNotBlank)?.take(TITLE_LIMIT),
                    ),
                )
            }
        }

    override suspend fun update(accessToken: String, bookmarkId: String, title: String, notes: String): GrimmoryBookmarkWrite =
        writeRequest {
            httpClient.put("$baseUrl/api/v1/bookmarks/$bookmarkId") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(GrimmoryUpdateBookmarkRequestDto(title = title.take(TITLE_LIMIT), notes = notes.take(NOTES_LIMIT)))
            }
        }

    override suspend fun delete(accessToken: String, bookmarkId: String): Boolean {
        val response = statusAware { httpClient.delete("$baseUrl/api/v1/bookmarks/$bookmarkId") { bearerAuth(accessToken) } }
        if (response.status == HttpStatusCode.NotFound) return false
        response.throwIfGrimmoryError()
        return true
    }

    private suspend fun writeRequest(send: suspend () -> HttpResponse): GrimmoryBookmarkWrite {
        val response = statusAware(send)
        return when (response.status) {
            HttpStatusCode.NotFound -> GrimmoryBookmarkWrite.Missing
            HttpStatusCode.Conflict -> GrimmoryBookmarkWrite.AlreadyThere
            HttpStatusCode.BadRequest, HttpStatusCode.UnprocessableEntity ->
                GrimmoryBookmarkWrite.Refused(response.bodyAsText().take(REFUSAL_MESSAGE_LIMIT))
            else -> {
                response.throwIfGrimmoryError()
                val saved = response.body<GrimmoryBookmarkDto>().toBookmark(serverZone)
                    ?: error("Grimmory saved the bookmark without an id.")
                GrimmoryBookmarkWrite.Saved(saved)
            }
        }
    }

    // The answer whatever its status, so a 404 or 409 can be read; a 401 still asks for a renewal.
    private suspend fun statusAware(send: suspend () -> HttpResponse): HttpResponse = authenticatedRequest {
        try {
            send()
        } catch (refused: ClientRequestException) {
            if (refused.response.status == HttpStatusCode.Unauthorized) throw refused
            refused.response
        }
    }
}

private const val NOTEBOOK_BOOKMARK = "BOOKMARK"

// Grimmory's limits on a bookmark's fields (its update request's checks).
private const val TITLE_LIMIT = 255
private const val NOTES_LIMIT = 2_000
private const val REFUSAL_MESSAGE_LIMIT = 300

@Serializable
internal data class GrimmoryBookmarkDto(
    val id: Long? = null,
    val bookId: Long? = null,
    val cfi: String? = null,
    val pageNumber: Int? = null,
    val title: String? = null,
    val notes: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class GrimmoryCreateBookmarkRequestDto(
    val bookId: Long,
    val cfi: String,
    val title: String? = null,
)

@Serializable
internal data class GrimmoryUpdateBookmarkRequestDto(
    val title: String,
    val notes: String,
)

internal fun GrimmoryBookmarkDto.toBookmark(serverZone: ZoneId): GrimmoryBookmark? {
    val bookmarkId = id ?: return null
    return GrimmoryBookmark(
        id = bookmarkId.toString(),
        bookId = bookId?.toString(),
        cfi = cfi?.takeIf(String::isNotBlank),
        pageNumber = pageNumber,
        title = title?.takeIf(String::isNotBlank),
        notes = notes?.takeIf(String::isNotBlank),
        createdAtEpochMillis = createdAt.toEpochMillisIn(serverZone),
        updatedAtEpochMillis = updatedAt.toEpochMillisIn(serverZone),
    )
}
