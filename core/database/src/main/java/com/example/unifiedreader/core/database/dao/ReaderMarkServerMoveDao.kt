package com.example.unifiedreader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING

/**
 * Another server takes over a book's highlights and bookmarks (a different Katana Desk paired): their ids were
 * the old server's and mean nothing to the new one, which would read them as removed there. Each goes to the new
 * server as new (its place there, remoteCfi, stays: it's the book's, not the server's), and removals meant for
 * the old server are dropped.
 */
@Dao
abstract class ReaderMarkServerMoveDao {
    @Transaction
    open fun moveToNewServer(itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        dropHighlightRemovals(itemIds)
        sendHighlightsAsNew(itemIds)
        dropBookmarkRemovals(itemIds)
        sendBookmarksAsNew(itemIds)
    }

    @Query("DELETE FROM reader_highlights WHERE itemId IN (:itemIds) AND deleted = 1 AND remoteId IS NOT NULL")
    protected abstract fun dropHighlightRemovals(itemIds: List<String>)

    @Query(
        "UPDATE reader_highlights SET remoteId = NULL, syncState = '$HIGHLIGHT_WAITING' " +
            "WHERE itemId IN (:itemIds) AND deleted = 0 AND remoteId IS NOT NULL",
    )
    protected abstract fun sendHighlightsAsNew(itemIds: List<String>)

    @Query("DELETE FROM reader_bookmarks WHERE itemId IN (:itemIds) AND deleted = 1 AND remoteId IS NOT NULL")
    protected abstract fun dropBookmarkRemovals(itemIds: List<String>)

    @Query(
        "UPDATE reader_bookmarks SET remoteId = NULL, syncState = '$HIGHLIGHT_WAITING' " +
            "WHERE itemId IN (:itemIds) AND deleted = 0 AND remoteId IS NOT NULL",
    )
    protected abstract fun sendBookmarksAsNew(itemIds: List<String>)
}
