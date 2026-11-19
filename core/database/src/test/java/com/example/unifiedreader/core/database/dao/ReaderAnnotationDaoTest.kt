package com.example.unifiedreader.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
import com.example.unifiedreader.core.database.entity.SavedWordEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderAnnotationDaoTest {
    private lateinit var database: UnifiedReaderDatabase
    private lateinit var dao: ReaderAnnotationDao
    private lateinit var bookmarks: ReaderBookmarkDao
    private lateinit var study: SavedWordStudyDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            UnifiedReaderDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.readerAnnotationDao()
        bookmarks = database.readerBookmarkDao()
        study = database.savedWordStudyDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a book's highlights come in reading order and a removed one leaves the lists but stays stored`() = runBlocking {
        dao.upsertHighlight(highlight(id = "late", progression = 0.8, createdAt = 1))
        dao.upsertHighlight(highlight(id = "early", progression = 0.1, createdAt = 3))
        dao.upsertHighlight(highlight(id = "unplaced", progression = null, createdAt = 2))
        dao.upsertHighlight(highlight(id = "other", itemId = "grimmory:item:2", progression = 0.5, createdAt = 4))

        assertEquals(listOf("early", "late", "unplaced"), dao.observeHighlights("grimmory:item:1").first().map { it.id })

        dao.markHighlightDeleted("late", syncState = "DIRTY", atEpochMillis = 9)

        assertEquals(listOf("early", "unplaced"), dao.observeHighlights("grimmory:item:1").first().map { it.id })
        assertEquals(listOf("other", "early", "unplaced"), dao.observeAllHighlights().first().map { it.id })
        val removed = dao.getHighlight("late")!!
        assertTrue(removed.deleted)
        assertEquals(9L, removed.updatedAtEpochMillis)
    }

    @Test
    fun `a changed highlight replaces the stored one`() = runBlocking {
        dao.upsertHighlight(highlight(id = "a", progression = 0.2, createdAt = 1))
        dao.upsertHighlight(highlight(id = "a", progression = 0.2, createdAt = 1).copy(style = "UNDERLINE", note = "why"))

        val stored = dao.observeHighlights("grimmory:item:1").first().single()
        assertEquals("UNDERLINE", stored.style)
        assertEquals("why", stored.note)
    }

    @Test
    fun `saved words come per book in reading order, and every word newest first`() = runBlocking {
        dao.upsertWord(word(id = "w2", word = "liminal", progression = 0.6, createdAt = 2))
        dao.upsertWord(word(id = "w1", word = "verdant", progression = 0.2, createdAt = 3))
        dao.upsertWord(word(id = "w3", word = "sere", itemId = "grimmory:item:2", progression = 0.1, createdAt = 1))

        assertEquals(listOf("verdant", "liminal"), dao.observeWords("grimmory:item:1").first().map { it.word })
        assertEquals(listOf("verdant", "liminal", "sere"), dao.observeAllWords().first().map { it.word })

        dao.deleteWord("w1")

        assertEquals(listOf("liminal"), dao.observeWords("grimmory:item:1").first().map { it.word })
    }

    private fun highlight(
        id: String,
        itemId: String = "grimmory:item:1",
        progression: Double?,
        createdAt: Long,
    ) = ReaderHighlightEntity(
        id = id,
        itemId = itemId,
        bookTitle = "The Hole",
        bookAuthor = "Hiroko Oyamada",
        href = "OEBPS/chapter1.xhtml",
        locatorJson = "{}",
        text = "a box of the most beautiful sumo mandarins",
        chapterTitle = "Chapter 1",
        progression = progression,
        style = "HIGHLIGHT",
        color = "YELLOW",
        note = null,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
        deleted = false,
        syncState = "DIRTY",
        remoteId = null,
        remoteCfi = null,
    )

    @Test
    fun `a book's bookmarks come in reading order and a removed one leaves the lists but stays stored`() = runBlocking {
        bookmarks.upsertBookmark(bookmark(id = "late", progression = 0.8, createdAt = 1))
        bookmarks.upsertBookmark(bookmark(id = "early", progression = 0.1, createdAt = 3))
        bookmarks.upsertBookmark(bookmark(id = "other", itemId = "grimmory:item:2", progression = 0.5, createdAt = 4))

        assertEquals(listOf("early", "late"), bookmarks.observeBookmarks("grimmory:item:1").first().map { it.id })
        assertEquals(listOf("other", "early", "late"), bookmarks.observeAllBookmarks().first().map { it.id })

        bookmarks.markBookmarkDeleted("late", syncState = "DIRTY", atEpochMillis = 9)

        assertEquals(listOf("early"), bookmarks.observeBookmarks("grimmory:item:1").first().map { it.id })
        assertEquals(listOf("other", "early"), bookmarks.observeAllBookmarks().first().map { it.id })
    }

    @Test
    fun `a bookmark's name and note are kept, and the change waits for its server`() = runBlocking {
        bookmarks.upsertBookmark(bookmark(id = "b", progression = 0.1, createdAt = 1).copy(syncState = "CLEAN", remoteId = "r1"))

        bookmarks.setBookmarkNaming("b", title = "the box", note = "mandarins", atEpochMillis = 5)

        val stored = bookmarks.observeBookmarks("grimmory:item:1").first().single()
        assertEquals("the box", stored.title)
        assertEquals("mandarins", stored.note)
        assertEquals("DIRTY", stored.syncState)
        assertEquals(listOf("b"), database.readerBookmarkSyncDao().observeWaitingBookmarks().first().map { it.id })
    }

    @Test
    fun `a new bookmark waits for its place, and one from the server is placed once`() = runBlocking {
        bookmarks.upsertBookmark(bookmark(id = "mine", progression = 0.1, createdAt = 1))
        bookmarks.upsertBookmark(
            bookmark(id = "theirs", progression = null, createdAt = 2)
                .copy(locatorJson = "", text = null, syncState = "CLEAN", remoteId = "r2", remoteCfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)"),
        )
        val sync = database.readerBookmarkSyncDao()
        assertTrue(sync.observeWaitingBookmarks().first().isEmpty())
        assertEquals(setOf("mine", "theirs"), bookmarks.observeBookmarksToPlace("grimmory:item:1").first().map { it.id }.toSet())

        bookmarks.setBookmarkCfi("mine", "epubcfi(/6/4!/4/2,/1:9,/1:20)")
        bookmarks.placeBookmark("theirs", "c1.xhtml", "{\"href\":\"c1.xhtml\"}", 0.3, 24, 78, "the words there", "Chapter 1")

        assertTrue(bookmarks.observeBookmarksToPlace("grimmory:item:1").first().isEmpty())
        assertEquals(listOf("mine"), sync.observeWaitingBookmarks().first().map { it.id })
        val placed = sync.getBookmark("theirs")!!
        assertEquals(24, placed.page)
        assertEquals("the words there", placed.text)
    }

    @Test
    fun `a word keeps its note and its flashcard review`() = runBlocking {
        dao.upsertWord(word(id = "w", word = "solar", progression = 0.1, createdAt = 1))
        val fresh = study.getWord("w")!!
        assertEquals(0, fresh.reviewLevel)
        assertNull(fresh.reviewDueAtEpochMillis)

        study.setWordNote("w", "of the sun")
        study.setWordReview("w", level = 2, dueAtEpochMillis = 500, atEpochMillis = 100)

        val reviewed = study.getWord("w")!!
        assertEquals("of the sun", reviewed.note)
        assertEquals(2, reviewed.reviewLevel)
        assertEquals(500L, reviewed.reviewDueAtEpochMillis)
        assertEquals(100L, reviewed.reviewedAtEpochMillis)
        assertNull(study.getWord("missing"))
    }

    private fun bookmark(
        id: String,
        itemId: String = "grimmory:item:1",
        progression: Double?,
        createdAt: Long,
    ) = ReaderBookmarkEntity(
        id = id,
        itemId = itemId,
        bookTitle = "The Hole",
        bookAuthor = "Hiroko Oyamada",
        href = "c1.xhtml",
        locatorJson = "{}",
        chapterTitle = "Chapter 1",
        progression = progression,
        page = 8,
        pageCount = 78,
        text = "home looked like.",
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
        deleted = false,
        syncState = "DIRTY",
        remoteId = null,
    )

    private fun word(
        id: String,
        word: String,
        itemId: String = "grimmory:item:1",
        progression: Double?,
        createdAt: Long,
    ) = SavedWordEntity(
        id = id,
        itemId = itemId,
        bookTitle = "The Hole",
        bookAuthor = "Hiroko Oyamada",
        word = word,
        sentence = "The $word field stretched past the house.",
        chapterTitle = "Chapter 1",
        locatorJson = null,
        progression = progression,
        note = null,
        createdAtEpochMillis = createdAt,
    )
}
