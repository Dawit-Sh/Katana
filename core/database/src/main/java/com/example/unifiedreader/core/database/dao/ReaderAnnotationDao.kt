package com.example.unifiedreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
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

    /** Where this book's highlights and bookmarks sit, with the chapter name each was saved with. */
    @Query("SELECT id, href, progression, chapterTitle FROM reader_highlights WHERE itemId = :itemId AND deleted = 0")
    abstract fun highlightChapterPlaces(itemId: String): List<MarkChapterPlace>

    @Query("SELECT id, href, progression, chapterTitle FROM reader_bookmarks WHERE itemId = :itemId AND deleted = 0")
    abstract fun bookmarkChapterPlaces(itemId: String): List<MarkChapterPlace>

    /** Only the chapter name: not a change the reader made, so it isn't marked for sync. */
    @Query("UPDATE reader_highlights SET chapterTitle = :chapterTitle WHERE id = :id")
    abstract fun setHighlightChapter(id: String, chapterTitle: String?)

    @Query("UPDATE reader_bookmarks SET chapterTitle = :chapterTitle WHERE id = :id")
    abstract fun setBookmarkChapter(id: String, chapterTitle: String?)

    @Query("UPDATE reader_highlights SET itemId = :toItemId WHERE itemId = :fromItemId")
    abstract fun moveHighlights(fromItemId: String, toItemId: String): Int

    @Query("UPDATE saved_words SET itemId = :toItemId WHERE itemId = :fromItemId")
    abstract fun moveWords(fromItemId: String, toItemId: String): Int

    @Query("UPDATE reader_bookmarks SET itemId = :toItemId WHERE itemId = :fromItemId")
    abstract fun moveBookmarks(fromItemId: String, toItemId: String): Int

    /**
     * Gives every highlight, word and bookmark of [fromItemId] to [toItemId] (a book from a file that came back
     * under another id); how many moved.
     */
    @Transaction
    open fun moveMarks(fromItemId: String, toItemId: String): Int =
        moveHighlights(fromItemId, toItemId) + moveWords(fromItemId, toItemId) + moveBookmarks(fromItemId, toItemId)
}

/** A highlight's or bookmark's place and the chapter name saved with it. */
data class MarkChapterPlace(
    val id: String,
    val href: String,
    val progression: Double?,
    val chapterTitle: String?,
)

