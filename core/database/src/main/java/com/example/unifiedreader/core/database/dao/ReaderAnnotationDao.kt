package com.example.unifiedreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_ON_DEVICE
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.HighlightRowPurge
import com.example.unifiedreader.core.database.entity.HighlightRowWrite
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
import com.example.unifiedreader.core.database.entity.WaitingHighlightKey
import com.example.unifiedreader.core.database.entity.SavedWordEntity
import kotlinx.coroutines.flow.Flow

/** The reader's highlights and saved words. Callers run the plain functions off the main thread. */
@Dao
abstract class ReaderAnnotationDao {
    /** One book's highlights in reading order (those without a position last, oldest first). */
    @Query(
        "SELECT * FROM reader_highlights WHERE itemId = :itemId AND deleted = 0 " +
            "ORDER BY progression IS NULL, progression, createdAtEpochMillis",
    )
    abstract fun observeHighlights(itemId: String): Flow<List<ReaderHighlightEntity>>

    /** Every book's highlights, the latest change first. */
    @Query("SELECT * FROM reader_highlights WHERE deleted = 0 ORDER BY updatedAtEpochMillis DESC")
    abstract fun observeAllHighlights(): Flow<List<ReaderHighlightEntity>>

    @Query("SELECT * FROM reader_highlights WHERE id = :id")
    abstract fun getHighlight(id: String): ReaderHighlightEntity?

    /** The highlight made or changed last, in any book; its look is the next one's. */
    @Query("SELECT * FROM reader_highlights WHERE deleted = 0 ORDER BY updatedAtEpochMillis DESC LIMIT 1")
    abstract fun getLatestHighlight(): ReaderHighlightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertHighlight(highlight: ReaderHighlightEntity)

    /** Removes a highlight from every list; the row stays, marked, until a sync has removed it remotely. */
    @Query(
        "UPDATE reader_highlights SET deleted = 1, syncState = :syncState, updatedAtEpochMillis = :atEpochMillis " +
            "WHERE id = :id",
    )
    abstract fun markHighlightDeleted(id: String, syncState: String, atEpochMillis: Long)

    /** Every highlight of a book as stored, removed ones still waiting for a sync included. */
    @Query("SELECT * FROM reader_highlights WHERE itemId = :itemId")
    abstract fun getHighlightRows(itemId: String): List<ReaderHighlightEntity>

    /**
     * The highlight changes a sync can send: removed, changed after reaching the server, or new with
     * their place on the server worked out. A new one without that place waits for the reader to work it out.
     */
    @Query(
        "SELECT itemId, id, updatedAtEpochMillis FROM reader_highlights WHERE syncState = '$HIGHLIGHT_WAITING' " +
            "AND (deleted = 1 OR remoteId IS NOT NULL OR remoteCfi IS NOT NULL) ORDER BY itemId, id",
    )
    abstract fun observeWaitingHighlights(): Flow<List<WaitingHighlightKey>>

    /**
     * A book's highlights the reader has to place: new ones whose place on the server isn't worked out
     * yet, and ones from the server not yet found in this device's copy of the book.
     */
    @Query(
        "SELECT * FROM reader_highlights WHERE itemId = :itemId AND deleted = 0 AND (" +
            "(remoteCfi IS NULL AND remoteId IS NULL AND locatorJson != '' AND syncState = '$HIGHLIGHT_WAITING') " +
            "OR (remoteCfi IS NOT NULL AND locatorJson = ''))",
    )
    abstract fun observeHighlightsToPlace(itemId: String): Flow<List<ReaderHighlightEntity>>

    @Query("UPDATE reader_highlights SET remoteCfi = :cfi WHERE id = :id AND remoteCfi IS NULL")
    abstract fun setHighlightCfi(id: String, cfi: String)

    /** A new highlight whose place can't be put the server's way stays on this device. */
    @Query(
        "UPDATE reader_highlights SET syncState = '$HIGHLIGHT_ON_DEVICE' " +
            "WHERE id = :id AND syncState = '$HIGHLIGHT_WAITING' AND remoteId IS NULL AND remoteCfi IS NULL",
    )
    abstract fun keepHighlightOnDevice(id: String)

    /** Where a highlight from the server is in this device's copy of the book. */
    @Query(
        "UPDATE reader_highlights SET href = :href, locatorJson = :locatorJson, progression = :progression, " +
            "chapterTitle = COALESCE(chapterTitle, :chapterTitle) WHERE id = :id AND locatorJson = ''",
    )
    abstract fun placeHighlight(id: String, href: String, locatorJson: String, progression: Double?, chapterTitle: String?)

    /** The books with highlights on a server, or waiting to go to one. */
    @Query(
        "SELECT DISTINCT itemId FROM reader_highlights WHERE remoteId IS NOT NULL OR " +
            "(syncState = '$HIGHLIGHT_WAITING' AND (deleted = 1 OR remoteCfi IS NOT NULL))",
    )
    abstract fun getItemIdsWithServerHighlights(): List<String>

    @Query("SELECT COUNT(*) FROM reader_highlights WHERE itemId = :itemId AND remoteId = :remoteId")
    abstract fun countHighlightsWithRemoteId(itemId: String, remoteId: String): Int

    @Query("DELETE FROM reader_highlights WHERE id = :id AND updatedAtEpochMillis = :expectedUpdatedAt")
    abstract fun purgeHighlight(id: String, expectedUpdatedAt: Long)

    @Query("DELETE FROM reader_highlights WHERE id = :id AND deleted = 1")
    abstract fun purgeRemovedHighlight(id: String)

    /** The server made it: it is in step unless it changed again meanwhile, which then goes next. */
    @Query(
        "UPDATE reader_highlights SET remoteId = :remoteId, syncState = CASE WHEN updatedAtEpochMillis = :expectedUpdatedAt " +
            "THEN '$HIGHLIGHT_IN_STEP' ELSE syncState END WHERE id = :id",
    )
    abstract fun markHighlightCreated(id: String, remoteId: String, expectedUpdatedAt: Long)

    @Query(
        "UPDATE reader_highlights SET syncState = '$HIGHLIGHT_IN_STEP' " +
            "WHERE id = :id AND updatedAtEpochMillis = :expectedUpdatedAt AND deleted = 0",
    )
    abstract fun markHighlightSent(id: String, expectedUpdatedAt: Long)

    /** The server lost it: the change on this device makes it again there. */
    @Query("UPDATE reader_highlights SET remoteId = NULL WHERE id = :id")
    abstract fun forgetHighlightRemoteId(id: String)

    @Query(
        "UPDATE reader_highlights SET syncState = '$HIGHLIGHT_ON_DEVICE' " +
            "WHERE id = :id AND updatedAtEpochMillis = :expectedUpdatedAt AND deleted = 0",
    )
    abstract fun markHighlightRefused(id: String, expectedUpdatedAt: Long)

    /**
     * Writes what a sync worked out, all at once. A write over a stored highlight happens only if it
     * hasn't changed since the sync read it ([HighlightRowWrite.expectedUpdatedAt]); a new one only if
     * the server's highlight isn't in the book already. The place this device worked out is kept.
     */
    @Transaction
    open fun applyHighlightSync(writes: List<HighlightRowWrite>, purges: List<HighlightRowPurge>) {
        writes.forEach { write ->
            val row = write.row
            val stored = getHighlight(row.id)
            val expected = write.expectedUpdatedAt
            if (expected == null) {
                val known = row.remoteId?.let { remoteId -> countHighlightsWithRemoteId(row.itemId, remoteId) > 0 } ?: false
                if (stored == null && !known) upsertHighlight(row)
                return@forEach
            }
            if (stored == null || stored.updatedAtEpochMillis != expected) return@forEach
            val keepsPlace = row.locatorJson.isEmpty() && stored.locatorJson.isNotEmpty()
            upsertHighlight(
                row.copy(
                    href = if (keepsPlace) stored.href else row.href,
                    locatorJson = if (keepsPlace) stored.locatorJson else row.locatorJson,
                    progression = if (keepsPlace) stored.progression else row.progression,
                    remoteCfi = row.remoteCfi ?: stored.remoteCfi,
                ),
            )
        }
        purges.forEach { purge -> purgeHighlight(purge.id, purge.expectedUpdatedAt) }
    }

    /** One book's saved words in reading order. */
    @Query(
        "SELECT * FROM saved_words WHERE itemId = :itemId " +
            "ORDER BY progression IS NULL, progression, createdAtEpochMillis",
    )
    abstract fun observeWords(itemId: String): Flow<List<SavedWordEntity>>

    /** Every saved word, the newest first. */
    @Query("SELECT * FROM saved_words ORDER BY createdAtEpochMillis DESC")
    abstract fun observeAllWords(): Flow<List<SavedWordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertWord(word: SavedWordEntity)

    @Query("DELETE FROM saved_words WHERE id = :id")
    abstract fun deleteWord(id: String)
}
