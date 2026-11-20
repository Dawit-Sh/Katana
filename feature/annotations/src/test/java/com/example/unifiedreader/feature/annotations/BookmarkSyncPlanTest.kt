package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkSyncPlanTest {
    private val book = AnnotatedBook("grimmory:item:7", "The Hole", "Hiroko Oyamada")
    private val cfi = "epubcfi(/6/14!/4/2,/8/1:10,/8/1:40)"
    private val now = 10_000_000L

    private fun plan(local: List<ReaderBookmarkEntity>, remote: List<RemoteBookmark>) =
        planBookmarkSync(book, local, remote, now, newId = { "new" })

    @Test
    fun `a bookmark made here goes up once its place is worked out, named by its chapter`() {
        val unplaced = row("b1", remoteCfi = null)
        assertTrue(plan(listOf(unplaced), emptyList()).uploads.isEmpty())

        val upload = plan(listOf(row("b1", remoteCfi = cfi)), emptyList()).uploads.single() as BookmarkUpload.Create
        assertEquals(cfi, upload.place)
        assertEquals("Chapter Two", upload.title)
    }

    @Test
    fun `a bookmark made on the server comes down unplaced, with its name and note`() {
        val write = plan(emptyList(), listOf(remote("r1", title = "the box", note = "mandarins"))).writes.single()

        assertNull(write.expectedUpdatedAt)
        assertEquals("", write.row.locatorJson)
        assertEquals(cfi, write.row.remoteCfi)
        assertEquals("the box", write.row.title)
        assertEquals("mandarins", write.row.note)
        assertEquals(HIGHLIGHT_IN_STEP, write.row.syncState)
    }

    @Test
    fun `removed on the server is removed here, and removed here is removed there`() {
        val inStep = row("b1", remoteId = "r1", remoteCfi = cfi, syncState = HIGHLIGHT_IN_STEP)
        assertEquals(listOf("b1"), plan(listOf(inStep), emptyList()).purges.map(BookmarkRowPurge::id))

        val removedHere = inStep.copy(deleted = true, syncState = HIGHLIGHT_WAITING, updatedAtEpochMillis = now)
        val delete = plan(listOf(removedHere), listOf(remote("r1", updated = now - 60_000))).uploads.single()
        assertEquals("r1", (delete as BookmarkUpload.Delete).remoteId)
    }

    @Test
    fun `a name or note changed here goes up, unless the server's copy is newer by more than the margin`() {
        val renamed = row("b1", remoteId = "r1", remoteCfi = cfi, title = "the box")
        val update = plan(listOf(renamed), listOf(remote("r1", title = "Chapter Two", updated = now - 60_000))).uploads.single()
        assertEquals("the box", (update as BookmarkUpload.Update).title)

        val serverNewer = plan(listOf(renamed), listOf(remote("r1", title = "from the web", updated = now + 3 * 60_000)))
        assertTrue(serverNewer.uploads.isEmpty())
        assertEquals("from the web", serverNewer.writes.single().row.title)
    }

    @Test
    fun `a bookmark whose answer was lost is matched by its place, not sent twice`() {
        val sentBefore = row("b1", remoteCfi = cfi)
        val result = plan(listOf(sentBefore), listOf(remote("r9", title = "Chapter Two")))

        assertTrue(result.uploads.isEmpty())
        assertEquals("r9", result.writes.single().row.remoteId)
    }

    @Test
    fun `the chapter's name coming back from the server doesn't name the bookmark here`() {
        val inStep = row("b1", remoteId = "r1", remoteCfi = cfi, syncState = HIGHLIGHT_IN_STEP)
        val result = plan(listOf(inStep), listOf(remote("r1", title = "Chapter Two")))

        assertTrue(result.writes.isEmpty() || result.writes.single().row.title == null)
        assertTrue(result.uploads.isEmpty())
    }

    private fun remote(id: String, title: String? = null, note: String? = null, updated: Long = now) =
        RemoteBookmark(
            remoteId = id,
            place = cfi,
            title = title,
            note = note,
            createdAtEpochMillis = updated,
            updatedAtEpochMillis = updated,
        )

    private fun row(
        id: String,
        remoteId: String? = null,
        remoteCfi: String?,
        title: String? = null,
        syncState: String = HIGHLIGHT_WAITING,
    ) = ReaderBookmarkEntity(
        id = id,
        itemId = book.itemId,
        bookTitle = book.title,
        bookAuthor = book.author,
        href = "OEBPS/c2.xhtml",
        locatorJson = """{"href":"OEBPS/c2.xhtml"}""",
        chapterTitle = "Chapter Two",
        progression = 0.1,
        page = 7,
        pageCount = 78,
        text = "they came by with a box",
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
        deleted = false,
        syncState = syncState,
        remoteId = remoteId,
        remoteCfi = remoteCfi,
        title = title,
    )
}
