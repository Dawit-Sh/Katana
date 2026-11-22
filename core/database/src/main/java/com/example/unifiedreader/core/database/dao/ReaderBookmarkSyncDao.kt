package com.example.unifiedreader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import com.example.unifiedreader.core.database.entity.WaitingBookmarkKey
import kotlinx.coroutines.flow.Flow

/**
 * The bookmarks as their sync with a server reads and writes them: every row of a book, the changes
 * waiting, and the server's answers, as [ReaderHighlightSyncDao] does for highlights. Callers run the
 * plain functions off the main thread.
 */
@Dao
interface ReaderBookmarkSyncDao {
    @Query("SELECT * FROM reader_bookmarks WHERE id = :id")
    fun getBookmark(id: String): ReaderBookmarkEntity?

    /** Every bookmark of a book as stored, removed ones still waiting for a sync included. */
    @Query("SELECT * FROM reader_bookmarks WHERE itemId = :itemId")
    fun getBookmarkRows(itemId: String): List<ReaderBookmarkEntity>

    /**
     * The changes a sync can send: removed, changed after reaching the server, or new with their server place
     * known (worked out in an EPUB; a page bookmark's page is its place from the start).
     */
    @Query(
        "SELECT itemId, id, updatedAtEpochMillis FROM reader_bookmarks WHERE syncState = '$HIGHLIGHT_WAITING' " +
            "AND (deleted = 1 OR remoteId IS NOT NULL OR remoteCfi IS NOT NULL OR href LIKE 'page:%') ORDER BY itemId, id",
    )
    fun observeWaitingBookmarks(): Flow<List<WaitingBookmarkKey>>

    /** The books with bookmarks on a server, or waiting to go to one. */
    @Query(
        "SELECT DISTINCT itemId FROM reader_bookmarks WHERE remoteId IS NOT NULL OR " +
            "(syncState = '$HIGHLIGHT_WAITING' AND (deleted = 1 OR remoteCfi IS NOT NULL OR href LIKE 'page:%'))",
    )
    fun getItemIdsWithServerBookmarks(): List<String>

    @Query("SELECT COUNT(*) FROM reader_bookmarks WHERE itemId = :itemId AND remoteId = :remoteId")
    fun countBookmarksWithRemoteId(itemId: String, remoteId: String): Int

    @Query("DELETE FROM reader_bookmarks WHERE id = :id AND updatedAtEpochMillis = :expectedUpdatedAt")
    fun purgeBookmark(id: String, expectedUpdatedAt: Long)

    @Query("DELETE FROM reader_bookmarks WHERE id = :id AND deleted = 1")
    fun purgeRemovedBookmark(id: String)

    /** The server made it: it is in step unless it changed again meanwhile, which then goes next. */
    @Query(
        "UPDATE reader_bookmarks SET remoteId = :remoteId, syncState = CASE WHEN updatedAtEpochMillis = :expectedUpdatedAt " +
            "THEN '$HIGHLIGHT_IN_STEP' ELSE syncState END WHERE id = :id",
    )
    fun markBookmarkCreated(id: String, remoteId: String, expectedUpdatedAt: Long)

    /** A bookmark not removed and unchanged since [expectedUpdatedAt] (sent, or refused) takes [syncState]. */
    @Query(
        "UPDATE reader_bookmarks SET syncState = :syncState " +
            "WHERE id = :id AND updatedAtEpochMillis = :expectedUpdatedAt AND deleted = 0",
    )
    fun setBookmarkSyncStateIfUnchanged(id: String, syncState: String, expectedUpdatedAt: Long)

    /** The server lost it: the change on this device makes it again there. */
    @Query("UPDATE reader_bookmarks SET remoteId = NULL WHERE id = :id")
    fun forgetBookmarkRemoteId(id: String)
}
