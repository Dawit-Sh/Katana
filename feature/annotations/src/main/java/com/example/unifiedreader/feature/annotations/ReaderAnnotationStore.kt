package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.dao.ReaderAnnotationDao
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
import com.example.unifiedreader.core.database.entity.SavedWordEntity
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The reader's highlights and saved words on this device. */
interface ReaderAnnotationStore {
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
}

class RoomReaderAnnotationStore(
    private val dao: ReaderAnnotationDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ReaderAnnotationStore {
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

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }

    private companion object {
        /** A change no server has yet; the Grimmory sync (later) sends these. */
        const val SYNC_DIRTY = "DIRTY"
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
)
