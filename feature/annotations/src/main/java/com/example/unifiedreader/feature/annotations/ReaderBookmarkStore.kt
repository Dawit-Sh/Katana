package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.dao.ReaderBookmarkDao
import com.example.unifiedreader.core.database.dao.SavedWordStudyDao
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The pages the reader bookmarked, on this device. */
interface ReaderBookmarkStore {
    /** One book's bookmarks in reading order. */
    fun bookmarks(itemId: String): Flow<List<ReaderBookmark>>

    /** Every book's bookmarks, the newest first. */
    val allBookmarks: Flow<List<ReaderBookmark>>

    suspend fun addBookmark(
        book: AnnotatedBook,
        place: AnnotationPlace,
        page: Int?,
        pageCount: Int?,
        text: String?,
    ): ReaderBookmark

    suspend fun deleteBookmark(id: String)

    /** The reader's name and note for a bookmark; a blank one removes it. */
    suspend fun nameBookmark(id: String, title: String, note: String)
}

/** Studying the saved words: a word's own note, and its flashcard answers. */
interface SavedWordStudyStore {
    /** A word's note in the reader's own words; a blank note removes it. */
    suspend fun setWordNote(id: String, note: String)

    /** A flashcard answer for a word: [known] moves it on, otherwise it starts again. */
    suspend fun reviewWord(id: String, known: Boolean): SavedWord?
}

internal class RoomReaderBookmarkStore(
    private val dao: ReaderBookmarkDao,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long,
    private val newId: () -> String,
) : ReaderBookmarkStore {
    override fun bookmarks(itemId: String): Flow<List<ReaderBookmark>> =
        dao.observeBookmarks(itemId).map { rows -> rows.map(ReaderBookmarkEntity::toModel) }.flowOn(ioDispatcher)

    override val allBookmarks: Flow<List<ReaderBookmark>> =
        dao.observeAllBookmarks().map { rows -> rows.map(ReaderBookmarkEntity::toModel) }.flowOn(ioDispatcher)

    override suspend fun addBookmark(
        book: AnnotatedBook,
        place: AnnotationPlace,
        page: Int?,
        pageCount: Int?,
        text: String?,
    ): ReaderBookmark = withContext(ioDispatcher) {
        val at = now()
        val row = ReaderBookmarkEntity(
            id = newId(),
            itemId = book.itemId,
            bookTitle = book.title,
            bookAuthor = book.author,
            href = place.href,
            locatorJson = place.locatorJson,
            chapterTitle = place.chapterTitle,
            progression = place.progression,
            page = page?.takeIf { it > 0 },
            pageCount = pageCount?.takeIf { it > 0 },
            text = text?.oneLine()?.takeIf(String::isNotEmpty)?.take(MAX_BOOKMARK_TEXT),
            createdAtEpochMillis = at,
            updatedAtEpochMillis = at,
            deleted = false,
            syncState = SYNC_DIRTY,
            remoteId = null,
        )
        dao.upsertBookmark(row)
        row.toModel()
    }

    override suspend fun deleteBookmark(id: String) = withContext(ioDispatcher) {
        dao.markBookmarkDeleted(id, syncState = SYNC_DIRTY, atEpochMillis = now())
    }

    override suspend fun nameBookmark(id: String, title: String, note: String) = withContext(ioDispatcher) {
        val name = title.oneLine().takeIf(String::isNotEmpty)
        dao.setBookmarkNaming(id, title = name, note = note.trim().takeIf(String::isNotEmpty), atEpochMillis = now())
    }

    private companion object {
        /** A change no server has yet; a sync (later) sends these. */
        const val SYNC_DIRTY = "DIRTY"

        /** A bookmark keeps the page's first words, enough for a line in a list. */
        const val MAX_BOOKMARK_TEXT = 240
    }
}

internal class RoomSavedWordStudyStore(
    private val dao: SavedWordStudyDao,
    private val ioDispatcher: CoroutineDispatcher,
    private val now: () -> Long,
) : SavedWordStudyStore {
    override suspend fun setWordNote(id: String, note: String) = withContext(ioDispatcher) {
        dao.setWordNote(id, note.trim().takeIf(String::isNotEmpty))
    }

    override suspend fun reviewWord(id: String, known: Boolean): SavedWord? = withContext(ioDispatcher) {
        val stored = dao.getWord(id) ?: return@withContext null
        val at = now()
        val next = WordReviewSchedule.next(level = stored.reviewLevel, known = known, atEpochMillis = at)
        dao.setWordReview(id, level = next.level, dueAtEpochMillis = next.dueAtEpochMillis, atEpochMillis = at)
        dao.getWord(id)?.toModel()
    }
}

internal fun ReaderBookmarkEntity.toModel(): ReaderBookmark = ReaderBookmark(
    id = id,
    book = AnnotatedBook(itemId = itemId, title = bookTitle, author = bookAuthor),
    place = AnnotationPlace(
        href = href,
        locatorJson = locatorJson,
        chapterTitle = chapterTitle,
        progression = progression,
    ),
    page = page,
    pageCount = pageCount,
    text = text,
    createdAtEpochMillis = createdAtEpochMillis,
    title = title,
    note = note,
)
