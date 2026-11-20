package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import com.example.unifiedreader.core.database.entity.WaitingBookmarkKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Many marks gone from the server at once are kept here until the reader answers; a few go at once. */
class MarkRemovalHoldTest {
    private val book = AnnotatedBook("grimmory:item:35", "Calculus I", null)
    private val store = FakeBookmarkStore()
    private val engine = BookmarkSyncEngine(store, now = { 10_000L }, newId = { "new" })
    private val nobody = object : BookmarkServer {
        override suspend fun create(upload: BookmarkUpload.Create) = error("not sent")

        override suspend fun update(upload: BookmarkUpload.Update) = error("not sent")

        override suspend fun delete(upload: BookmarkUpload.Delete) = error("not sent")
    }

    @Before
    fun setUp() = MarkRemovalHolds.clear()

    @Test
    fun `at least five and more than half of a book's marks is many, fewer is a few`() {
        assertTrue(isMassRemoval(removed = 5, kept = 9))
        assertTrue(isMassRemoval(removed = 12, kept = 12))
        assertFalse("not more than half", isMassRemoval(removed = 5, kept = 10))
        assertFalse("one or two removed on the web", isMassRemoval(removed = 2, kept = 2))
        assertFalse(isMassRemoval(removed = 4, kept = 4))
    }

    @Test
    fun `a server that lost six of six bookmarks changes nothing here and asks`() = runBlocking {
        store.rows += (1..6).map { inStep("b$it") }

        engine.syncBook(book, remote = emptyList(), server = nobody, places = BookmarkPlaces.Pages(40))

        assertEquals(6, store.rows.size)
        assertEquals(6, MarkRemovalHolds.current(book.itemId)?.bookmarks?.size)
        assertEquals("Calculus I", MarkRemovalHolds.current(book.itemId)?.bookTitle)
    }

    @Test
    fun `a question put aside loses nothing and comes again with the next sync`() = runBlocking {
        store.rows += (1..6).map { inStep("b$it") }
        engine.syncBook(book, emptyList(), nobody, BookmarkPlaces.Pages(40))
        MarkRemovalHolds.clear()

        engine.syncBook(book, emptyList(), nobody, BookmarkPlaces.Pages(40))

        assertEquals(6, store.rows.size)
        assertEquals(6, MarkRemovalHolds.current(book.itemId)?.count)
    }

    @Test
    fun `two removed on the web go here at once, as before`() = runBlocking {
        store.rows += listOf(inStep("b1"), inStep("b2"))

        engine.syncBook(book, emptyList(), nobody, BookmarkPlaces.Pages(40))

        assertTrue(store.rows.isEmpty())
        assertNull(MarkRemovalHolds.current(book.itemId))
    }

    @Test
    fun `removed here is not counted as lost by the server`() = runBlocking {
        store.rows += (1..6).map { inStep("b$it").copy(deleted = true, syncState = HIGHLIGHT_WAITING) }

        engine.syncBook(book, emptyList(), nobody, BookmarkPlaces.Pages(40))

        assertTrue("removals made here are just confirmed", store.rows.isEmpty())
        assertNull(MarkRemovalHolds.current(book.itemId))
    }

    @Test
    fun `remove here removes them, put back makes them new for the next sync`() = runBlocking {
        store.rows += (1..6).map { inStep("b$it") }
        engine.syncBook(book, emptyList(), nobody, BookmarkPlaces.Pages(40))
        val hold = MarkRemovalHolds.current(book.itemId)!!

        settleMarkRemovalHold(hold, removeHere = false, highlights = NoHighlights, bookmarks = store)
        assertTrue("new again, to go up", store.rows.all { it.remoteId == null && it.syncState == HIGHLIGHT_WAITING })
        assertNull(MarkRemovalHolds.current(book.itemId))

        val again = MarkRemovalHold(book.itemId, book.title, bookmarks = store.rows.map { HeldMark(it.id, it.updatedAtEpochMillis) })
        settleMarkRemovalHold(again, removeHere = true, highlights = NoHighlights, bookmarks = store)
        assertTrue(store.rows.isEmpty())
    }

    private fun inStep(id: String) = ReaderBookmarkEntity(
        id = id,
        itemId = book.itemId,
        bookTitle = book.title,
        bookAuthor = null,
        href = "page:${id.removePrefix("b").toInt()}",
        locatorJson = "{\"page\":${id.removePrefix("b").toInt()}}",
        chapterTitle = null,
        progression = null,
        page = null,
        pageCount = null,
        text = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        deleted = false,
        syncState = HIGHLIGHT_IN_STEP,
        remoteId = "r$id",
    )

    private class FakeBookmarkStore : BookmarkSyncStore {
        val rows = mutableListOf<ReaderBookmarkEntity>()

        override fun observeWaiting(): Flow<List<WaitingBookmarkKey>> = flowOf(emptyList())

        override suspend fun rowsOf(itemId: String) = rows.filter { it.itemId == itemId }

        override suspend fun booksOnServer() = rows.mapTo(mutableSetOf()) { it.itemId }

        override suspend fun apply(writes: List<BookmarkRowWrite>, purges: List<BookmarkRowPurge>) {
            val gone = purges.mapTo(mutableSetOf(), BookmarkRowPurge::id)
            rows.removeAll { it.id in gone }
            writes.forEach { write -> rows.replaceAll { row -> if (row.id == write.row.id) write.row else row } }
        }

        override suspend fun created(localId: String, expectedUpdatedAt: Long, remoteId: String) = Unit

        override suspend fun sent(localId: String, expectedUpdatedAt: Long) = Unit

        override suspend fun removedOnServer(localId: String) = Unit

        override suspend fun lostOnServer(localId: String) = Unit

        override suspend fun refused(localId: String, expectedUpdatedAt: Long) = Unit
    }

    private object NoHighlights : HighlightSyncStore {
        override fun observeWaiting() = flowOf(emptyList<com.example.unifiedreader.core.database.entity.WaitingHighlightKey>())

        override suspend fun rowsOf(itemId: String) = emptyList<com.example.unifiedreader.core.database.entity.ReaderHighlightEntity>()

        override suspend fun booksOnServer() = emptySet<String>()

        override suspend fun apply(
            writes: List<com.example.unifiedreader.core.database.entity.HighlightRowWrite>,
            purges: List<com.example.unifiedreader.core.database.entity.HighlightRowPurge>,
        ) = Unit

        override suspend fun created(localId: String, expectedUpdatedAt: Long, remoteId: String) = Unit

        override suspend fun sent(localId: String, expectedUpdatedAt: Long) = Unit

        override suspend fun removedOnServer(localId: String) = Unit

        override suspend fun lostOnServer(localId: String) = Unit

        override suspend fun refused(localId: String, expectedUpdatedAt: Long) = Unit
    }
}
