package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.dao.ReaderBookmarkDao
import com.example.unifiedreader.core.database.dao.ReaderBookmarkSyncDao
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_ON_DEVICE
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import com.example.unifiedreader.core.database.entity.WaitingBookmarkKey
import com.example.unifiedreader.core.model.clampedToNowIfBeyondLimit
import com.example.unifiedreader.core.model.trustedAt
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * A bookmark as the book's server has it: its [place] (as [BookmarkPlaces] says), the reader's name for
 * it ([title]) and [note]. [updatedAtEpochMillis] is the server's time of its last change.
 */
data class RemoteBookmark(
    val remoteId: String,
    val place: String,
    val title: String?,
    val note: String?,
    val createdAtEpochMillis: Long?,
    val updatedAtEpochMillis: Long?,
)

/** A change for the server, made from a bookmark on this device as it was when the sync read it ([expectedUpdatedAt]). */
sealed interface BookmarkUpload {
    val localId: String
    val expectedUpdatedAt: Long

    /** [place] as [BookmarkPlaces] says: a CFI, or a page from 1. */
    data class Create(
        override val localId: String,
        override val expectedUpdatedAt: Long,
        val place: String,
        val title: String?,
        val note: String?,
    ) : BookmarkUpload

    data class Update(
        override val localId: String,
        override val expectedUpdatedAt: Long,
        val remoteId: String,
        val title: String?,
        val note: String?,
    ) : BookmarkUpload

    data class Delete(
        override val localId: String,
        override val expectedUpdatedAt: Long,
        val remoteId: String,
    ) : BookmarkUpload
}

/** A stored bookmark a sync writes over, if it still has [expectedUpdatedAt]; null for one new to this device. */
data class BookmarkRowWrite(val row: ReaderBookmarkEntity, val expectedUpdatedAt: Long?)

/** A bookmark a sync removes for good, if it still has [expectedUpdatedAt]. */
data class BookmarkRowPurge(val id: String, val expectedUpdatedAt: Long)

/** What a sync of one book does: [writes] and [purges] here, then [uploads] to the server. */
data class BookmarkSyncPlan(
    val writes: List<BookmarkRowWrite> = emptyList(),
    val purges: List<BookmarkRowPurge> = emptyList(),
    val uploads: List<BookmarkUpload> = emptyList(),
)

/**
 * Brings one book's bookmarks here ([local], removed ones included) and on the server ([remote], the
 * book's whole list there) in step, by the same rules as highlights:
 * - The same bookmark on both sides by the server's id. One made here without an id yet (its answer
 *   lost, or the server already had one there) is matched by its place: the same CFI however it is
 *   written, or the same page.
 * - The last change wins: a change here not yet sent loses only to a server copy newer by
 *   [HIGHLIGHT_CLOCK_MARGIN_MILLIS]; a bookmark in step takes the server's name and note. A time more
 *   than a day ahead of this device's clock doesn't count (a server's is read as no time, one here as now).
 * - Removed on one side is removed on the other, unless the other side changed it later.
 * - A new bookmark here goes up once its place the server's way is known: worked out by the reader in
 *   an EPUB, its page in a book read by the page.
 * A name is compared as the server sees it: a bookmark not named here goes up named by its chapter (as
 * Grimmory's web reader names its own) or "Page 12", and that name coming back doesn't name it here.
 */
@Suppress("LongParameterList")
fun planBookmarkSync(
    book: AnnotatedBook,
    local: List<ReaderBookmarkEntity>,
    remote: List<RemoteBookmark>,
    nowEpochMillis: Long,
    newId: () -> String,
    places: BookmarkPlaces = BookmarkPlaces.EpubCfi,
): BookmarkSyncPlan {
    // A place of the other kind (another format of the same server book) is no place in this copy: left alone there.
    val trusted = remote.filter { server -> places.holds(server.place) }.map { server -> server.withTrustedTimes(nowEpochMillis) }
    val remoteIds = trusted.mapTo(mutableSetOf(), RemoteBookmark::remoteId)
    val byRemoteId = local.filter { it.remoteId != null }.associateBy { it.remoteId }
    val unmatched = local.filter { row -> row.remoteId == null || row.remoteId !in remoteIds }
    val plan = BookmarkPlanBuilder(places, nowEpochMillis)
    val matched = mutableSetOf<String>()
    trusted.forEach { server ->
        val row = byRemoteId[server.remoteId] ?: unmatched.firstOrNull { row ->
            row.id !in matched && !(row.deleted && row.remoteId == null) && places.same(row.serverPlace(places), server.place)
        }
        if (row == null) {
            plan.writes += BookmarkRowWrite(server.toNewRow(book, newId(), nowEpochMillis, places), expectedUpdatedAt = null)
        } else {
            matched += row.id
            plan.matched(row, server)
        }
    }
    local.filterNot { it.id in matched }.forEach(plan::notOnServer)
    return plan.build()
}

// Server times more than a day ahead of this device's clock are no times to go by.
private fun RemoteBookmark.withTrustedTimes(nowEpochMillis: Long) = copy(
    createdAtEpochMillis = createdAtEpochMillis?.trustedAt(nowEpochMillis),
    updatedAtEpochMillis = updatedAtEpochMillis?.trustedAt(nowEpochMillis),
)

private class BookmarkPlanBuilder(private val places: BookmarkPlaces, private val nowEpochMillis: Long) {
    val writes = mutableListOf<BookmarkRowWrite>()
    val purges = mutableListOf<BookmarkRowPurge>()
    val uploads = mutableListOf<BookmarkUpload>()

    fun build() = BookmarkSyncPlan(writes, purges, uploads)

    fun matched(row: ReaderBookmarkEntity, server: RemoteBookmark) {
        val linked = row.linkedTo(server)
        val serverNewer = server.isNewerThan(row)
        when {
            row.syncState == HIGHLIGHT_IN_STEP && !row.deleted -> write(row, linked.takingServerCopy(server))
            row.remoteId == null && !row.deleted && row.syncState == HIGHLIGHT_WAITING -> firstPairing(row, linked, server, serverNewer)
            serverNewer -> write(row, linked.takingServerCopy(server).copy(deleted = false))
            row.deleted -> {
                write(row, linked)
                uploads += BookmarkUpload.Delete(row.id, row.updatedAtEpochMillis, server.remoteId)
            }
            row.differsFrom(server) -> {
                write(row, linked)
                uploads += BookmarkUpload.Update(row.id, row.updatedAtEpochMillis, server.remoteId, row.serverTitle(), row.note)
            }
            else -> write(row, linked.copy(syncState = HIGHLIGHT_IN_STEP))
        }
    }

    private fun ReaderBookmarkEntity.linkedTo(server: RemoteBookmark): ReaderBookmarkEntity = when (places) {
        BookmarkPlaces.EpubCfi -> copy(remoteId = server.remoteId, remoteCfi = remoteCfi ?: server.place)
        is BookmarkPlaces.Pages -> copy(remoteId = server.remoteId)
    }

    // Newer than [row]'s change by the margin; a change made here while this device's clock ran days ahead counts as made now.
    private fun RemoteBookmark.isNewerThan(row: ReaderBookmarkEntity): Boolean {
        val changedHereAt = row.updatedAtEpochMillis.clampedToNowIfBeyondLimit(nowEpochMillis)
        return updatedAtEpochMillis?.let { it > changedHereAt + HIGHLIGHT_CLOCK_MARGIN_MILLIS } ?: false
    }

    /**
     * A bookmark made here meets the server's at its place for the first time: its own copy whose answer was
     * lost (sent before the note, which follows in a second request), or one made there too. Neither side's
     * name or note is dropped for being older: an empty one takes the other's; only two different ones go by
     * time.
     */
    private fun firstPairing(row: ReaderBookmarkEntity, linked: ReaderBookmarkEntity, server: RemoteBookmark, serverNewer: Boolean) {
        val serverTitle = server.title.asText()?.takeUnless { it == row.defaultServerTitle() }
        val title = pick(row.title.asText(), serverTitle, serverNewer)
        val note = pick(row.note.asText(), server.note.asText(), serverNewer)
        val merged = linked.copy(title = title, note = note)
        if (merged.differsFrom(server)) {
            write(row, merged)
            uploads += BookmarkUpload.Update(row.id, row.updatedAtEpochMillis, server.remoteId, merged.serverTitle(), merged.note)
        } else {
            write(row, merged.copy(syncState = HIGHLIGHT_IN_STEP))
        }
    }

    private fun pick(here: String?, there: String?, thereNewer: Boolean): String? = when {
        here == null -> there
        there == null || here == there -> here
        thereNewer -> there
        else -> here
    }

    fun notOnServer(row: ReaderBookmarkEntity) {
        when {
            row.deleted || (row.remoteId != null && row.syncState == HIGHLIGHT_IN_STEP) ->
                purges += BookmarkRowPurge(row.id, row.updatedAtEpochMillis)
            row.syncState != HIGHLIGHT_WAITING -> Unit
            else -> {
                if (row.remoteId != null) write(row, row.copy(remoteId = null))
                row.serverPlace(places)?.let { place ->
                    uploads += BookmarkUpload.Create(row.id, row.updatedAtEpochMillis, place, row.serverTitle(), row.note)
                }
            }
        }
    }

    private fun write(stored: ReaderBookmarkEntity, row: ReaderBookmarkEntity) {
        if (row != stored) writes += BookmarkRowWrite(row, expectedUpdatedAt = stored.updatedAtEpochMillis)
    }

    // The server's name stays off this device while it is only the chapter's or the page's.
    private fun ReaderBookmarkEntity.takingServerCopy(server: RemoteBookmark): ReaderBookmarkEntity {
        val changedHereAt = updatedAtEpochMillis.clampedToNowIfBeyondLimit(nowEpochMillis)
        return copy(
            title = server.title?.takeUnless { it.trim() == defaultServerTitle() },
            note = server.note,
            updatedAtEpochMillis = maxOf(changedHereAt, server.updatedAtEpochMillis ?: changedHereAt),
            syncState = HIGHLIGHT_IN_STEP,
        )
    }
}

private fun ReaderBookmarkEntity.differsFrom(server: RemoteBookmark): Boolean =
    serverTitle().asText() != server.title.asText() || note.asText() != server.note.asText()

private fun String?.asText(): String? = this?.trim()?.takeIf(String::isNotEmpty)

// A bookmark new to this device. In an EPUB it has no place in this copy of the book yet (the reader finds
// it there); by the page, its page is its place.
private fun RemoteBookmark.toNewRow(
    book: AnnotatedBook,
    id: String,
    nowEpochMillis: Long,
    places: BookmarkPlaces,
): ReaderBookmarkEntity {
    val updatedAt = updatedAtEpochMillis ?: createdAtEpochMillis ?: nowEpochMillis
    val row = ReaderBookmarkEntity(
        id = id,
        itemId = book.itemId,
        bookTitle = book.title,
        bookAuthor = book.author,
        href = "",
        locatorJson = "",
        chapterTitle = null,
        progression = null,
        page = null,
        pageCount = null,
        text = null,
        createdAtEpochMillis = createdAtEpochMillis ?: updatedAt,
        updatedAtEpochMillis = updatedAt,
        deleted = false,
        syncState = HIGHLIGHT_IN_STEP,
        remoteId = remoteId,
        remoteCfi = place,
        title = title.asText(),
        note = note.asText(),
    )
    val pageNumber = place.toPageNumberOrNull()
    if (places !is BookmarkPlaces.Pages || pageNumber == null) return row
    val pageIndex = pageNumber - 1
    val pageCount = places.pageCount?.takeIf { it > 0 }
    return row.copy(
        href = "$PAGE_HREF_PREFIX$pageIndex",
        locatorJson = "{\"page\":$pageIndex}",
        progression = pageCount?.let { count -> if (count > 1) (pageIndex.toDouble() / (count - 1)).coerceIn(0.0, 1.0) else 0.0 },
        page = pageNumber,
        pageCount = pageCount,
        remoteCfi = null,
        title = title.asText()?.takeUnless { it == pageTitle(pageNumber) },
    )
}

/** A book's server, as a bookmark sync talks to it. A failure to reach it is thrown. */
interface BookmarkServer {
    suspend fun create(upload: BookmarkUpload.Create): BookmarkServerAnswer

    suspend fun update(upload: BookmarkUpload.Update): BookmarkServerAnswer

    /** False when the server no longer had it, which is as good as removed. */
    suspend fun delete(upload: BookmarkUpload.Delete): Boolean
}

sealed interface BookmarkServerAnswer {
    data class Saved(val remoteId: String) : BookmarkServerAnswer

    data object Missing : BookmarkServerAnswer

    data object AlreadyThere : BookmarkServerAnswer

    data object Refused : BookmarkServerAnswer
}

enum class BookmarkSync { Done, ServerChangedMeanwhile }

/** The bookmarks as a sync reads and writes them. */
interface BookmarkSyncStore {
    fun observeWaiting(): Flow<List<WaitingBookmarkKey>>

    suspend fun rowsOf(itemId: String): List<ReaderBookmarkEntity>

    suspend fun booksOnServer(): Set<String>

    suspend fun apply(writes: List<BookmarkRowWrite>, purges: List<BookmarkRowPurge>)

    suspend fun created(localId: String, expectedUpdatedAt: Long, remoteId: String)

    suspend fun sent(localId: String, expectedUpdatedAt: Long)

    suspend fun removedOnServer(localId: String)

    suspend fun lostOnServer(localId: String)

    suspend fun refused(localId: String, expectedUpdatedAt: Long)

    /**
     * Another server takes over [itemId]'s marks (a different Katana Desk paired): each goes to it as new,
     * and removals meant for the old server are dropped. Their ids were the old server's and mean nothing
     * to the new one, which would read them as removed there.
     */
    suspend fun newServer(itemId: String) = Unit
}

/**
 * Brings a book's bookmarks in step with its server ([planBookmarkSync]), then sends what waits,
 * one change at a time, each answer saved as it comes. One sync at a time: the caller runs them in turn.
 */
class BookmarkSyncEngine(
    private val store: BookmarkSyncStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun syncBook(
        book: AnnotatedBook,
        remote: List<RemoteBookmark>,
        server: BookmarkServer,
        places: BookmarkPlaces = BookmarkPlaces.EpubCfi,
    ): BookmarkSync {
        val rows = store.rowsOf(book.itemId)
        val plan = planBookmarkSync(book, rows, remote, now(), newId, places)
        // Many gone from the server at once is held for the reader to answer, not removed here.
        val kept = rows.filterNot(ReaderBookmarkEntity::deleted).mapTo(mutableSetOf(), ReaderBookmarkEntity::id)
        val purges = holdingMassRemoval(book, HeldMarkKind.Bookmark, kept, plan.purges) { HeldMark(it.id, it.expectedUpdatedAt) }
        if (plan.writes.isNotEmpty() || purges.isNotEmpty()) store.apply(plan.writes, purges)
        val serverChanged = plan.uploads.map { upload -> send(upload, server) }.any { it }
        return if (serverChanged) BookmarkSync.ServerChangedMeanwhile else BookmarkSync.Done
    }

    // True when the server already had a bookmark at a new one's place.
    private suspend fun send(upload: BookmarkUpload, server: BookmarkServer): Boolean {
        when (upload) {
            is BookmarkUpload.Create -> when (val answer = server.create(upload)) {
                is BookmarkServerAnswer.Saved -> store.created(upload.localId, upload.expectedUpdatedAt, answer.remoteId)
                BookmarkServerAnswer.AlreadyThere -> return true
                BookmarkServerAnswer.Refused -> store.refused(upload.localId, upload.expectedUpdatedAt)
                BookmarkServerAnswer.Missing -> Unit
            }
            is BookmarkUpload.Update -> when (server.update(upload)) {
                BookmarkServerAnswer.Missing -> store.lostOnServer(upload.localId)
                is BookmarkServerAnswer.Saved,
                BookmarkServerAnswer.Refused,
                BookmarkServerAnswer.AlreadyThere -> store.sent(upload.localId, upload.expectedUpdatedAt)
            }
            is BookmarkUpload.Delete -> {
                server.delete(upload)
                store.removedOnServer(upload.localId)
            }
        }
        return false
    }
}

class RoomBookmarkSyncStore(
    private val dao: ReaderBookmarkSyncDao,
    private val bookmarks: ReaderBookmarkDao,
    private val runInTransaction: (() -> Unit) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BookmarkSyncStore {
    override fun observeWaiting(): Flow<List<WaitingBookmarkKey>> =
        dao.observeWaitingBookmarks().distinctUntilChanged().flowOn(ioDispatcher)

    override suspend fun rowsOf(itemId: String): List<ReaderBookmarkEntity> = io { dao.getBookmarkRows(itemId) }

    override suspend fun booksOnServer(): Set<String> = io { dao.getItemIdsWithServerBookmarks().toSet() }

    /** All at once; a write over a stored bookmark only if it hasn't changed since the sync read it. */
    override suspend fun apply(writes: List<BookmarkRowWrite>, purges: List<BookmarkRowPurge>) = io {
        runInTransaction {
            writes.forEach { write ->
                val stored = dao.getBookmark(write.row.id)
                val expected = write.expectedUpdatedAt
                when {
                    expected == null -> {
                        val known = write.row.remoteId?.let { dao.countBookmarksWithRemoteId(write.row.itemId, it) > 0 } ?: false
                        if (stored == null && !known) bookmarks.upsertBookmark(write.row)
                    }
                    stored != null && stored.updatedAtEpochMillis == expected -> bookmarks.upsertBookmark(write.row.keepingPlaceOf(stored))
                }
            }
            purges.forEach { purge -> dao.purgeBookmark(purge.id, purge.expectedUpdatedAt) }
        }
    }

    override suspend fun created(localId: String, expectedUpdatedAt: Long, remoteId: String) =
        io { dao.markBookmarkCreated(localId, remoteId, expectedUpdatedAt) }

    override suspend fun sent(localId: String, expectedUpdatedAt: Long) =
        io { dao.setBookmarkSyncStateIfUnchanged(localId, HIGHLIGHT_IN_STEP, expectedUpdatedAt) }

    override suspend fun removedOnServer(localId: String) = io { dao.purgeRemovedBookmark(localId) }

    override suspend fun lostOnServer(localId: String) = io { dao.forgetBookmarkRemoteId(localId) }

    override suspend fun newServer(itemId: String) = io {
        dao.dropBookmarkRemovalsForOldServer(itemId)
        dao.sendBookmarksToNewServer(itemId)
    }

    override suspend fun refused(localId: String, expectedUpdatedAt: Long) =
        io { dao.setBookmarkSyncStateIfUnchanged(localId, HIGHLIGHT_ON_DEVICE, expectedUpdatedAt) }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }
}

// The place the reader worked out stays when the sync's copy has none.
private fun ReaderBookmarkEntity.keepingPlaceOf(stored: ReaderBookmarkEntity): ReaderBookmarkEntity {
    if (locatorJson.isNotEmpty() || stored.locatorJson.isEmpty()) return copy(remoteCfi = remoteCfi ?: stored.remoteCfi)
    return copy(
        href = stored.href,
        locatorJson = stored.locatorJson,
        progression = stored.progression,
        page = stored.page,
        pageCount = stored.pageCount,
        text = stored.text,
        chapterTitle = stored.chapterTitle,
        remoteCfi = remoteCfi ?: stored.remoteCfi,
    )
}
