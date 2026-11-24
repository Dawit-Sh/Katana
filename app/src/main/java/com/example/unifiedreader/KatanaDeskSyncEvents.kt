package com.example.unifiedreader

import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.data.grimmory.GrimmoryApi
import com.example.unifiedreader.data.grimmory.GrimmoryReadingSessionRequest
import com.example.unifiedreader.feature.annotations.BookmarkServer
import com.example.unifiedreader.feature.annotations.BookmarkServerAnswer
import com.example.unifiedreader.feature.annotations.BookmarkUpload
import com.example.unifiedreader.feature.annotations.HighlightServer
import com.example.unifiedreader.feature.annotations.HighlightServerAnswer
import com.example.unifiedreader.feature.annotations.HighlightUpload
import com.example.unifiedreader.journal.JournalReadingSession
import com.example.unifiedreader.journal.JournalRepository
import java.time.Instant

/** A sync with Katana Desk starting or ending, for a sign on screen ("Syncing with the PC", "Synced"). */
sealed interface KatanaDeskSyncEvent {
    val atEpochMillis: Long
    val deskName: String

    data class Started(override val atEpochMillis: Long, override val deskName: String) : KatanaDeskSyncEvent

    data class Ended(
        override val atEpochMillis: Long,
        override val deskName: String,
        val sinceEpochMillis: Long,
        val result: KatanaDeskSyncResult,
    ) : KatanaDeskSyncEvent
}

/** A sync with Katana Desk going on now, for the bar under the top bar. */
data class KatanaDeskActivity(
    val deskName: String,
    val sinceEpochMillis: Long,
    /** The PC's book coming over (the first sync after pairing), while it does */
    val getting: KatanaDeskGetting? = null,
)

/** A book coming over from the PC: its title, and how much of its file is here so far. */
data class KatanaDeskGetting(val title: String, val bytes: Long, val totalBytes: Long?) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) }
}

/** How one sync with Katana Desk went. */
data class KatanaDeskSyncResult(
    /** Books here that are on the desk too */
    val books: Int = 0,
    /** Books whose place changed here (the desk's came down) */
    val placesHere: Int = 0,
    val placesSent: Int = 0,
    val statusesSent: Int = 0,
    /** Highlights and bookmarks sent to the desk (new, changed or removed) */
    val marksSent: Int = 0,
    /** What became of the book being read on the PC, when this sync brought it (the first after pairing) */
    val brought: KatanaDeskBringing? = null,
    /** Why it didn't finish, in words to show; null when it did */
    val problem: String? = null,
) {
    /** Whether anything changed on either side that this device knows of. */
    val changed: Boolean
        get() = placesHere + placesSent + statusesSent + marksSent > 0 || brought is KatanaDeskBringing.Arrived
}

/** Counts what one sync sends and changes. */
internal class KatanaDeskTally {
    var placesSent = 0
    var statusesSent = 0
    var marksSent = 0

    fun result(books: Int, placesHere: Int, brought: KatanaDeskBringing?, problem: String?) = KatanaDeskSyncResult(
        books = books,
        placesHere = placesHere,
        placesSent = placesSent,
        statusesSent = statusesSent,
        marksSent = marksSent,
        brought = brought,
        problem = problem,
    )
}

/** [server], counting what reaches the desk. */
internal class CountingHighlightServer(private val server: HighlightServer, private val tally: KatanaDeskTally) : HighlightServer {
    override suspend fun create(upload: HighlightUpload.Create): HighlightServerAnswer =
        server.create(upload).also { if (it is HighlightServerAnswer.Saved) tally.marksSent++ }

    override suspend fun update(upload: HighlightUpload.Update): HighlightServerAnswer =
        server.update(upload).also { if (it is HighlightServerAnswer.Saved) tally.marksSent++ }

    override suspend fun delete(upload: HighlightUpload.Delete): Boolean = server.delete(upload).also { if (it) tally.marksSent++ }
}

/** [server], counting what reaches the desk. */
internal class CountingBookmarkServer(private val server: BookmarkServer, private val tally: KatanaDeskTally) : BookmarkServer {
    override suspend fun create(upload: BookmarkUpload.Create): BookmarkServerAnswer =
        server.create(upload).also { if (it is BookmarkServerAnswer.Saved) tally.marksSent++ }

    override suspend fun update(upload: BookmarkUpload.Update): BookmarkServerAnswer =
        server.update(upload).also { if (it is BookmarkServerAnswer.Saved) tally.marksSent++ }

    override suspend fun delete(upload: BookmarkUpload.Delete): Boolean = server.delete(upload).also { if (it) tally.marksSent++ }
}

/**
 * Reading time for the desk's "time left at your pace": each reading session on a book here that's on the desk (the
 * Journal keeps them, with the place at each end) goes to the desk once, in Grimmory's own shape. A desk too old to
 * take them answers no, and the sessions wait; the sync goes on either way. Sessions that came from the desk
 * ([cameFromDesk]) never go back to it.
 */
internal class KatanaDeskReadingTime(
    private val journal: JournalRepository,
    private val connection: KatanaDeskConnection,
) {
    suspend fun send(api: GrimmoryApi, token: String, item: ReadableItem, bookId: String) {
        val deskBookId = bookId.toLongOrNull() ?: return
        val since = connection.readingSentUntil(item.id)
        val sessions = journal.getReadingSessionsForItem(item.id)
            .filter { it.endedAtEpochMillis > since && it.durationMillis > 0 && !it.cameFromDesk() }
            .sortedBy { it.endedAtEpochMillis }
        for (session in sessions) {
            val sent = runSuspendCatching {
                api.createReadingSession(token, session.toDeskSession(deskBookId, item.format.name))
            }
            val failure = sent.exceptionOrNull()
            if (failure != null) {
                failure.rethrowIfServerAddressUnreachable()
                return
            }
            connection.markReadingSent(item.id, session.endedAtEpochMillis)
        }
    }
}

/** A Journal reading session as Grimmory's request (places as percentages, as Grimmory keeps them). */
internal fun JournalReadingSession.toDeskSession(
    deskBookId: Long,
    bookType: String,
): GrimmoryReadingSessionRequest {
    val from = fromPercent?.let { (it.coerceIn(0.0, 1.0) * PERCENT).toFloat() }
    val to = toPercent?.let { (it.coerceIn(0.0, 1.0) * PERCENT).toFloat() }
    return GrimmoryReadingSessionRequest(
        bookId = deskBookId,
        bookType = bookType,
        startTime = Instant.ofEpochMilli(startedAtEpochMillis).toString(),
        endTime = Instant.ofEpochMilli(endedAtEpochMillis).toString(),
        durationSeconds = (durationMillis / MILLIS_PER_SECOND).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
        durationFormatted = null,
        startProgress = from,
        endProgress = to,
        progressDelta = if (from != null && to != null) to - from else null,
        startLocation = fromPage?.toString(),
        endLocation = toPage?.toString(),
    )
}

private const val PERCENT = 100.0
private const val MILLIS_PER_SECOND = 1000L
