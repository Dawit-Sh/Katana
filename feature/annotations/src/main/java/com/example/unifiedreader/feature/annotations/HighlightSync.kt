package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.core.database.dao.ReaderAnnotationDao
import com.example.unifiedreader.core.database.dao.ReaderHighlightSyncDao
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_ON_DEVICE
import com.example.unifiedreader.core.database.entity.HighlightRowPurge
import com.example.unifiedreader.core.database.entity.HighlightRowWrite
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
import com.example.unifiedreader.core.database.entity.WaitingHighlightKey
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** A book's server, as a highlight sync talks to it. A failure to reach it is thrown. */
interface HighlightServer {
    suspend fun create(upload: HighlightUpload.Create): HighlightServerAnswer

    suspend fun update(upload: HighlightUpload.Update): HighlightServerAnswer

    /** False when the server no longer had it, which is as good as removed. */
    suspend fun delete(upload: HighlightUpload.Delete): Boolean
}

sealed interface HighlightServerAnswer {
    data class Saved(val remoteId: String) : HighlightServerAnswer

    /** The highlight (or its book) is no longer on the server. */
    data object Missing : HighlightServerAnswer

    /** The book has a highlight at that place already, made since the sync read the server's list. */
    data object AlreadyThere : HighlightServerAnswer

    /** The server won't take it as it is; sending it again won't help. */
    data object Refused : HighlightServerAnswer
}

enum class HighlightBookSync {
    Done,

    /** See [HighlightSyncPlan.needsPlaces]: sync again with the book's own list. */
    NeedsPlaces,

    /** The server gained a highlight at a place this device was sending one: sync the book again. */
    ServerChangedMeanwhile,
}

/** The highlights as a sync reads and writes them. */
interface HighlightSyncStore {
    /** Every change waiting to go to a server, in every book; each new change is a new list. */
    fun observeWaiting(): Flow<List<WaitingHighlightKey>>

    suspend fun rowsOf(itemId: String): List<ReaderHighlightEntity>

    /** The books with highlights on a server, or waiting to go to one. */
    suspend fun booksOnServer(): Set<String>

    suspend fun apply(writes: List<HighlightRowWrite>, purges: List<HighlightRowPurge>)

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
 * Brings a book's highlights in step with its server ([planHighlightSync] says how), then sends
 * what waits, one change at a time; each answer is saved as it comes, so a sync cut short by the
 * network keeps what got through and the rest waits for the next one. One sync at a time: the caller
 * runs them one after another.
 */
class HighlightSyncEngine(
    private val store: HighlightSyncStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun syncBook(
        book: AnnotatedBook,
        remote: List<RemoteHighlight>,
        server: HighlightServer,
    ): HighlightBookSync {
        val rows = store.rowsOf(book.itemId)
        val plan = planHighlightSync(book, rows, remote, now(), newId)
        if (plan.needsPlaces) return HighlightBookSync.NeedsPlaces
        // Many gone from the server at once is held for the reader to answer, not removed here.
        val kept = rows.filterNot(ReaderHighlightEntity::deleted).mapTo(mutableSetOf(), ReaderHighlightEntity::id)
        val purges = holdingMassRemoval(book, HeldMarkKind.Highlight, kept, plan.purges) { HeldMark(it.id, it.expectedUpdatedAt) }
        if (plan.writes.isNotEmpty() || purges.isNotEmpty()) store.apply(plan.writes, purges)
        val serverChanged = plan.uploads.map { upload -> send(upload, server) }.any { alreadyThere -> alreadyThere }
        return if (serverChanged) HighlightBookSync.ServerChangedMeanwhile else HighlightBookSync.Done
    }

    // True when the server already had a highlight at a new one's place.
    private suspend fun send(upload: HighlightUpload, server: HighlightServer): Boolean {
        when (upload) {
            is HighlightUpload.Create -> when (val answer = server.create(upload)) {
                is HighlightServerAnswer.Saved -> store.created(upload.localId, upload.expectedUpdatedAt, answer.remoteId)
                HighlightServerAnswer.AlreadyThere -> return true
                HighlightServerAnswer.Refused -> store.refused(upload.localId, upload.expectedUpdatedAt)
                // The book left the server: its removal from the library ends this.
                HighlightServerAnswer.Missing -> Unit
            }
            is HighlightUpload.Update -> when (server.update(upload)) {
                HighlightServerAnswer.Missing -> store.lostOnServer(upload.localId)
                // A change the server won't take is dropped; the server's copy comes back next time.
                is HighlightServerAnswer.Saved,
                HighlightServerAnswer.Refused,
                HighlightServerAnswer.AlreadyThere -> store.sent(upload.localId, upload.expectedUpdatedAt)
            }
            is HighlightUpload.Delete -> {
                server.delete(upload)
                store.removedOnServer(upload.localId)
            }
        }
        return false
    }
}

class RoomHighlightSyncStore(
    private val database: UnifiedReaderDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HighlightSyncStore {
    private val dao = database.readerHighlightSyncDao()
    private val highlights = database.readerAnnotationDao()

    override fun observeWaiting(): Flow<List<WaitingHighlightKey>> =
        dao.observeWaitingHighlights().distinctUntilChanged().flowOn(ioDispatcher)

    override suspend fun rowsOf(itemId: String): List<ReaderHighlightEntity> = io { dao.getHighlightRows(itemId) }

    override suspend fun booksOnServer(): Set<String> = io { dao.getItemIdsWithServerHighlights().toSet() }

    /**
     * Writes what a sync worked out, all at once. A write over a stored highlight happens only if it
     * hasn't changed since the sync read it ([HighlightRowWrite.expectedUpdatedAt]); a new one only if
     * the server's highlight isn't in the book already. The place this device worked out is kept.
     */
    override suspend fun apply(writes: List<HighlightRowWrite>, purges: List<HighlightRowPurge>) = io {
        database.runInTransaction {
            writes.forEach { write -> write.applyTo(dao, highlights) }
            purges.forEach { purge -> dao.purgeHighlight(purge.id, purge.expectedUpdatedAt) }
        }
    }

    override suspend fun created(localId: String, expectedUpdatedAt: Long, remoteId: String) =
        io { dao.markHighlightCreated(localId, remoteId, expectedUpdatedAt) }

    override suspend fun sent(localId: String, expectedUpdatedAt: Long) =
        io { dao.setSyncStateIfUnchanged(localId, HIGHLIGHT_IN_STEP, expectedUpdatedAt) }

    override suspend fun removedOnServer(localId: String) = io { dao.purgeRemovedHighlight(localId) }

    override suspend fun lostOnServer(localId: String) = io { dao.forgetHighlightRemoteId(localId) }

    override suspend fun newServer(itemId: String) = io {
        dao.dropHighlightRemovalsForOldServer(itemId)
        dao.sendHighlightsToNewServer(itemId)
    }

    override suspend fun refused(localId: String, expectedUpdatedAt: Long) =
        io { dao.setSyncStateIfUnchanged(localId, HIGHLIGHT_ON_DEVICE, expectedUpdatedAt) }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }
}

private fun HighlightRowWrite.applyTo(dao: ReaderHighlightSyncDao, highlights: ReaderAnnotationDao) {
    val stored = highlights.getHighlight(row.id)
    val expected = expectedUpdatedAt
    when {
        expected == null -> {
            val known = row.remoteId?.let { remoteId -> dao.countHighlightsWithRemoteId(row.itemId, remoteId) > 0 } ?: false
            if (stored == null && !known) highlights.upsertHighlight(row)
        }
        stored != null && stored.updatedAtEpochMillis == expected -> highlights.upsertHighlight(row.keepingPlaceOf(stored))
    }
}

// The place the reader worked out (and the server place) stay when the sync's copy has none.
private fun ReaderHighlightEntity.keepingPlaceOf(stored: ReaderHighlightEntity): ReaderHighlightEntity {
    val keepsPlace = locatorJson.isEmpty() && stored.locatorJson.isNotEmpty()
    return copy(
        href = if (keepsPlace) stored.href else href,
        locatorJson = if (keepsPlace) stored.locatorJson else locatorJson,
        progression = if (keepsPlace) stored.progression else progression,
        remoteCfi = remoteCfi ?: stored.remoteCfi,
    )
}
