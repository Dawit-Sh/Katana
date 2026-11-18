package com.example.unifiedreader.feature.annotations

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnnotationBackupStoreTest {
    private lateinit var source: UnifiedReaderDatabase
    private lateinit var target: UnifiedReaderDatabase
    private var clock = 1_000L
    private var ids = 0

    private val hole = AnnotatedBook("grimmory:item:2694", "The Hole", "Hiroko Oyamada")
    private val place = AnnotationPlace("c1.xhtml", "{\"href\":\"c1.xhtml\"}", "Chapter 1", 0.07)

    @Before
    fun setUp() {
        source = inMemoryDatabase()
        target = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    private fun inMemoryDatabase(): UnifiedReaderDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        UnifiedReaderDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun store(database: UnifiedReaderDatabase, prefix: String) = RoomReaderAnnotationStore(
        dao = database.readerAnnotationDao(),
        bookmarkDao = database.readerBookmarkDao(),
        studyDao = database.savedWordStudyDao(),
        ioDispatcher = Dispatchers.Unconfined,
        now = { clock++ },
        newId = { "$prefix${++ids}" },
    )

    private fun backups(database: UnifiedReaderDatabase) = AnnotationBackupStore(
        dao = database.readerAnnotationDao(),
        bookmarkDao = database.readerBookmarkDao(),
        ioDispatcher = Dispatchers.Unconfined,
        now = { 1_763_424_000_000L },
    )

    @Test
    fun `a backup restores every highlight, note and word on another tablet`() = runBlocking {
        val notes = store(source, "a")
        notes.addHighlight(hole, place, "sumo mandarins", HighlightLook(HighlightStyle.Squiggly, HighlightColor.Blue), note = "Odd")
        notes.saveWord(hole, "solar", "A solar flare.", place)

        val file = backups(source).backUp()
        val result = backups(target).restore(file.text)

        assertEquals(AnnotationRestoreResult(addedHighlights = 1, addedWords = 1, alreadyHere = 0), result)
        val restored = store(target, "b")
        val highlight = restored.allHighlights.first().single()
        assertEquals("sumo mandarins", highlight.text)
        assertEquals("Odd", highlight.note)
        assertEquals(HighlightStyle.Squiggly, highlight.style)
        assertEquals(HighlightColor.Blue, highlight.color)
        assertEquals(place, highlight.place)
        assertEquals("A solar flare.", restored.allWords.first().single().sentence)
    }

    @Test
    fun `restoring twice adds nothing the second time`() = runBlocking {
        store(source, "a").addHighlight(hole, place, "sumo mandarins", HighlightLook())
        val text = backups(source).backUp().text

        backups(target).restore(text)
        val again = backups(target).restore(text)

        assertEquals(AnnotationRestoreResult(addedHighlights = 0, addedWords = 0, alreadyHere = 1), again)
        assertEquals(1, store(target, "b").allHighlights.first().size)
    }

    @Test
    fun `the same words made here under another id are not doubled, and a removed highlight stays removed`() =
        runBlocking {
            val here = store(target, "t")
            here.addHighlight(hole, place, "sumo mandarins", HighlightLook())
            here.saveWord(hole, "Solar", "A solar flare.", place)
            val removed = here.addHighlight(hole, place, "the hole", HighlightLook())
            val text = backups(target).backUp().text
            here.deleteHighlight(removed.id)
            store(source, "s").apply {
                addHighlight(hole, place, "sumo mandarins", HighlightLook())
                saveWord(hole, "solar", "A solar flare.", place)
            }
            val fromOtherTablet = backups(source).backUp().text

            val first = backups(target).restore(text)
            val second = backups(target).restore(fromOtherTablet)

            assertEquals(0, first.addedHighlights)
            assertEquals(0, second.addedHighlights + second.addedWords)
            assertEquals(listOf("sumo mandarins"), here.allHighlights.first().map(ReaderHighlight::text))
            assertEquals(1, here.allWords.first().size)
        }

    @Test
    fun `a file that isn't a Katana backup is refused`() = runBlocking {
        listOf("", "not json", "{\"app\":\"Moon+\",\"exportedAt\":1}", "{\"highlights\":[]}").forEach { text ->
            val refused = try {
                backups(target).restore(text)
                null
            } catch (error: AnnotationBackupFormatException) {
                error
            }
            assertTrue(text, refused != null)
        }
    }

    @Test
    fun `bookmarks and a word's review state come back, once`() = runBlocking {
        source.readerBookmarkDao().upsertBookmark(bookmark("p1", progression = 0.2))
        source.readerBookmarkDao().upsertBookmark(bookmark("p2", progression = 0.5))
        val word = store(source, "a").saveWord(hole, "solar", "A solar flare.", place)
        val dao = source.readerAnnotationDao()
        dao.upsertWord(dao.observeAllWords().first().single().copy(reviewLevel = 3, reviewDueAtEpochMillis = 9_000L))
        target.readerBookmarkDao().upsertBookmark(bookmark("other-id", progression = 0.5))
        val text = backups(source).backUp().text

        val result = backups(target).restore(text)

        assertEquals(AnnotationRestoreResult(addedHighlights = 0, addedWords = 1, alreadyHere = 1, addedBookmarks = 1), result)
        assertEquals(
            listOf("other-id", "p1"),
            target.readerBookmarkDao().observeAllBookmarks().first().map(ReaderBookmarkEntity::id).sorted(),
        )
        val restoredWord = target.readerAnnotationDao().observeAllWords().first().single()
        assertEquals(word.id, restoredWord.id)
        assertEquals(3, restoredWord.reviewLevel)
        assertEquals(9_000L, restoredWord.reviewDueAtEpochMillis)
    }

    private fun bookmark(id: String, progression: Double) = ReaderBookmarkEntity(
        id = id,
        itemId = hole.itemId,
        bookTitle = hole.title,
        bookAuthor = hole.author,
        href = "c1.xhtml",
        locatorJson = "{\"href\":\"c1.xhtml\"}",
        chapterTitle = "Chapter 1",
        progression = progression,
        page = null,
        pageCount = null,
        text = null,
        createdAtEpochMillis = 5L,
        updatedAtEpochMillis = 5L,
        deleted = false,
        syncState = "DIRTY",
        remoteId = null,
    )

    @Test
    fun `the restore message counts what was added`() {
        assertEquals("Added 1 highlight, 2 words and 3 bookmarks", AnnotationRestoreResult(1, 2, 0, 3).message)
        assertEquals("Added 2 highlights and 1 word", AnnotationRestoreResult(2, 1, 0).message)
        assertEquals("Added 1 highlight", AnnotationRestoreResult(1, 0, 3).message)
        assertEquals("Nothing new: this device has it all", AnnotationRestoreResult(0, 0, 3).message)
    }

    @Test
    fun `the file is named for the day`() {
        assertEquals("Katana backup 2025-11-18.json", annotationBackupFileName(1_763_424_000_000L, ZoneOffset.UTC))
    }
}
