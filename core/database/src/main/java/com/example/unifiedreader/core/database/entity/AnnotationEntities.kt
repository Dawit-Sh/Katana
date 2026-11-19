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
 * Where a highlight stands with its book's server ([ReaderHighlightEntity.syncState]).
 * [HIGHLIGHT_WAITING]: made, changed or removed on this device and not yet on the server (a book
 * with no server keeps it for good). [HIGHLIGHT_IN_STEP]: the same as the server's copy.
 * [HIGHLIGHT_ON_DEVICE]: its place can't be told to the server, so it stays on this device.
 */
const val HIGHLIGHT_WAITING = "DIRTY"
const val HIGHLIGHT_IN_STEP = "CLEAN"
const val HIGHLIGHT_ON_DEVICE = "LOCAL"

/** A highlight waiting to go to its server: which book, and the change (the highlight and when). */
data class WaitingHighlightKey(
    val itemId: String,
    val id: String,
    val updatedAtEpochMillis: Long,
)

/**
 * What a highlight sync writes over a stored highlight: [row], if the stored one still has
 * [expectedUpdatedAt] (unchanged since the sync read it); null for a highlight new to this device.
 */
data class HighlightRowWrite(
    val row: ReaderHighlightEntity,
    val expectedUpdatedAt: Long?,
)

/** A highlight a sync removes for good, if it still has [expectedUpdatedAt]. */
data class HighlightRowPurge(
    val id: String,
    val expectedUpdatedAt: Long,
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
 * page's start, which finds it again at any text size (a page-based book's is `{"page": n}`); [page]
 * of [pageCount] and [progression] (through the book, 0..1) say where it was when it was made, and
 * [text] keeps the page's first words for lists and search. Like a highlight, a removed bookmark
 * stays as [deleted] until a sync has told a server ([syncState], [remoteId]); [remoteCfi] is an
 * EPUB bookmark's place the server's way (a CFI), worked out by the reader, or the place a bookmark
 * made on the server came with, until the reader finds it in this device's copy (an empty
 * [locatorJson] until then). [title] is the reader's own name for it and [note] what they wrote
 * about it, both shared with the server. Database 40 added these three.
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
    val remoteCfi: String? = null,
    val title: String? = null,
    val note: String? = null,
)

/** A bookmark waiting to go to its server: which book, and the change (the bookmark and when). */
data class WaitingBookmarkKey(
    val itemId: String,
    val id: String,
    val updatedAtEpochMillis: Long,
)
