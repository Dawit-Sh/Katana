package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.dao.ReaderBookmarkDao
import com.example.unifiedreader.core.database.dao.ReaderBookmarkSyncDao
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_ON_DEVICE
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import com.example.unifiedreader.core.database.entity.WaitingBookmarkKey
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * A bookmark as the book's server has it: its place ([cfi], an EPUB CFI), the reader's name for it
 * ([title]) and [note]. [updatedAtEpochMillis] is the server's time of its last change.
 */
data class RemoteBookmark(
    val remoteId: String,
    val cfi: String,
    val title: String?,
    val note: String?,
    val createdAtEpochMillis: Long?,
    val updatedAtEpochMillis: Long?,
)

/** A change for the server, made from a bookmark on this device as it was when the sync read it ([expectedUpdatedAt]). */
sealed interface BookmarkUpload {
    val localId: String
    val expectedUpdatedAt: Long

    data class Create(
        override val localId: String,
        override val expectedUpdatedAt: Long,
        val cfi: String,
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
 *   lost, or the server already had one there) is matched by its place, however the CFI is written.
 * - The last change wins: a change here not yet sent loses only to a server copy newer by
 *   [HIGHLIGHT_CLOCK_MARGIN_MILLIS]; a bookmark in step takes the server's name and note.
 * - Removed on one side is removed on the other, unless the other side changed it later.
 * - A new bookmark here goes up once the reader has worked out its place the server's way.
 * A name is compared as the server sees it: a bookmark not named here goes up named by its chapter
 * (as Grimmory's web reader names its own), and that name coming back doesn't name it here.
 */
fun planBookmarkSync(
    book: AnnotatedBook,
    local: List<ReaderBookmarkEntity>,
    remote: List<RemoteBookmark>,
    nowEpochMillis: Long,
    newId: () -> String,
): BookmarkSyncPlan {
    val remoteIds = remote.mapTo(mutableSetOf(), RemoteBookmark::remoteId)
    val byRemoteId = local.filter { it.remoteId != null }.associateBy { it.remoteId }
    val unmatched = local.filter { row -> row.remoteId == null || row.remoteId !in remoteIds }
    val plan = BookmarkPlanBuilder()
    val matched = mutableSetOf<String>()
    remote.forEach { server ->
        val row = byRemoteId[server.remoteId] ?: unmatched.firstOrNull { row ->
            row.id !in matched && !(row.deleted && row.remoteId == null) && sameCfiPlace(row.remoteCfi, server.cfi)
        }
        if (row == null) {
            plan.writes += BookmarkRowWrite(server.toNewRow(book, newId(), nowEpochMillis), expectedUpdatedAt = null)
        } else {
            matched += row.id
            plan.matched(row, server)
        }
    }
    local.filterNot { it.id in matched }.forEach(plan::notOnServer)
    return plan.build()
}

/** The name the server has, or would have, for [this]: its own, else its chapter's. */
internal fun ReaderBookmarkEntity.serverTitle(): String? = title ?: chapterTitle?.trim()?.takeIf(String::isNotEmpty)

private class BookmarkPlanBuilder {
    val writes = mutableListOf<BookmarkRowWrite>()
    val purges = mutableListOf<BookmarkRowPurge>()
    val uploads = mutableListOf<BookmarkUpload>()

    fun build() = BookmarkSyncPlan(writes, purges, uploads)

    fun matched(row: ReaderBookmarkEntity, server: RemoteBookmark) {
        val linked = row.copy(remoteId = server.remoteId, remoteCfi = row.remoteCfi ?: server.cfi)
        val serverNewer = server.updatedAtEpochMillis?.let { it > row.updatedAtEpochMillis + HIGHLIGHT_CLOCK_MARGIN_MILLIS } ?: false
        when {
            row.syncState == HIGHLIGHT_IN_STEP && !row.deleted -> write(row, linked.takingServerCopy(server))
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

    fun notOnServer(row: ReaderBookmarkEntity) {
        when {
            row.deleted || (row.remoteId != null && row.syncState == HIGHLIGHT_IN_STEP) ->
                purges += BookmarkRowPurge(row.id, row.updatedAtEpochMillis)
            row.syncState != HIGHLIGHT_WAITING -> Unit
            else -> {
                if (row.remoteId != null) write(row, row.copy(remoteId = null))
                row.remoteCfi?.let { cfi ->
                    uploads += BookmarkUpload.Create(row.id, row.updatedAtEpochMillis, cfi, row.serverTitle(), row.note)
                }
            }
        }
    }

    private fun write(stored: ReaderBookmarkEntity, row: ReaderBookmarkEntity) {
        if (row != stored) writes += BookmarkRowWrite(row, expectedUpdatedAt = stored.updatedAtEpochMillis)
    }

    // The server's name stays off this device while it is only the chapter's.
    private fun ReaderBookmarkEntity.takingServerCopy(server: RemoteBookmark) = copy(
        title = server.title?.takeUnless { it == chapterTitle?.trim() },
        note = server.note,
        updatedAtEpochMillis = maxOf(updatedAtEpochMillis, server.updatedAtEpochMillis ?: updatedAtEpochMillis),
        syncState = HIGHLIGHT_IN_STEP,
    )
}

private fun ReaderBookmarkEntity.differsFrom(server: RemoteBookmark): Boolean =
    serverTitle().asText() != server.title.asText() || note.asText() != server.note.asText()

private fun String?.asText(): String? = this?.trim()?.takeIf(String::isNotEmpty)

// A bookmark new to this device: no place in its copy of the book yet (the reader finds it there).
private fun RemoteBookmark.toNewRow(book: AnnotatedBook, id: String, nowEpochMillis: Long): ReaderBookmarkEntity {
    val updatedAt = updatedAtEpochMillis ?: createdAtEpochMillis ?: nowEpochMillis
    return ReaderBookmarkEntity(
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
        remoteCfi = cfi,
        title = title.asText(),
        note = note.asText(),
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
    suspend fun syncBook(book: AnnotatedBook, remote: List<RemoteBookmark>, server: BookmarkServer): BookmarkSync {
        val plan = planBookmarkSync(book, store.rowsOf(book.itemId), remote, now(), newId)
        if (plan.writes.isNotEmpty() || plan.purges.isNotEmpty()) store.apply(plan.writes, plan.purges)
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
