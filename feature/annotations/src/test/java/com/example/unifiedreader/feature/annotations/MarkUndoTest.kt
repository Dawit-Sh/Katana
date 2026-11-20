package com.example.unifiedreader.feature.annotations

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_ON_DEVICE
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MarkUndoTest {
    private lateinit var database: UnifiedReaderDatabase
    private lateinit var store: RoomReaderAnnotationStore
    private var clock = 100L
    private var ids = 0

    private val hole = AnnotatedBook("grimmory:item:2694", "The Hole", "Hiroko Oyamada")
    private val place = AnnotationPlace("c1.xhtml", "{\"href\":\"c1.xhtml\"}", "Chapter 1", 0.07)
    private val cfi = "epubcfi(/6/14!/4/2,/8/1:10,/8/1:40)"

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
            newId = { "m${++ids}" },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `undo brings a highlight back with its id, words, place, style, colour and note`() = runBlocking {
        val look = HighlightLook(HighlightStyle.Underline, HighlightColor.Pink)
        val made = store.addHighlight(hole, place, "sumo mandarins", look, note = "the box")

        val removed = store.removeHighlight(made.id)!!
        assertTrue(store.highlights(hole.itemId).first().isEmpty())
        store.restoreHighlight(removed)

        val back = store.highlights(hole.itemId).first().single()
        assertEquals(made.id, back.id)
        assertEquals(made.text, back.text)
        assertEquals(made.place, back.place)
        assertEquals(look, HighlightLook(back.style, back.color))
        assertEquals("the box", back.note)
        assertEquals(made.createdAtEpochMillis, back.createdAtEpochMillis)
        assertNull(store.removeHighlight("missing"))
    }

    @Test
    fun `undo brings a bookmark back with its id, place, name and note`() = runBlocking {
        val made = store.addBookmark(hole, place, page = 7, pageCount = 78, text = "they came by")
        store.nameBookmark(made.id, "the box", "mandarins")

        val removed = store.removeBookmark(made.id)!!
        assertTrue(store.bookmarks(hole.itemId).first().isEmpty())
        store.restoreBookmark(removed)

        val back = store.bookmarks(hole.itemId).first().single()
        assertEquals(made.copy(title = "the box", note = "mandarins"), back)
    }

    @Test
    fun `the toast's Undo restores every bookmark the page had`() = runBlocking {
        val first = store.addBookmark(hole, place, page = 7, pageCount = 78, text = null)
        val second = store.addBookmark(hole, place, page = 7, pageCount = 78, text = null)
        val toast = store.removeBookmarksForUndo(
            listOf(first.id, second.id),
            TestScope(UnconfinedTestDispatcher()),
            BookmarksCopy.BookmarkRemoved,
        )
        assertTrue(store.bookmarks(hole.itemId).first().isEmpty())
        assertEquals(BookmarksCopy.Undo, toast.actionLabel)
        toast.onAction!!.invoke()

        assertEquals(setOf(first.id, second.id), store.bookmarks(hole.itemId).first().map(ReaderBookmark::id).toSet())
    }

    @Test
    fun `the toast's Undo brings a removed word back with its id, sentence, place, note and review state`() = runBlocking {
        val saved = store.saveWord(hole, "liminal", "The liminal field.", place)
        store.setWordNote(saved.id, "in between")
        store.reviewWord(saved.id, known = true)
        val before = store.allWords.first().single()

        val toast = store.removeWordForUndo(before, TestScope(UnconfinedTestDispatcher()), BookmarksCopy.WordRemoved)
        assertTrue(store.allWords.first().isEmpty())
        assertEquals(BookmarksCopy.WordRemoved, toast.text)
        assertEquals(BookmarksCopy.Undo, toast.actionLabel)
        toast.onAction!!.invoke()

        assertEquals(before, store.allWords.first().single())
    }

    @Test
    fun `restored before a sync, the sync sees it as never removed`() {
        val inStep = row(remoteId = "r1", syncState = HIGHLIGHT_IN_STEP, title = "the box")
        val removedRow = inStep.copy(deleted = true, syncState = HIGHLIGHT_WAITING, updatedAtEpochMillis = 2_000)

        val restored = restoredBookmarkRow(inStep, current = removedRow, nowEpochMillis = 3_000)!!
        assertEquals(inStep.id, restored.id)
        assertEquals("r1", restored.remoteId)
        assertEquals(false, restored.deleted)

        val server = RemoteBookmark("r1", cfi, "the box", null, 1_000, 1_000)
        val plan = planBookmarkSync(hole, listOf(restored), listOf(server), 4_000, newId = { "new" })
        assertTrue(plan.uploads.isEmpty())
        assertTrue(plan.purges.isEmpty())
        assertEquals(HIGHLIGHT_IN_STEP, plan.writes.single().row.syncState)
    }

    @Test
    fun `restored after the server was told, the next sync makes it there again`() {
        val inStep = row(remoteId = "r1", syncState = HIGHLIGHT_IN_STEP)

        // The sync already cleared the removed row here too.
        val restored = restoredBookmarkRow(inStep, current = null, nowEpochMillis = 3_000)!!
        assertNull(restored.remoteId)
        assertEquals(HIGHLIGHT_WAITING, restored.syncState)

        val plan = planBookmarkSync(hole, listOf(restored), emptyList(), 4_000, newId = { "new" })
        assertTrue(plan.purges.isEmpty())
        assertEquals(inStep.id, (plan.uploads.single() as BookmarkUpload.Create).localId)

        // Told, but not yet cleared here: the server's id goes, and it is made again.
        val told = inStep.copy(deleted = true, syncState = HIGHLIGHT_WAITING, updatedAtEpochMillis = 2_000)
        val restoredLate = restoredBookmarkRow(inStep, current = told, nowEpochMillis = 3_000)!!
        val latePlan = planBookmarkSync(hole, listOf(restoredLate), emptyList(), 4_000, newId = { "new" })
        assertTrue(latePlan.purges.isEmpty())
        assertNull(latePlan.writes.single().row.remoteId)
        assertTrue(latePlan.uploads.single() is BookmarkUpload.Create)
    }

    @Test
    fun `a restored row is newer than the removal, so a sync's stale write misses it`() {
        val removedRow = row(remoteId = "r1").copy(deleted = true, updatedAtEpochMillis = 5_000)
        val restored = restoredBookmarkRow(row(remoteId = "r1"), current = removedRow, nowEpochMillis = 5_000)!!
        assertTrue(restored.updatedAtEpochMillis > removedRow.updatedAtEpochMillis)
    }

    @Test
    fun `one kept on this device stays so, and one already back is left alone`() {
        val onDevice = row(remoteId = null, syncState = HIGHLIGHT_ON_DEVICE)
        assertEquals(HIGHLIGHT_ON_DEVICE, restoredBookmarkRow(onDevice, current = null, nowEpochMillis = 3_000)!!.syncState)

        val backAlready = row(remoteId = "r1", syncState = HIGHLIGHT_IN_STEP)
        assertNull(restoredBookmarkRow(backAlready, current = backAlready, nowEpochMillis = 3_000))
    }

    private fun row(
        remoteId: String?,
        syncState: String = HIGHLIGHT_WAITING,
        title: String? = null,
    ) = ReaderBookmarkEntity(
        id = "b1",
        itemId = hole.itemId,
        bookTitle = hole.title,
        bookAuthor = hole.author,
        href = "OEBPS/c2.xhtml",
        locatorJson = """{"href":"OEBPS/c2.xhtml"}""",
        chapterTitle = "Chapter Two",
        progression = 0.1,
        page = 7,
        pageCount = 78,
        text = "they came by with a box",
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
        deleted = false,
        syncState = syncState,
        remoteId = remoteId,
        remoteCfi = cfi,
        title = title,
    )
}
