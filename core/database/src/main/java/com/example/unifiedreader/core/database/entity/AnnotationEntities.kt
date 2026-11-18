package com.example.unifiedreader.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A highlight the reader made on a book's text, kept on this device by book ([itemId], the
 * catalog's id). [locatorJson] is the Readium locator of the marked words (their chapter, the
 * words and the text around them), which finds them again at any text size; [text] repeats the
 * words for lists and exports. [style] and [color] are `feature:annotations`' names. A removed
 * highlight stays as [deleted] until a server has been told, so a sync can remove it there too
 * ([syncState], [remoteId], [remoteCfi]). [bookTitle] and [bookAuthor] are copied at the time, so
 * an export still names the book once it has left the catalog.
 */
@Entity(
    tableName = "reader_highlights",
    indices = [Index(value = ["itemId"])],
)
data class ReaderHighlightEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val bookTitle: String,
    val bookAuthor: String?,
    val href: String,
    val locatorJson: String,
    val text: String,
    val chapterTitle: String?,
    val progression: Double?,
    val style: String,
    val color: String,
    val note: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deleted: Boolean,
    val syncState: String,
    val remoteId: String?,
    val remoteCfi: String?,
)

/**
 * A word the reader saved from a book ("Add to dictionary") to look up later: the word, the
 * sentence it came from, and where (chapter, [locatorJson], [progression]). Only on this device.
 */
@Entity(
    tableName = "saved_words",
    indices = [Index(value = ["itemId"])],
)
data class SavedWordEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val bookTitle: String,
    val bookAuthor: String?,
    val word: String,
    val sentence: String?,
    val chapterTitle: String?,
    val locatorJson: String?,
    val progression: Double?,
    val note: String?,
    val createdAtEpochMillis: Long,
)
