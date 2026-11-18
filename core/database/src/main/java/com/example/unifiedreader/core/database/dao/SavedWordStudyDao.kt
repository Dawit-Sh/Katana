package com.example.unifiedreader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.example.unifiedreader.core.database.entity.SavedWordEntity

/** Studying a saved word: its note in the reader's own words and its flashcard review. Off the main thread. */
@Dao
abstract class SavedWordStudyDao {
    @Query("SELECT * FROM saved_words WHERE id = :id")
    abstract fun getWord(id: String): SavedWordEntity?

    @Query("UPDATE saved_words SET note = :note WHERE id = :id")
    abstract fun setWordNote(id: String, note: String?)

    /** A flashcard answer: the word's new level, when it is next due, and when it was seen. */
    @Query(
        "UPDATE saved_words SET reviewLevel = :level, reviewDueAtEpochMillis = :dueAtEpochMillis, " +
            "reviewedAtEpochMillis = :atEpochMillis WHERE id = :id",
    )
    abstract fun setWordReview(id: String, level: Int, dueAtEpochMillis: Long, atEpochMillis: Long)
}
