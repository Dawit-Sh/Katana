package com.example.unifiedreader.feature.annotations

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import kotlinx.coroutines.Dispatchers
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
class RoomReaderAnnotationStoreTest {
    private lateinit var database: UnifiedReaderDatabase
    private lateinit var store: RoomReaderAnnotationStore
    private var clock = 100L
    private var ids = 0

    private val hole = AnnotatedBook("grimmory:item:2694", "The Hole", "Hiroko Oyamada")
    private val place = AnnotationPlace("c1.xhtml", "{\"href\":\"c1.xhtml\"}", "Chapter 1", 0.07)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            UnifiedReaderDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        store = RoomReaderAnnotationStore(
            dao = database.readerAnnotationDao(),
            bookmarkDao = database.readerBookmarkDao(),
            studyDao = database.savedWordStudyDao(),
            ioDispatcher = Dispatchers.Unconfined,
            now = { clock++ },
            newId = { "h${++ids}" },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a new highlight takes the look of the last one, which starts as a yellow highlight`() = runBlocking {
        assertEquals(HighlightLook(), store.lastLook())

        store.addHighlight(hole, place, "  sumo mandarins ", HighlightLook(HighlightStyle.Underline, HighlightColor.Blue))

        assertEquals(HighlightLook(HighlightStyle.Underline, HighlightColor.Blue), store.lastLook())
        val saved = store.highlights(hole.itemId).first().single()
        assertEquals("sumo mandarins", saved.text)
        assertEquals(place, saved.place)
        assertEquals(hole, saved.book)
    }

    @Test
    fun `changing a highlight keeps what isn't named, and a blank note removes the note`() = runBlocking {
        val made = store.addHighlight(hole, place, "words", HighlightLook(), note = "first")

        store.updateHighlight(made.id, color = HighlightColor.Pink)
        assertEquals("first", store.highlights(hole.itemId).first().single().note)

        val changed = store.updateHighlight(made.id, style = HighlightStyle.Squiggly, note = "  ")!!
        assertEquals(HighlightStyle.Squiggly, changed.style)
        assertEquals(HighlightColor.Pink, changed.color)
        assertNull(changed.note)
        assertTrue(changed.updatedAtEpochMillis > made.updatedAtEpochMillis)
    }

    @Test
    fun `a deleted highlight leaves the lists and can't be changed`() = runBlocking {
        val made = store.addHighlight(hole, place, "words", HighlightLook())

        store.deleteHighlight(made.id)

        assertTrue(store.highlights(hole.itemId).first().isEmpty())
        assertTrue(store.allHighlights.first().isEmpty())
        assertNull(store.updateHighlight(made.id, note = "late"))
    }

    @Test
    fun `saved words keep their sentence and place, and can be removed`() = runBlocking {
        val word = store.saveWord(hole, " liminal ", "  The liminal field. ", place)

        assertEquals("liminal", word.word)
        assertEquals("The liminal field.", word.sentence)
        assertEquals("Chapter 1", store.allWords.first().single().place?.chapterTitle)

        store.deleteWord(word.id)

        assertTrue(store.words(hole.itemId).first().isEmpty())
    }

    @Test
    fun `a bookmark keeps its page and first words, and a deleted one leaves the lists`() = runBlocking {
        val made = store.addBookmark(hole, place, page = 8, pageCount = 78, text = "  home looked like.\n All I could   remember ")

        val saved = store.bookmarks(hole.itemId).first().single()
        assertEquals(made, saved)
        assertEquals(8, saved.page)
        assertEquals(78, saved.pageCount)
        assertEquals("home looked like. All I could remember", saved.text)
        assertEquals(listOf(saved), store.allBookmarks.first())

        store.deleteBookmark(made.id)

        assertTrue(store.bookmarks(hole.itemId).first().isEmpty())
        assertTrue(store.allBookmarks.first().isEmpty())
    }

    @Test
    fun `a bookmark without a page keeps none, and its first words are cut to a line's worth`() = runBlocking {
        store.addBookmark(hole, place, page = 0, pageCount = null, text = "word ".repeat(100))

        val saved = store.bookmarks(hole.itemId).first().single()
        assertNull(saved.page)
        assertNull(saved.pageCount)
        assertEquals(240, saved.text?.length)
    }

    @Test
    fun `a word's note is kept, and a blank one removes it`() = runBlocking {
        val word = store.saveWord(hole, "solar", "solar panels on the roof", place)

        store.setWordNote(word.id, "  of the sun  ")
        assertEquals("of the sun", store.words(hole.itemId).first().single().note)

        store.setWordNote(word.id, " ")
        assertNull(store.words(hole.itemId).first().single().note)
    }

    @Test
    fun `knowing a word sends it away for a day, Again brings it back at once`() = runBlocking {
        val word = store.saveWord(hole, "solar", "solar panels on the roof", place)
        assertEquals(0, word.reviewLevel)
        assertNull(word.reviewDueAtEpochMillis)

        val known = store.reviewWord(word.id, known = true)!!
        assertEquals(1, known.reviewLevel)
        assertEquals(known.reviewedAtEpochMillis!! + 24L * 60 * 60 * 1000, known.reviewDueAtEpochMillis)

        val again = store.reviewWord(word.id, known = false)!!
        assertEquals(0, again.reviewLevel)
        assertEquals(again.reviewedAtEpochMillis, again.reviewDueAtEpochMillis)
        assertNull(store.reviewWord("missing", known = true))
    }
}
