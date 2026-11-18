package com.example.unifiedreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import kotlinx.coroutines.flow.Flow

/** The pages the reader bookmarked. Callers run the plain functions off the main thread. */
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
}
