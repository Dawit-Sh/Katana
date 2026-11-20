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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MyWordsAddingTest {
    private lateinit var database: UnifiedReaderDatabase
    private lateinit var store: RoomReaderAnnotationStore
    private val hole = AnnotatedBook("grimmory:item:2694", "The Hole", "Hiroko Oyamada")
    private val place = AnnotationPlace("c1.xhtml", "{\"href\":\"c1.xhtml\"}", "Chapter 1", 0.07)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnifiedReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomReaderAnnotationStore(
            dao = database.readerAnnotationDao(),
            bookmarkDao = database.readerBookmarkDao(),
            studyDao = database.savedWordStudyDao(),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `Add to My words saves the word with its sentence, place and first definition`() = runBlocking {
        val saved = store.addToMyWords(hole, "geese", "The geese flew over.", place, meaning = "a web-footed bird")

        val word = store.words(hole.itemId).first().single()
        assertEquals("geese", word.word)
        assertEquals("The geese flew over.", word.sentence)
        assertEquals(place.locatorJson, word.place?.locatorJson)
        assertEquals(place.chapterTitle, word.place?.chapterTitle)
        assertEquals("a web-footed bird", word.note)
        assertEquals(word.note, saved.note)
    }

    @Test
    fun `a word with no definition is saved without a meaning`() = runBlocking {
        store.addToMyWords(hole, "zzyzx", "Zzyzx road.", place, meaning = null)

        assertNull(store.words(hole.itemId).first().single().note)
    }
}
