package com.example.unifiedreader.data.grimmory

import com.example.unifiedreader.core.network.LenientJson
import com.example.unifiedreader.core.model.toServerEpochMillisOrNull
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What Grimmory (or Katana Desk) answered to a status: the [status] it keeps now, set at [setAtEpochMillis] when it says. */
data class GrimmoryReadStatusAnswer(
    val status: String,
    val setAtEpochMillis: Long? = null,
)

@Serializable
internal data class GrimmoryReadStatusRequestDto(
    val bookIds: List<Long>,
    val status: String,
)

/**
 * Sets book [bookId]'s reading status as Grimmory's web app does: `POST /api/v1/books/status` with
 * `{"bookIds":[id],"status":"RE_READING"}`. Grimmory answers with each book's `readStatus` (and
 * `readStatusModifiedTime`); an answer without them reads as the status asked for.
 */
internal suspend fun HttpClient.postGrimmoryReadStatus(
    baseUrl: String,
    accessToken: String,
    bookId: String,
    status: String,
): GrimmoryReadStatusAnswer {
    val parsedBookId = bookId.toLongOrNull() ?: error("Grimmory bookId must be numeric: $bookId")
    return authenticatedRequest {
        val response = post("$baseUrl/api/v1/books/status") {
            bearerAuth(accessToken)
            contentType(ContentType.Application.Json)
            setBody(GrimmoryReadStatusRequestDto(bookIds = listOf(parsedBookId), status = status))
        }
        response.throwIfGrimmoryError()
        response.bodyAsText().toReadStatusAnswer(bookId = parsedBookId) ?: GrimmoryReadStatusAnswer(status = status)
    }
}

// The answer's entry for the book (or its only one): its readStatus and when it was set.
private fun String.toReadStatusAnswer(bookId: Long): GrimmoryReadStatusAnswer? {
    val rows = runCatching { LenientJson.decodeFromString<JsonElement>(this) }.getOrNull() as? JsonArray ?: return null
    val books = rows.filterIsInstance<JsonObject>()
    val row = books.firstOrNull { it.long("bookId") == bookId } ?: books.singleOrNull() ?: return null
    val status = row.text("readStatus") ?: return null
    return GrimmoryReadStatusAnswer(status = status, setAtEpochMillis = row.text("readStatusModifiedTime")?.toServerEpochMillisOrNull())
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)

private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.content?.toLongOrNull()
