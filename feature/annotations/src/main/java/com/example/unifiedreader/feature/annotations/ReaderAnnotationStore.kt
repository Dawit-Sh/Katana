package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.dao.ReaderAnnotationDao
import com.example.unifiedreader.core.database.dao.ReaderBookmarkDao
import com.example.unifiedreader.core.database.dao.SavedWordStudyDao
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
import com.example.unifiedreader.core.database.entity.SavedWordEntity
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The reader's highlights, bookmarks and saved words on this device. */
interface ReaderAnnotationStore : ReaderBookmarkStore, SavedWordStudyStore {
    /** One book's highlights in reading order. */
    fun highlights(itemId: String): Flow<List<ReaderHighlight>>

    /** Every book's highlights, the latest change first. */
    val allHighlights: Flow<List<ReaderHighlight>>

    /** One book's saved words in reading order. */
    fun words(itemId: String): Flow<List<SavedWord>>

    /** Every saved word, the newest first. */
    val allWords: Flow<List<SavedWord>>

    /** The look of the last highlight made or changed, which a new one takes. */
    suspend fun lastLook(): HighlightLook

    suspend fun addHighlight(
        book: AnnotatedBook,
        place: AnnotationPlace,
        text: String,
        look: HighlightLook,
        note: String? = null,
    ): ReaderHighlight

    /** Changes a highlight's style, colour or note; null leaves that part as it is, a blank note removes it. */
    suspend fun updateHighlight(
        id: String,
        style: HighlightStyle? = null,
        color: HighlightColor? = null,
        note: String? = null,
    ): ReaderHighlight?

    suspend fun deleteHighlight(id: String)

    suspend fun saveWord(
        book: AnnotatedBook,
        word: String,
        sentence: String?,
        place: AnnotationPlace?,
    ): SavedWord

    suspend fun deleteWord(id: String)

    /**
     * A book's highlights its reader has to place for the server sync: new ones whose place the
     * server's way (an EPUB CFI) isn't worked out yet, and ones from the server not yet found in this
     * device's copy of the book. Empty for a store that doesn't sync.
     */
    fun highlightsToPlace(itemId: String): Flow<List<HighlightToPlace>> = flowOf(emptyList())

    /** The highlight's place the server's way, worked out by the reader; it can go to the server now. */
    suspend fun setServerPlace(highlightId: String, cfi: String) = Unit

    /** A new highlight whose place can't be put the server's way: it stays on this device. */
    suspend fun keepOnDevice(highlightId: String) = Unit

    /** Where a highlight from the server is in this device's copy of the book. */
    suspend fun place(highlightId: String, place: AnnotationPlace) = Unit
}

/**
 * A highlight its reader has to place: [locatorJson] is empty for one from the server, which has
 * [serverPlace] (its EPUB CFI) instead; a new one from this device has its locator and no server place yet.
 */
data class HighlightToPlace(
    val id: String,
    val locatorJson: String,
    val serverPlace: String?,
    val text: String,
    val chapterTitle: String?,
)

class RoomReaderAnnotationStore(
    private val dao: ReaderAnnotationDao,
    bookmarkDao: ReaderBookmarkDao,
    studyDao: SavedWordStudyDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ReaderAnnotationStore,
    ReaderBookmarkStore by RoomReaderBookmarkStore(bookmarkDao, ioDispatcher, now, newId),
    SavedWordStudyStore by RoomSavedWordStudyStore(studyDao, ioDispatcher, now) {
    override fun highlights(itemId: String): Flow<List<ReaderHighlight>> =
        dao.observeHighlights(itemId).map { rows -> rows.map(ReaderHighlightEntity::toModel) }.flowOn(ioDispatcher)

    override val allHighlights: Flow<List<ReaderHighlight>> =
        dao.observeAllHighlights().map { rows -> rows.map(ReaderHighlightEntity::toModel) }.flowOn(ioDispatcher)

    override fun words(itemId: String): Flow<List<SavedWord>> =
        dao.observeWords(itemId).map { rows -> rows.map(SavedWordEntity::toModel) }.flowOn(ioDispatcher)

    override val allWords: Flow<List<SavedWord>> =
        dao.observeAllWords().map { rows -> rows.map(SavedWordEntity::toModel) }.flowOn(ioDispatcher)

    override suspend fun lastLook(): HighlightLook = io {
        dao.getLatestHighlight()?.let { row ->
            HighlightLook(HighlightStyle.fromStored(row.style), HighlightColor.fromStored(row.color))
        } ?: HighlightLook()
    }

    override suspend fun addHighlight(
        book: AnnotatedBook,
        place: AnnotationPlace,
        text: String,
        look: HighlightLook,
        note: String?,
    ): ReaderHighlight = io {
        val at = now()
        val row = ReaderHighlightEntity(
            id = newId(),
            itemId = book.itemId,
            bookTitle = book.title,
            bookAuthor = book.author,
            href = place.href,
            locatorJson = place.locatorJson,
            text = text.trim(),
            chapterTitle = place.chapterTitle,
            progression = place.progression,
            style = look.style.name,
            color = look.color.name,
            note = note?.trim()?.takeIf(String::isNotEmpty),
            createdAtEpochMillis = at,
            updatedAtEpochMillis = at,
            deleted = false,
            syncState = SYNC_DIRTY,
            remoteId = null,
            remoteCfi = null,
        )
        dao.upsertHighlight(row)
        row.toModel()
    }

    override suspend fun updateHighlight(
        id: String,
        style: HighlightStyle?,
        color: HighlightColor?,
        note: String?,
    ): ReaderHighlight? = io {
        val stored = dao.getHighlight(id)?.takeUnless(ReaderHighlightEntity::deleted) ?: return@io null
        val changed = stored.copy(
            style = style?.name ?: stored.style,
            color = color?.name ?: stored.color,
            note = if (note == null) stored.note else note.trim().takeIf(String::isNotEmpty),
            updatedAtEpochMillis = now(),
            syncState = SYNC_DIRTY,
        )
        dao.upsertHighlight(changed)
        changed.toModel()
    }

    override suspend fun deleteHighlight(id: String) = io {
        dao.markHighlightDeleted(id, syncState = SYNC_DIRTY, atEpochMillis = now())
    }

    override suspend fun saveWord(
        book: AnnotatedBook,
        word: String,
        sentence: String?,
        place: AnnotationPlace?,
    ): SavedWord = io {
        val row = SavedWordEntity(
            id = newId(),
            itemId = book.itemId,
            bookTitle = book.title,
            bookAuthor = book.author,
            word = word.trim(),
            sentence = sentence?.trim()?.takeIf(String::isNotEmpty),
            chapterTitle = place?.chapterTitle,
            locatorJson = place?.locatorJson,
            progression = place?.progression,
            note = null,
            createdAtEpochMillis = now(),
        )
        dao.upsertWord(row)
        row.toModel()
    }

    override suspend fun deleteWord(id: String) = io { dao.deleteWord(id) }

    override fun highlightsToPlace(itemId: String): Flow<List<HighlightToPlace>> =
        dao.observeHighlightsToPlace(itemId)
            .map { rows ->
                rows.map { row ->
                    HighlightToPlace(row.id, row.locatorJson, row.remoteCfi, row.text, row.chapterTitle)
                }
            }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override suspend fun setServerPlace(highlightId: String, cfi: String) = io { dao.setHighlightCfi(highlightId, cfi) }

    override suspend fun keepOnDevice(highlightId: String) = io { dao.keepHighlightOnDevice(highlightId) }

    override suspend fun place(highlightId: String, place: AnnotationPlace) = io {
        dao.placeHighlight(highlightId, place.href, place.locatorJson, place.progression, place.chapterTitle)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }

    private companion object {
        /** A change no server has yet; the Grimmory sync sends these. */
        const val SYNC_DIRTY = HIGHLIGHT_WAITING
    }
}

internal fun ReaderHighlightEntity.toModel(): ReaderHighlight = ReaderHighlight(
    id = id,
    book = AnnotatedBook(itemId = itemId, title = bookTitle, author = bookAuthor),
    place = AnnotationPlace(
        href = href,
        locatorJson = locatorJson,
        chapterTitle = chapterTitle,
        progression = progression,
    ),
    text = text,
    style = HighlightStyle.fromStored(style),
    color = HighlightColor.fromStored(color),
    note = note,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun SavedWordEntity.toModel(): SavedWord = SavedWord(
    id = id,
    book = AnnotatedBook(itemId = itemId, title = bookTitle, author = bookAuthor),
    word = word,
    sentence = sentence,
    place = locatorJson?.let { json ->
        AnnotationPlace(href = "", locatorJson = json, chapterTitle = chapterTitle, progression = progression)
    },
    note = note,
    createdAtEpochMillis = createdAtEpochMillis,
    reviewLevel = reviewLevel,
    reviewDueAtEpochMillis = reviewDueAtEpochMillis,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)
