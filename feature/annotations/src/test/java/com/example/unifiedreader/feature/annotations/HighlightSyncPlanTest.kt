package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_ON_DEVICE
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.HighlightRowPurge
import com.example.unifiedreader.core.database.entity.ReaderHighlightEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of a book's highlight sync: who wins, what is removed, what goes up and what waits. */
class HighlightSyncPlanTest {
    private val hole = AnnotatedBook("grimmory:item:88", "The Hole", "Hiroko Oyamada")
    private val minute = 60_000L
    private val noon = 1_800_000_000_000L
    private var ids = 0

    private fun plan(local: List<ReaderHighlightEntity>, remote: List<RemoteHighlight>) =
        planHighlightSync(hole, local, remote, nowEpochMillis = noon, newId = { "new${++ids}" })

    private fun row(
        id: String = "h1",
        state: String = HIGHLIGHT_IN_STEP,
        remoteId: String? = "7",
        cfi: String? = "epubcfi(/6/8!/4/2,/1:0,/1:4)",
    ) = ReaderHighlightEntity(
        id = id,
        itemId = hole.itemId,
        bookTitle = hole.title,
        bookAuthor = hole.author,
        href = "OEBPS/c1.xhtml",
        locatorJson = "{\"href\":\"OEBPS/c1.xhtml\"}",
        text = "Once",
        chapterTitle = "One",
        progression = 0.1,
        style = HighlightStyle.Highlight.name,
        color = HighlightColor.Yellow.name,
        note = null,
        createdAtEpochMillis = noon - 60 * minute,
        updatedAtEpochMillis = noon,
        deleted = false,
        syncState = state,
        remoteId = remoteId,
        remoteCfi = cfi,
    )

    private fun server(
        id: String = "7",
        cfi: String? = "epubcfi(/6/8!/4/2,/1:0,/1:4)",
        note: String? = null,
        color: HighlightColor = HighlightColor.Yellow,
        updatedAt: Long? = noon,
    ) = RemoteHighlight(
        remoteId = id,
        cfi = cfi,
        text = "Once",
        note = note,
        color = color,
        style = HighlightStyle.Highlight,
        chapterTitle = "One",
        createdAtEpochMillis = noon - 60 * minute,
        updatedAtEpochMillis = updatedAt,
    )

    @Test
    fun `a highlight new on the server comes down in step, to be placed in the book when it opens`() {
        val result = plan(local = emptyList(), remote = listOf(server(id = "9", note = "from the web")))

        val made = result.writes.single()
        assertEquals(null, made.expectedUpdatedAt)
        assertEquals("9", made.row.remoteId)
        assertEquals("from the web", made.row.note)
        assertEquals(HIGHLIGHT_IN_STEP, made.row.syncState)
        assertEquals("", made.row.locatorJson)
        assertTrue(result.uploads.isEmpty())
    }

    @Test
    fun `nothing changes when both sides are the same`() {
        val result = plan(local = listOf(row()), remote = listOf(server()))

        assertTrue(result.writes.isEmpty() && result.purges.isEmpty() && result.uploads.isEmpty())
    }

    @Test
    fun `a highlight in step takes the server's change`() {
        val result = plan(
            local = listOf(row()),
            remote = listOf(server(note = "added in KOReader", color = HighlightColor.Green, updatedAt = noon + minute)),
        )

        val written = result.writes.single()
        assertEquals("added in KOReader", written.row.note)
        assertEquals(HighlightColor.Green.name, written.row.color)
        assertEquals(noon, written.expectedUpdatedAt)
        assertTrue(result.uploads.isEmpty())
    }

    @Test
    fun `a new highlight made here goes up once its place the server's way is known`() {
        val placed = row(id = "a", state = HIGHLIGHT_WAITING, remoteId = null)
        val unplaced = row(id = "b", state = HIGHLIGHT_WAITING, remoteId = null, cfi = null)

        val result = plan(local = listOf(placed, unplaced), remote = emptyList())

        val create = result.uploads.single() as HighlightUpload.Create
        assertEquals("a", create.localId)
        assertEquals("epubcfi(/6/8!/4/2,/1:0,/1:4)", create.cfi)
        assertTrue(result.writes.isEmpty() && result.purges.isEmpty())
    }

    @Test
    fun `a change made here goes up when the server's copy isn't newer`() {
        val changed = row(state = HIGHLIGHT_WAITING).copy(note = "my note", updatedAtEpochMillis = noon + 10 * minute)

        val result = plan(local = listOf(changed), remote = listOf(server(updatedAt = noon)))

        val update = result.uploads.single() as HighlightUpload.Update
        assertEquals("7", update.remoteId)
        assertEquals("my note", update.note)
        assertTrue(result.writes.isEmpty())
    }

    // The server stamps a change with its own clock, which may run a little ahead of this device's.
    @Test
    fun `a change made here wins over a server copy newer by less than the margin`() {
        val changed = row(state = HIGHLIGHT_WAITING).copy(note = "mine", updatedAtEpochMillis = noon)

        val result = plan(
            local = listOf(changed),
            remote = listOf(server(note = "theirs", updatedAt = noon + HIGHLIGHT_CLOCK_MARGIN_MILLIS)),
        )

        assertTrue(result.uploads.single() is HighlightUpload.Update)
    }

    @Test
    fun `a later change on the server wins over an older one waiting here`() {
        val changed = row(state = HIGHLIGHT_WAITING).copy(note = "written offline", updatedAtEpochMillis = noon)

        val result = plan(
            local = listOf(changed),
            remote = listOf(server(note = "written later on the web", updatedAt = noon + 30 * minute)),
        )

        assertTrue(result.uploads.isEmpty())
        val written = result.writes.single().row
        assertEquals("written later on the web", written.note)
        assertEquals(HIGHLIGHT_IN_STEP, written.syncState)
    }

    @Test
    fun `a highlight removed here is removed on the server`() {
        val removed = row(state = HIGHLIGHT_WAITING).copy(deleted = true, updatedAtEpochMillis = noon + minute)

        val result = plan(local = listOf(removed), remote = listOf(server()))

        val delete = result.uploads.single() as HighlightUpload.Delete
        assertEquals("7", delete.remoteId)
    }

    @Test
    fun `a highlight removed here but changed on the server later comes back`() {
        val removed = row(state = HIGHLIGHT_WAITING).copy(deleted = true, updatedAtEpochMillis = noon)

        val result = plan(local = listOf(removed), remote = listOf(server(note = "kept on the web", updatedAt = noon + 30 * minute)))

        assertTrue(result.uploads.isEmpty())
        val restored = result.writes.single().row
        assertFalse(restored.deleted)
        assertEquals("kept on the web", restored.note)
    }

    @Test
    fun `a highlight removed on the server is removed here, and one removed on both sides goes`() {
        val inStep = row(id = "a", remoteId = "7")
        val removedHere = row(id = "b", remoteId = "8", state = HIGHLIGHT_WAITING).copy(deleted = true)
        val neverSent = row(id = "c", remoteId = null, state = HIGHLIGHT_WAITING).copy(deleted = true)

        val result = plan(local = listOf(inStep, removedHere, neverSent), remote = emptyList())

        assertEquals(listOf("a", "b", "c"), result.purges.map(HighlightRowPurge::id))
        assertTrue(result.uploads.isEmpty())
    }

    // A note typed here offline isn't lost because the highlight went elsewhere: it goes back up.
    @Test
    fun `a highlight changed here after the server removed it goes up again`() {
        val changed = row(state = HIGHLIGHT_WAITING, remoteId = "7").copy(note = "my note")

        val result = plan(local = listOf(changed), remote = emptyList())

        assertEquals(null, result.writes.single().row.remoteId)
        assertEquals("h1", (result.uploads.single() as HighlightUpload.Create).localId)
    }

    // The answer to a highlight sent from here was lost; the server made it all the same.
    @Test
    fun `a highlight the server made without this device hearing back is matched by its place`() {
        val sent = row(state = HIGHLIGHT_WAITING, remoteId = null)

        val result = plan(local = listOf(sent), remote = listOf(server(id = "12")))

        assertTrue(result.uploads.isEmpty())
        val linked = result.writes.single().row
        assertEquals("12", linked.remoteId)
        assertEquals(HIGHLIGHT_IN_STEP, linked.syncState)
    }

    // KOReader sends a changed highlight as a new one in the same place.
    @Test
    fun `a highlight made again elsewhere under a new id stays one highlight`() {
        val result = plan(
            local = listOf(row(remoteId = "7")),
            remote = listOf(server(id = "15", note = "changed in KOReader", updatedAt = noon + minute)),
        )

        assertTrue(result.purges.isEmpty())
        val relinked = result.writes.single().row
        assertEquals("h1", relinked.id)
        assertEquals("15", relinked.remoteId)
        assertEquals("changed in KOReader", relinked.note)
    }

    // The web reader asserts element ids in a CFI, the KOReader plugin doesn't.
    @Test
    fun `the same place written by another reader is the same highlight`() {
        val mine = row(state = HIGHLIGHT_WAITING, remoteId = null, cfi = "epubcfi(/6/14!/4/2[chapter]/4,/1:116,/1:196)")

        val result = plan(local = listOf(mine), remote = listOf(server(id = "2", cfi = "epubcfi(/6/14!/4/2/4,/1:116,/1:196)")))

        assertTrue(result.uploads.isEmpty())
        assertEquals("2", result.writes.single().row.remoteId)
    }

    @Test
    fun `the same words highlighted in the same chapter elsewhere are the same highlight`() {
        val mine = row(state = HIGHLIGHT_WAITING, remoteId = null, cfi = "epubcfi(/6/14!/4/2/4,/1:118,/1:198)")

        val sameChapter = plan(local = listOf(mine), remote = listOf(server(id = "2", cfi = "epubcfi(/6/14!/4/2/4,/1:116,/1:196)")))
        val otherChapter = plan(local = listOf(mine), remote = listOf(server(id = "2", cfi = "epubcfi(/6/16!/4/2/4,/1:116,/1:196)")))

        assertEquals("2", sameChapter.writes.single().row.remoteId)
        assertTrue(sameChapter.uploads.isEmpty())
        assertTrue(otherChapter.uploads.single() is HighlightUpload.Create)
    }

    @Test
    fun `a server highlight with the words of one made here and not yet placed waits for that one to be placed`() {
        val unplaced = row(state = HIGHLIGHT_WAITING, remoteId = null, cfi = null)

        val result = plan(local = listOf(unplaced), remote = listOf(server(id = "2")))

        assertTrue(result.writes.isEmpty() && result.uploads.isEmpty())
    }

    @Test
    fun `a highlight removed here before it reached the server doesn't remove someone else's at its place`() {
        val removed = row(state = HIGHLIGHT_WAITING, remoteId = null).copy(deleted = true)

        val result = plan(local = listOf(removed), remote = listOf(server(id = "2")))

        assertTrue(result.uploads.isEmpty())
        assertEquals(listOf("h1"), result.purges.map(HighlightRowPurge::id))
        assertEquals("2", result.writes.single().row.remoteId)
    }

    @Test
    fun `from the list without places, a book that may hold one of these under another id asks for its own list`() {
        val sentWithoutAnswer = row(state = HIGHLIGHT_WAITING, remoteId = null)

        val result = plan(local = listOf(sentWithoutAnswer), remote = listOf(server(id = "12", cfi = null)))

        assertTrue(result.needsPlaces)
        assertTrue(result.writes.isEmpty() && result.uploads.isEmpty())
    }

    @Test
    fun `from the list without places, changes and removals still apply`() {
        val inStep = row(id = "a", remoteId = "7")
        val gone = row(id = "b", remoteId = "8")

        val result = plan(
            local = listOf(inStep, gone),
            remote = listOf(server(id = "7", cfi = null, note = "n", updatedAt = noon + minute)),
        )

        assertFalse(result.needsPlaces)
        assertEquals("n", result.writes.single().row.note)
        assertEquals("epubcfi(/6/8!/4/2,/1:0,/1:4)", result.writes.single().row.remoteCfi)
        assertEquals(listOf("b"), result.purges.map(HighlightRowPurge::id))
    }

    @Test
    fun `a highlight kept on this device is left alone`() {
        val local = row(state = HIGHLIGHT_ON_DEVICE, remoteId = null, cfi = null)

        val result = plan(local = listOf(local), remote = emptyList())

        assertTrue(result.writes.isEmpty() && result.purges.isEmpty() && result.uploads.isEmpty())
    }

    @Test
    fun `colours go up as the web reader's and come down by name or nearest hue`() {
        assertEquals("#FACC15", HighlightColor.Yellow.toServerHex())
        HighlightColor.entries.forEach { color -> assertEquals(color, highlightColorFromServer(color.toServerHex())) }
        assertEquals(HighlightColor.Yellow, highlightColorFromServer("#FFC107"))
        assertEquals(HighlightColor.Blue, highlightColorFromServer("#0248F8"))
        assertEquals(HighlightColor.Pink, highlightColorFromServer("#DC143C"))
        assertEquals(HighlightColor.Green, highlightColorFromServer("#00AA00"))
        assertEquals(HighlightColor.Yellow, highlightColorFromServer("#777777"))
        assertEquals(HighlightColor.Yellow, highlightColorFromServer("not a colour"))
        assertEquals(HighlightStyle.Squiggly, highlightStyleFromServer("SQUIGGLY"))
        assertEquals(HighlightStyle.Highlight, highlightStyleFromServer(null))
        assertEquals("strikethrough", HighlightStyle.Strikethrough.serverName)
    }
}
