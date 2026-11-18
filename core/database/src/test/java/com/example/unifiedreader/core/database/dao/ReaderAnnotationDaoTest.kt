package com.example.unifiedreader.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
import com.example.unifiedreader.core.database.entity.SavedWordEntity
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
class ReaderAnnotationDaoTest {
    private lateinit var database: UnifiedReaderDatabase
    private lateinit var dao: ReaderAnnotationDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            UnifiedReaderDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = database.readerAnnotationDao()
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
