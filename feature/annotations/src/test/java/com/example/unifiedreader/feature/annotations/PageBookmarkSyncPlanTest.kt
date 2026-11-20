package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import com.example.unifiedreader.core.model.FUTURE_CLOCK_LIMIT_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bookmarks by the page (PDFs and comics) sync by the same rules as an EPUB's; and a wrong clock never wins. */
class PageBookmarkSyncPlanTest {
    private val book = AnnotatedBook("grimmory:item:12", "Saga", "Brian K. Vaughan")
    private val pages = BookmarkPlaces.Pages(pageCount = 41)
    private val now = 1_800_000_000_000L

    private fun plan(local: List<ReaderBookmarkEntity>, remote: List<RemoteBookmark>, places: BookmarkPlaces = pages) =
        planBookmarkSync(book, local, remote, now, newId = { "new" }, places = places)

    @Test
    fun `a page bookmarked here goes up at once as its page from 1, named as Grimmory names its own`() {
        val upload = plan(listOf(row("b1", pageIndex = 7)), emptyList()).uploads.single() as BookmarkUpload.Create

        assertEquals("8", upload.place)
        assertEquals("Page 8", upload.title)
    }

    @Test
    fun `a page bookmark from the server comes down on its page, its default name left on the server`() {
        val write = plan(emptyList(), listOf(remote("r1", place = "11", title = "Page 11", note = "the ship"))).writes.single()

        assertNull(write.expectedUpdatedAt)
        assertEquals("page:10", write.row.href)
        assertEquals("{\"page\":10}", write.row.locatorJson)
        assertEquals(11, write.row.page)
        assertEquals(41, write.row.pageCount)
        assertEquals(0.25, write.row.progression!!, 0.0001)
        assertNull("a page's own name doesn't name it here", write.row.title)
        assertEquals("the ship", write.row.note)
        assertNull(write.row.remoteCfi)
        assertEquals(HIGHLIGHT_IN_STEP, write.row.syncState)
    }

    @Test
    fun `a page bookmark whose answer was lost, or that the server already had, is matched by its page, not sent twice`() {
        val result = plan(listOf(row("b1", pageIndex = 7)), listOf(remote("r9", place = "8", title = "Page 8")))

        assertTrue(result.uploads.isEmpty())
        assertEquals("r9", result.writes.single().row.remoteId)
    }

    @Test
    fun `a bookmark whose answer was lost before its note went up keeps its note, however long ago it was made`() {
        val madeOffline = row("b1", pageIndex = 7, title = "the ship", updated = now - 6 * 60 * 60_000).copy(note = "read again")
        val result = plan(listOf(madeOffline), listOf(remote("r9", place = "8", title = "the ship", updated = now - 60_000)))

        val update = result.uploads.single() as BookmarkUpload.Update
        assertEquals("r9", update.remoteId)
        assertEquals("read again", update.note)
        assertEquals("read again", result.writes.single().row.note)
    }

    @Test
    fun `a bookmark made on the same page on both sides keeps the server's note when this one has none`() {
        val madeHere = row("b1", pageIndex = 7)
        val result = plan(listOf(madeHere), listOf(remote("r9", place = "8", title = "Page 8", note = "from the web")))

        assertTrue(result.uploads.isEmpty())
        assertEquals("from the web", result.writes.single().row.note)
        assertEquals(HIGHLIGHT_IN_STEP, result.writes.single().row.syncState)
    }

    @Test
    fun `removed on the server is removed here, and removed here is removed there`() {
        val inStep = row("b1", pageIndex = 7, remoteId = "r1", syncState = HIGHLIGHT_IN_STEP)
        assertEquals(listOf("b1"), plan(listOf(inStep), emptyList()).purges.map(BookmarkRowPurge::id))

        val removedHere = inStep.copy(deleted = true, syncState = HIGHLIGHT_WAITING)
        val delete = plan(listOf(removedHere), listOf(remote("r1", place = "8", updated = now - 60_000))).uploads.single()
        assertEquals("r1", (delete as BookmarkUpload.Delete).remoteId)
    }

    @Test
    fun `a page bookmark removed here before it ever went up isn't matched to a new one on that page`() {
        val removedUnsent = row("b1", pageIndex = 7).copy(deleted = true)
        val result = plan(listOf(removedUnsent), listOf(remote("r2", place = "8")))

        assertEquals("r2", result.writes.single().row.remoteId)
        assertEquals(listOf("b1"), result.purges.map(BookmarkRowPurge::id))
    }

    @Test
    fun `a page book leaves a CFI on the server alone, and an EPUB a page number`() {
        val cfi = "epubcfi(/6/4!/4/2,/1:0,/1:5)"
        val pageBook = plan(listOf(row("b1", pageIndex = 2)), listOf(remote("r1", place = cfi)))
        assertTrue(pageBook.writes.isEmpty())
        assertEquals("3", (pageBook.uploads.single() as BookmarkUpload.Create).place)

        assertTrue(plan(emptyList(), listOf(remote("r2", place = "3")), places = BookmarkPlaces.EpubCfi).writes.isEmpty())
    }

    @Test
    fun `a server copy stamped years ahead doesn't win over a name changed here`() {
        val renamed = row("b1", pageIndex = 7, remoteId = "r1", title = "the ship", updated = now - 60_000)
        val yearsAhead = now + 400 * FUTURE_CLOCK_LIMIT_MILLIS
        val result = plan(listOf(renamed), listOf(remote("r1", place = "8", title = "Page 8", updated = yearsAhead)))

        assertEquals("the ship", (result.uploads.single() as BookmarkUpload.Update).title)
    }

    @Test
    fun `a change here stamped years ahead counts as made now, and the server's later copy isn't stored with that stamp`() {
        val stampedAhead = row("b1", pageIndex = 7, remoteId = "r1", title = "here", updated = now + 400 * FUTURE_CLOCK_LIMIT_MILLIS)
        val serverLater = plan(listOf(stampedAhead), listOf(remote("r1", place = "8", title = "there", updated = now + 10 * 60_000)))

        val taken = serverLater.writes.single().row
        assertEquals("there", taken.title)
        assertEquals(now + 10 * 60_000, taken.updatedAtEpochMillis)
    }

    private fun remote(id: String, place: String, title: String? = null, note: String? = null, updated: Long = now) =
        RemoteBookmark(
            remoteId = id,
            place = place,
            title = title,
            note = note,
            createdAtEpochMillis = updated,
            updatedAtEpochMillis = updated,
        )

    @Suppress("LongParameterList")
    private fun row(
        id: String,
        pageIndex: Int,
        remoteId: String? = null,
        title: String? = null,
        syncState: String = HIGHLIGHT_WAITING,
        updated: Long = now,
    ) = ReaderBookmarkEntity(
        id = id,
        itemId = book.itemId,
        bookTitle = book.title,
        bookAuthor = book.author,
        href = "page:$pageIndex",
        locatorJson = "{\"page\":$pageIndex}",
        chapterTitle = null,
        progression = pageIndex / 40.0,
        page = pageIndex + 1,
        pageCount = 41,
        text = null,
        createdAtEpochMillis = updated,
        updatedAtEpochMillis = updated,
        deleted = false,
        syncState = syncState,
        remoteId = remoteId,
        remoteCfi = null,
        title = title,
    )
}
