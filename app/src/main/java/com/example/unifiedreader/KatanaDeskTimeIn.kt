package com.example.unifiedreader

import android.util.Log
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.ReadableItemKind
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.journal.JournalMediaType
import com.example.unifiedreader.journal.JournalReadingSession
import com.example.unifiedreader.journal.JournalRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Every session that came from the paired desk has an id starting so: it's never sent back. */
internal const val DESK_SESSION_PREFIX = "desk:"

/** Where the desk's sessions on books not on this device are kept, so they count in the totals only. */
private const val DESK_BOOK_STAND_IN = "katana-desk:"
private const val DESK_TIME_PATH = "/katana-sync/v1/reading-sessions"
private const val DESK_TIME_TIMEOUT_MILLIS = 10_000L
private const val MILLIS_PER_SECOND = 1000L

internal fun JournalReadingSession.cameFromDesk(): Boolean = id.startsWith(DESK_SESSION_PREFIX)

/** One of the desk's own reading sessions: an hour's reading of one book there (its seconds grow while it's read). */
internal data class KatanaDeskTimeRow(
    val id: String,
    val deskBookId: String?,
    val title: String,
    val formatName: String,
    val startMillis: Long,
    val endMillis: Long,
    val seconds: Long,
)

/** The desk's `/katana-sync/v1/reading-sessions` answer read; a row it can't read is left out. */
internal fun parseKatanaDeskTime(body: String): List<KatanaDeskTimeRow> {
    val rows = runCatching { JSONObject(body).optJSONArray("sessions") }.getOrNull() ?: return emptyList()
    return (0 until rows.length()).mapNotNull { index -> runCatching { rowOf(rows.getJSONObject(index)) }.getOrNull() }
}

private fun rowOf(row: JSONObject): KatanaDeskTimeRow? = row.optString("id").takeIf { it.startsWith(DESK_SESSION_PREFIX) }?.let { id ->
    KatanaDeskTimeRow(
        id = id,
        deskBookId = row.opt("bookId")?.toString()?.takeIf { it.isNotBlank() && it != "null" },
        title = row.optString("title").ifBlank { "A book" },
        formatName = row.optString("format"),
        startMillis = OffsetDateTime.parse(row.getString("startTime")).toInstant().toEpochMilli(),
        endMillis = OffsetDateTime.parse(row.getString("endTime")).toInstant().toEpochMilli(),
        seconds = row.optLong("seconds", 0L),
    )
}

/**
 * The desk's session as this device's Journal keeps it: on [item] when that book here is the desk's, else on a stand-in
 * so it counts in the totals but never on a wrong book. Null when there's no time in it. A time past [nowMillis] is
 * cut at now, and the length never runs past its own start and end.
 */
internal fun KatanaDeskTimeRow.toJournalSession(item: ReadableItem?, nowMillis: Long): JournalReadingSession? {
    val end = minOf(endMillis, nowMillis)
    val start = minOf(startMillis, end)
    val duration = minOf(seconds.coerceAtLeast(0L) * MILLIS_PER_SECOND, end - start)
    val format = item?.format ?: FormatType.entries.firstOrNull { it.name.equals(formatName, ignoreCase = true) } ?: FormatType.EPUB
    val kind = item?.kind ?: ReadableItemKind.BOOK
    return JournalReadingSession(
        id = id,
        itemId = item?.id ?: "$DESK_BOOK_STAND_IN${deskBookId.orEmpty()}",
        sourceType = item?.sourceType ?: SourceType.LOCAL,
        libraryId = item?.libraryId ?: "katana-desk",
        itemTitle = item?.title ?: title,
        coverUrl = item?.coverUrl,
        itemKind = kind,
        format = format,
        mediaType = if (kind == ReadableItemKind.COMIC) JournalMediaType.COMIC else JournalMediaType.BOOK,
        startedAtEpochMillis = start,
        endedAtEpochMillis = end,
        durationMillis = duration,
        fromPercent = null,
        toPercent = null,
        fromPage = null,
        toPage = null,
        pageCount = null,
    ).takeIf { duration > 0 }
}

/**
 * Reading time from the paired desk: its own sessions (never ones this device sent it) are saved in the Journal by
 * their id, so asking again replaces them and nothing counts twice. A desk too old to know the question is skipped.
 */
internal class KatanaDeskTimeIn(
    private val http: HttpClient,
    private val journal: JournalRepository,
    private val connection: KatanaDeskConnection,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [linked]: the books here that are on the desk, by the desk's book id in [links]. A failure doesn't stop the sync. */
    suspend fun pullQuietly(desk: KatanaDeskPaired, linked: List<ReadableItem>, links: Map<String, String>) {
        runSuspendCatching { pull(desk, linked, links) }.onFailure { failure ->
            failure.rethrowIfServerAddressUnreachable()
            Log.w("KatanaDesk", "Reading time from ${desk.name} didn't come.", failure)
        }
    }

    suspend fun pull(desk: KatanaDeskPaired, linked: List<ReadableItem>, links: Map<String, String>) {
        val query = connection.deskTimeSince?.let { "?since=$it" }.orEmpty()
        val response = withTimeoutOrNull(DESK_TIME_TIMEOUT_MILLIS) {
            try {
                http.get("${desk.baseUrl}$DESK_TIME_PATH$query") { bearerAuth(desk.token) }
            } catch (refused: ResponseException) {
                refused.response
            }
        } ?: throw KatanaDeskNoAnswer()
        if (!response.status.isSuccess()) return
        val byDeskBook = linked.mapNotNull { item -> links[item.id]?.let { it to item } }.toMap()
        val now = clock()
        val sessions = parseKatanaDeskTime(response.bodyAsText())
            .mapNotNull { row -> row.toJournalSession(row.deskBookId?.let(byDeskBook::get), now) }
        journal.saveReadingSessions(sessions)
        // yesterday's hours may still grow (read past midnight): the next pull asks from there
        connection.deskTimeSince = LocalDate.now().minusDays(1).toString()
    }
}
