package com.example.unifiedreader

import com.example.unifiedreader.feature.annotations.BookmarkServer
import com.example.unifiedreader.feature.annotations.BookmarkServerAnswer
import com.example.unifiedreader.feature.annotations.BookmarkUpload
import com.example.unifiedreader.feature.annotations.HighlightServer
import com.example.unifiedreader.feature.annotations.HighlightServerAnswer
import com.example.unifiedreader.feature.annotations.HighlightUpload

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
