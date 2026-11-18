package com.example.unifiedreader.core.database.entity

import androidx.room.ColumnInfo
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
 * Reviewing it as a flashcard keeps [reviewLevel] (how often it was known in a row), when it is
 * next due ([reviewDueAtEpochMillis], null for a word never reviewed) and when it was last seen.
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
    @ColumnInfo(defaultValue = "0") val reviewLevel: Int = 0,
    val reviewDueAtEpochMillis: Long? = null,
    val reviewedAtEpochMillis: Long? = null,
)

/**
 * A page the reader bookmarked in a book ([itemId]). [locatorJson] is the Readium locator of the
 * page's start, which finds it again at any text size; [page] of [pageCount] and [progression]
 * (through the book, 0..1) say where it was when it was made, and [text] keeps the page's first
 * words for lists. Like a highlight, a removed bookmark stays as [deleted] until a sync has told a
 * server ([syncState], [remoteId]).
 */
@Entity(
    tableName = "reader_bookmarks",
    indices = [Index(value = ["itemId"])],
)
data class ReaderBookmarkEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val bookTitle: String,
    val bookAuthor: String?,
    val href: String,
    val locatorJson: String,
    val chapterTitle: String?,
    val progression: Double?,
    val page: Int?,
    val pageCount: Int?,
    val text: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deleted: Boolean,
    val syncState: String,
    val remoteId: String?,
)
