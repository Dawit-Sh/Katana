package com.example.unifiedreader.data.grimmory

import com.example.unifiedreader.core.model.toServerEpochMillisOrNull
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
import kotlinx.serialization.Serializable

/**
 * A highlight as Grimmory keeps it (`/api/v1/annotations`), the one its web reader and the
 * grimmory.koplugin share: the words ([text]) at an EPUB CFI range ([cfi]), a `#RRGGBB` [color],
 * a [style] (highlight, underline, strikethrough or squiggly) and an optional [note]. [cfi] is null
 * when it came from the notebook list, which leaves it out. Times are the server's.
 */
data class GrimmoryAnnotation(
    val id: String,
    val bookId: String?,
    val cfi: String?,
    val text: String,
    val color: String?,
    val style: String?,
    val note: String?,
    val chapterTitle: String?,
    val createdAtEpochMillis: Long?,
    val updatedAtEpochMillis: Long?,
)

/** A new highlight for [bookId]; Grimmory refuses a second one at the same [cfi] of the same book. */
data class GrimmoryAnnotationCreate(
    val bookId: String,
    val cfi: String,
    val text: String,
    val color: String,
    val style: String,
    val note: String?,
    val chapterTitle: String?,
)

/** A highlight's look and note. An empty [note] removes it (Grimmory leaves a missing one as it is). */
data class GrimmoryAnnotationChange(
    val color: String,
    val style: String,
    val note: String,
)

/** What Grimmory said to a new or changed highlight. */
sealed interface GrimmoryAnnotationWrite {
    data class Saved(val annotation: GrimmoryAnnotation) : GrimmoryAnnotationWrite

    /** The highlight is no longer on the server (removed in the web reader or KOReader). */
    data object Missing : GrimmoryAnnotationWrite

    /** The book already has a highlight at this place. */
    data object AlreadyThere : GrimmoryAnnotationWrite

    /** Grimmory turned the highlight down as it is (a place it can't take, say); sending it again won't help. */
    data class Refused(val message: String) : GrimmoryAnnotationWrite
}

/** Grimmory's highlights of EPUBs: one book's at once, every book's without places, and one by one changes. */
interface GrimmoryAnnotationsApi {
    /** All of one book's highlights, with their places. */
    suspend fun bookHighlights(accessToken: String, bookId: String): List<GrimmoryAnnotation>

    /** Every highlight of every book in one request, without their places (Grimmory's notebook). */
    suspend fun allHighlights(accessToken: String): List<GrimmoryAnnotation>

    suspend fun create(accessToken: String, highlight: GrimmoryAnnotationCreate): GrimmoryAnnotationWrite

    suspend fun update(accessToken: String, annotationId: String, change: GrimmoryAnnotationChange): GrimmoryAnnotationWrite

    /** False when the server no longer had it, which is as good as removed. */
    suspend fun delete(accessToken: String, annotationId: String): Boolean
}

class KtorGrimmoryAnnotationsApi(
    private val httpClient: HttpClient,
    baseUrl: String,
) : GrimmoryAnnotationsApi {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun bookHighlights(accessToken: String, bookId: String): List<GrimmoryAnnotation> =
        authenticatedRequest {
            val response = httpClient.get("$baseUrl/api/v1/annotations/book/$bookId") { bearerAuth(accessToken) }
            response.throwIfGrimmoryError()
            response.body<List<GrimmoryAnnotationDto>>().mapNotNull(GrimmoryAnnotationDto::toAnnotation)
        }

    override suspend fun allHighlights(accessToken: String): List<GrimmoryAnnotation> = authenticatedRequest {
        val response = httpClient.get("$baseUrl/api/v1/notebook/export") {
            bearerAuth(accessToken)
            url {
                parameters.append("types", NOTEBOOK_HIGHLIGHT)
                parameters.append("sort", "asc")
            }
        }
        response.throwIfGrimmoryError()
        response.body<List<GrimmoryNotebookEntryDto>>()
            .filter { entry -> entry.type == null || entry.type == NOTEBOOK_HIGHLIGHT }
            .mapNotNull(GrimmoryNotebookEntryDto::toHighlightOrNull)
    }

    override suspend fun create(accessToken: String, highlight: GrimmoryAnnotationCreate): GrimmoryAnnotationWrite =
        writeRequest {
            httpClient.post("$baseUrl/api/v1/annotations") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(highlight.toRequestDto())
            }
        }

    override suspend fun update(
        accessToken: String,
        annotationId: String,
        change: GrimmoryAnnotationChange,
    ): GrimmoryAnnotationWrite = writeRequest {
        httpClient.put("$baseUrl/api/v1/annotations/$annotationId") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(GrimmoryUpdateAnnotationRequestDto(color = change.color, style = change.style, note = change.note.take(NOTE_LIMIT)))
        }
    }

    override suspend fun delete(accessToken: String, annotationId: String): Boolean {
        val response = statusAware { httpClient.delete("$baseUrl/api/v1/annotations/$annotationId") { bearerAuth(accessToken) } }
        if (response.status == HttpStatusCode.NotFound) return false
        response.throwIfGrimmoryError()
        return true
    }

    private suspend fun writeRequest(send: suspend () -> HttpResponse): GrimmoryAnnotationWrite {
        val response = statusAware(send)
        return when (response.status) {
            HttpStatusCode.NotFound -> GrimmoryAnnotationWrite.Missing
            HttpStatusCode.Conflict -> GrimmoryAnnotationWrite.AlreadyThere
            HttpStatusCode.BadRequest, HttpStatusCode.UnprocessableEntity ->
                GrimmoryAnnotationWrite.Refused(response.bodyAsText().take(REFUSAL_MESSAGE_LIMIT))
            else -> {
                response.throwIfGrimmoryError()
                val saved = response.body<GrimmoryAnnotationDto>().toAnnotation()
                    ?: error("Grimmory saved the highlight without an id.")
                GrimmoryAnnotationWrite.Saved(saved)
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

private const val NOTEBOOK_HIGHLIGHT = "HIGHLIGHT"

// Grimmory's limits on a highlight's fields.
private const val TEXT_LIMIT = 5_000
private const val NOTE_LIMIT = 5_000
private const val CHAPTER_TITLE_LIMIT = 500
private const val REFUSAL_MESSAGE_LIMIT = 300

@Serializable
internal data class GrimmoryAnnotationDto(
    val id: Long? = null,
    val bookId: Long? = null,
    val cfi: String? = null,
    val text: String? = null,
    val color: String? = null,
    val style: String? = null,
    val note: String? = null,
    val chapterTitle: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class GrimmoryCreateAnnotationRequestDto(
    val bookId: Long,
    val cfi: String,
    val text: String,
    val color: String,
    val style: String,
    val note: String? = null,
    val chapterTitle: String? = null,
)

@Serializable
internal data class GrimmoryUpdateAnnotationRequestDto(
    val color: String,
    val style: String,
    val note: String,
)

internal fun GrimmoryAnnotationDto.toAnnotation(): GrimmoryAnnotation? {
    val annotationId = id ?: return null
    return GrimmoryAnnotation(
        id = annotationId.toString(),
        bookId = bookId?.toString(),
        cfi = cfi?.takeIf(String::isNotBlank),
        text = text.orEmpty(),
        color = color,
        style = style,
        note = note?.takeIf(String::isNotBlank),
        chapterTitle = chapterTitle?.takeIf(String::isNotBlank),
        createdAtEpochMillis = createdAt.toServerEpochMillisOrNull(),
        updatedAtEpochMillis = updatedAt.toServerEpochMillisOrNull(),
    )
}

internal fun GrimmoryNotebookEntryDto.toHighlightOrNull(): GrimmoryAnnotation? {
    val entryId = id ?: return null
    return GrimmoryAnnotation(
        id = entryId.toString(),
        bookId = bookId?.toString(),
        cfi = null,
        text = text.orEmpty(),
        color = color,
        style = style,
        note = note?.takeIf(String::isNotBlank),
        chapterTitle = chapterTitle?.takeIf(String::isNotBlank),
        createdAtEpochMillis = createdAt.toServerEpochMillisOrNull(),
        updatedAtEpochMillis = updatedAt.toServerEpochMillisOrNull(),
    )
}

private fun GrimmoryAnnotationCreate.toRequestDto(): GrimmoryCreateAnnotationRequestDto =
    GrimmoryCreateAnnotationRequestDto(
        bookId = bookId.toLongOrNull() ?: error("Grimmory bookId must be numeric: $bookId"),
        cfi = cfi,
        text = text.take(TEXT_LIMIT),
        color = color,
        style = style,
        note = note?.takeIf(String::isNotBlank)?.take(NOTE_LIMIT),
        chapterTitle = chapterTitle?.takeIf(String::isNotBlank)?.take(CHAPTER_TITLE_LIMIT),
    )
