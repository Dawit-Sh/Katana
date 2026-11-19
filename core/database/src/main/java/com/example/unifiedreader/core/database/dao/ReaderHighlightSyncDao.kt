package com.example.unifiedreader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
import com.example.unifiedreader.core.database.entity.WaitingHighlightKey
import kotlinx.coroutines.flow.Flow

/**
 * The highlights as their sync with a server reads and writes them: every row of a book, the
 * changes waiting, and the server's answers. A sync's own writes run in one transaction with
 * [ReaderAnnotationDao]'s. Callers run the plain functions off the main thread.
 */
@Dao
interface ReaderHighlightSyncDao {
    /** Every highlight of a book as stored, removed ones still waiting for a sync included. */
    @Query("SELECT * FROM reader_highlights WHERE itemId = :itemId")
    fun getHighlightRows(itemId: String): List<ReaderHighlightEntity>

    /**
     * The highlight changes a sync can send: removed, changed after reaching the server, or new with
     * their place on the server worked out. A new one without that place waits for the reader to work it out.
     */
    @Query(
        "SELECT itemId, id, updatedAtEpochMillis FROM reader_highlights WHERE syncState = '$HIGHLIGHT_WAITING' " +
            "AND (deleted = 1 OR remoteId IS NOT NULL OR remoteCfi IS NOT NULL) ORDER BY itemId, id",
    )
    fun observeWaitingHighlights(): Flow<List<WaitingHighlightKey>>

    /** The books with highlights on a server, or waiting to go to one. */
    @Query(
        "SELECT DISTINCT itemId FROM reader_highlights WHERE remoteId IS NOT NULL OR " +
            "(syncState = '$HIGHLIGHT_WAITING' AND (deleted = 1 OR remoteCfi IS NOT NULL))",
    )
    fun getItemIdsWithServerHighlights(): List<String>

    @Query("SELECT COUNT(*) FROM reader_highlights WHERE itemId = :itemId AND remoteId = :remoteId")
    fun countHighlightsWithRemoteId(itemId: String, remoteId: String): Int

    @Query("DELETE FROM reader_highlights WHERE id = :id AND updatedAtEpochMillis = :expectedUpdatedAt")
    fun purgeHighlight(id: String, expectedUpdatedAt: Long)

    @Query("DELETE FROM reader_highlights WHERE id = :id AND deleted = 1")
    fun purgeRemovedHighlight(id: String)

    /** The server made it: it is in step unless it changed again meanwhile, which then goes next. */
    @Query(
        "UPDATE reader_highlights SET remoteId = :remoteId, syncState = CASE WHEN updatedAtEpochMillis = :expectedUpdatedAt " +
            "THEN '$HIGHLIGHT_IN_STEP' ELSE syncState END WHERE id = :id",
    )
    fun markHighlightCreated(id: String, remoteId: String, expectedUpdatedAt: Long)

    /** A highlight not removed and unchanged since [expectedUpdatedAt] (sent, or refused) takes [syncState]. */
    @Query(
        "UPDATE reader_highlights SET syncState = :syncState " +
            "WHERE id = :id AND updatedAtEpochMillis = :expectedUpdatedAt AND deleted = 0",
    )
    fun setSyncStateIfUnchanged(id: String, syncState: String, expectedUpdatedAt: Long)

    /** The server lost it: the change on this device makes it again there. */
    @Query("UPDATE reader_highlights SET remoteId = NULL WHERE id = :id")
    fun forgetHighlightRemoteId(id: String)
}
