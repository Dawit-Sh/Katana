package com.example.unifiedreader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_ON_DEVICE
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
import kotlinx.coroutines.flow.Flow

/** Where the reader works out a highlight's place: the server's way (a CFI), or in this device's copy of the book. */
@Dao
interface ReaderHighlightPlacementDao {
    /**
     * A book's highlights the reader has to place: new ones whose place on the server isn't worked out
     * yet, and ones from the server not yet found in this device's copy of the book.
     */
    @Query(
        "SELECT * FROM reader_highlights WHERE itemId = :itemId AND deleted = 0 AND (" +
            "(remoteCfi IS NULL AND remoteId IS NULL AND locatorJson != '' AND syncState = '$HIGHLIGHT_WAITING') " +
            "OR (remoteCfi IS NOT NULL AND locatorJson = ''))",
    )
    fun observeHighlightsToPlace(itemId: String): Flow<List<ReaderHighlightEntity>>

    @Query("UPDATE reader_highlights SET remoteCfi = :cfi WHERE id = :id AND remoteCfi IS NULL")
    fun setHighlightCfi(id: String, cfi: String)

    /** A new highlight whose place can't be put the server's way stays on this device. */
    @Query(
        "UPDATE reader_highlights SET syncState = '$HIGHLIGHT_ON_DEVICE' " +
            "WHERE id = :id AND syncState = '$HIGHLIGHT_WAITING' AND remoteId IS NULL AND remoteCfi IS NULL",
    )
    fun keepHighlightOnDevice(id: String)

    /** Where a highlight from the server is in this device's copy of the book. */
    @Query(
        "UPDATE reader_highlights SET href = :href, locatorJson = :locatorJson, progression = :progression, " +
            "chapterTitle = COALESCE(chapterTitle, :chapterTitle) WHERE id = :id AND locatorJson = ''",
    )
    fun placeHighlight(id: String, href: String, locatorJson: String, progression: Double?, chapterTitle: String?)
}
