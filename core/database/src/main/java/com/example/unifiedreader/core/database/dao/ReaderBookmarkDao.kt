package com.example.unifiedreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_ON_DEVICE
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import kotlinx.coroutines.flow.Flow

/**
 * The pages the reader bookmarked, their names and notes, and the places the reader works out for
 * their server sync ([ReaderBookmarkSyncDao] has the rest of it). Callers run the plain functions off
 * the main thread.
 */
@Dao
abstract class ReaderBookmarkDao {
    /** One book's bookmarks in reading order (those without a position last, oldest first). */
    @Query(
        "SELECT * FROM reader_bookmarks WHERE itemId = :itemId AND deleted = 0 " +
            "ORDER BY progression IS NULL, progression, createdAtEpochMillis",
    )
    abstract fun observeBookmarks(itemId: String): Flow<List<ReaderBookmarkEntity>>

    /** Every book's bookmarks, the newest first. */
    @Query("SELECT * FROM reader_bookmarks WHERE deleted = 0 ORDER BY createdAtEpochMillis DESC")
    abstract fun observeAllBookmarks(): Flow<List<ReaderBookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertBookmark(bookmark: ReaderBookmarkEntity)

    /** Removes a bookmark from every list; the row stays, marked, until a sync has removed it remotely. */
    @Query(
        "UPDATE reader_bookmarks SET deleted = 1, syncState = :syncState, updatedAtEpochMillis = :atEpochMillis " +
            "WHERE id = :id",
    )
    abstract fun markBookmarkDeleted(id: String, syncState: String, atEpochMillis: Long)

    /** The reader's name and note for a bookmark; it goes to the server next. */
    @Query(
        "UPDATE reader_bookmarks SET title = :title, note = :note, syncState = '$HIGHLIGHT_WAITING', " +
            "updatedAtEpochMillis = :atEpochMillis WHERE id = :id AND deleted = 0",
    )
    abstract fun setBookmarkNaming(id: String, title: String?, note: String?, atEpochMillis: Long)

    /**
     * A book's bookmarks the reader has to place: new ones whose place on the server isn't worked out
     * yet, and ones from the server not yet found in this device's copy of the book.
     */
    @Query(
        "SELECT * FROM reader_bookmarks WHERE itemId = :itemId AND deleted = 0 AND (" +
            "(remoteCfi IS NULL AND remoteId IS NULL AND locatorJson != '' AND syncState = '$HIGHLIGHT_WAITING') " +
            "OR (remoteCfi IS NOT NULL AND locatorJson = ''))",
    )
    abstract fun observeBookmarksToPlace(itemId: String): Flow<List<ReaderBookmarkEntity>>

    @Query("UPDATE reader_bookmarks SET remoteCfi = :cfi WHERE id = :id AND remoteCfi IS NULL")
    abstract fun setBookmarkCfi(id: String, cfi: String)

    /** A new bookmark whose place can't be put the server's way stays on this device. */
    @Query(
        "UPDATE reader_bookmarks SET syncState = '$HIGHLIGHT_ON_DEVICE' " +
            "WHERE id = :id AND syncState = '$HIGHLIGHT_WAITING' AND remoteId IS NULL AND remoteCfi IS NULL",
    )
    abstract fun keepBookmarkOnDevice(id: String)

    /** Where a bookmark from the server is in this device's copy of the book, and the first words there. */
    @Query(
        "UPDATE reader_bookmarks SET href = :href, locatorJson = :locatorJson, progression = :progression, " +
            "page = :page, pageCount = :pageCount, text = COALESCE(text, :text), " +
            "chapterTitle = COALESCE(chapterTitle, :chapterTitle) WHERE id = :id AND locatorJson = ''",
    )
    @Suppress("LongParameterList")
    abstract fun placeBookmark(
        id: String,
        href: String,
        locatorJson: String,
        progression: Double?,
        page: Int?,
        pageCount: Int?,
        text: String?,
        chapterTitle: String?,
    )
}
