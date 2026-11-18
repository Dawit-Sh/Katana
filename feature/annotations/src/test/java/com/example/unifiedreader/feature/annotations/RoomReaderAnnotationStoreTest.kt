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
}
