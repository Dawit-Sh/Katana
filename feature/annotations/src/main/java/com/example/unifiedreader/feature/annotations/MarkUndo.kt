package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.dao.ReaderAnnotationDao
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_ON_DEVICE
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppToastState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Undo for a removed highlight or bookmark: the toast that says it was removed has Undo, which
 * brings it back as it was (the same id, place, words, name, note, style and colour).
 *
 * A removal stays on this device as a removed row until a sync has told the server, so Undo puts
 * that same row back. It comes back as a change waiting for the server, never as "in step": if
 * the server was told in the meantime, the next sync makes it there again (it no longer has it),
 * and if not, the sync finds it the same on both sides and only marks it in step again.
 */

/** Taking a highlight away: for good once a sync has told its server ([deleteHighlight]), or so Undo can bring it back. */
interface HighlightRemovalStore {
    suspend fun deleteHighlight(id: String)

    /**
     * Removes a highlight like [deleteHighlight] and keeps it whole, so [restoreHighlight] can bring it
     * back exactly; null when there was no such highlight. A store that can't restore just removes it.
     */
    suspend fun removeHighlight(id: String): RemovedHighlight? {
        deleteHighlight(id)
        return null
    }

    /** Brings back a highlight [removeHighlight] took away: the same id, words, place, style, colour and note. */
    suspend fun restoreHighlight(removed: RemovedHighlight) = Unit
}

internal class RoomHighlightRemovalStore(
    private val dao: ReaderAnnotationDao,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long,
) : HighlightRemovalStore {
    override suspend fun deleteHighlight(id: String) = withContext(ioDispatcher) {
        dao.markHighlightDeleted(id, syncState = HIGHLIGHT_WAITING, atEpochMillis = now())
    }

    override suspend fun removeHighlight(id: String): RemovedHighlight? = withContext(ioDispatcher) {
        dao.getHighlight(id)?.takeUnless(ReaderHighlightEntity::deleted)?.let { stored ->
            dao.markHighlightDeleted(id, syncState = HIGHLIGHT_WAITING, atEpochMillis = now())
            RemovedHighlight(stored)
        }
    }

    override suspend fun restoreHighlight(removed: RemovedHighlight) = withContext(ioDispatcher) {
        restoredHighlightRow(removed.row, current = dao.getHighlight(removed.id), nowEpochMillis = now())
            ?.let(dao::upsertHighlight)
        Unit
    }
}

/** A bookmark taken away by [ReaderBookmarkStore.removeBookmark], kept whole for Undo. */
class RemovedBookmark internal constructor(internal val row: ReaderBookmarkEntity) {
    val id: String get() = row.id
}

/** A highlight taken away by [HighlightRemovalStore.removeHighlight], kept whole for Undo. */
class RemovedHighlight internal constructor(internal val row: ReaderHighlightEntity) {
    val id: String get() = row.id
}

/**
 * Removes the bookmarks [ids] and says [text] with Undo, which brings every one back exactly.
 * Suspends until they are removed; Undo runs in [scope].
 */
suspend fun ReaderBookmarkStore.removeBookmarksWithUndo(
    ids: List<String>,
    toasts: AppToastState?,
    scope: CoroutineScope,
    text: String = BookmarksCopy.BookmarkRemoved,
) {
    val toast = removeBookmarksForUndo(ids, scope, text)
    toasts?.show(toast)
}

/** Removes the bookmarks [ids]; the toast that says [text], its Undo bringing them back in [scope]. */
internal suspend fun ReaderBookmarkStore.removeBookmarksForUndo(
    ids: List<String>,
    scope: CoroutineScope,
    text: String,
): AppToastMessage {
    val removed = ids.mapNotNull { id -> removeBookmark(id) }
    return undoToast(text, canUndo = removed.isNotEmpty()) {
        scope.launch { removed.forEach { bookmark -> restoreBookmark(bookmark) } }
    }
}

/**
 * Removes the highlight [id] and says [text] with Undo, which brings it back exactly (its words,
 * place, style, colour and note). Suspends until it is removed; Undo runs in [scope].
 */
suspend fun HighlightRemovalStore.removeHighlightWithUndo(
    id: String,
    toasts: AppToastState?,
    scope: CoroutineScope,
    text: String = BookmarksCopy.HighlightRemoved,
) {
    val toast = removeHighlightForUndo(id, scope, text)
    toasts?.show(toast)
}

/** Removes the highlight [id]; the toast that says [text], its Undo bringing it back in [scope]. */
internal suspend fun HighlightRemovalStore.removeHighlightForUndo(
    id: String,
    scope: CoroutineScope,
    text: String,
): AppToastMessage {
    val removed = removeHighlight(id)
    return undoToast(text, canUndo = removed != null) {
        removed?.let { highlight -> scope.launch { restoreHighlight(highlight) } }
    }
}

private fun undoToast(text: String, canUndo: Boolean, undo: () -> Unit): AppToastMessage = AppToastMessage(
    text = text,
    icon = AppToastIcon.Removed,
    actionLabel = BookmarksCopy.Undo.takeIf { canUndo },
    onAction = undo.takeIf { canUndo },
)

/**
 * The row Undo writes for a bookmark removed as [removed], now stored as [current] (null once a sync
 * cleared it); null when it is already back (a sync took the server's copy meanwhile).
 */
internal fun restoredBookmarkRow(
    removed: ReaderBookmarkEntity,
    current: ReaderBookmarkEntity?,
    nowEpochMillis: Long,
): ReaderBookmarkEntity? {
    if (current != null && !current.deleted) return null
    val remoteId = current?.remoteId
    return removed.copy(
        deleted = false,
        remoteId = remoteId,
        remoteCfi = current?.remoteCfi ?: removed.remoteCfi,
        syncState = restoredSyncState(removed.syncState, remoteId),
        updatedAtEpochMillis = restoredAt(nowEpochMillis, removed.updatedAtEpochMillis, current?.updatedAtEpochMillis),
    )
}

/** As [restoredBookmarkRow], for a highlight. */
internal fun restoredHighlightRow(
    removed: ReaderHighlightEntity,
    current: ReaderHighlightEntity?,
    nowEpochMillis: Long,
): ReaderHighlightEntity? {
    if (current != null && !current.deleted) return null
    val remoteId = current?.remoteId
    return removed.copy(
        deleted = false,
        remoteId = remoteId,
        remoteCfi = current?.remoteCfi ?: removed.remoteCfi,
        syncState = restoredSyncState(removed.syncState, remoteId),
        updatedAtEpochMillis = restoredAt(nowEpochMillis, removed.updatedAtEpochMillis, current?.updatedAtEpochMillis),
    )
}

// One kept on this device (its place can't be told to the server) stays so; any other waits for a sync.
private fun restoredSyncState(removedState: String, remoteId: String?): String =
    if (removedState == HIGHLIGHT_ON_DEVICE && remoteId == null) HIGHLIGHT_ON_DEVICE else HIGHLIGHT_WAITING

// Later than the removal, so a sync that read the removed row writes nothing over the restored one.
private fun restoredAt(nowEpochMillis: Long, removedAt: Long, currentAt: Long?): Long =
    maxOf(nowEpochMillis, removedAt + 1, (currentAt ?: removedAt) + 1)
