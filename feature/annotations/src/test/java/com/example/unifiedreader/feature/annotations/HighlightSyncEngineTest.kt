package com.example.unifiedreader.feature.annotations

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_IN_STEP
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_ON_DEVICE
import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The waiting changes on this device and what a sync does with them, against a fake server. */
@RunWith(RobolectricTestRunner::class)
class HighlightSyncEngineTest {
    private lateinit var database: UnifiedReaderDatabase
    private lateinit var annotations: ReaderAnnotationStore
    private lateinit var syncStore: RoomHighlightSyncStore
    private lateinit var engine: HighlightSyncEngine
    private var clock = 1_800_000_000_000L
    private var ids = 0

    private val hole = AnnotatedBook("grimmory:item:88", "The Hole", "Hiroko Oyamada")
    private val place = AnnotationPlace("OEBPS/c1.xhtml", "{\"href\":\"OEBPS/c1.xhtml\"}", "One", 0.1)
    private val cfi = "epubcfi(/6/8!/4/2,/1:0,/1:4)"

    private class FakeServer : HighlightServer {
        val highlights = linkedMapOf<String, RemoteHighlight>()
        val requests = mutableListOf<String>()
        var offline = false
        var nextId = 100

        fun list(): List<RemoteHighlight> = highlights.values.toList()

        override suspend fun create(upload: HighlightUpload.Create): HighlightServerAnswer {
            reach("create ${upload.localId}")
            if (highlights.values.any { it.cfi == upload.cfi }) return HighlightServerAnswer.AlreadyThere
            val id = (nextId++).toString()
            highlights[id] = RemoteHighlight(
                id, upload.cfi, upload.text, upload.note, upload.color, upload.style, upload.chapterTitle, 1L, 1L,
            )
            return HighlightServerAnswer.Saved(id)
        }

        override suspend fun update(upload: HighlightUpload.Update): HighlightServerAnswer {
            reach("update ${upload.remoteId}")
            val stored = highlights[upload.remoteId] ?: return HighlightServerAnswer.Missing
            highlights[upload.remoteId] = stored.copy(note = upload.note, color = upload.color, style = upload.style)
            return HighlightServerAnswer.Saved(upload.remoteId)
        }

        override suspend fun delete(upload: HighlightUpload.Delete): Boolean {
            reach("delete ${upload.remoteId}")
            return highlights.remove(upload.remoteId) != null
        }

        private fun reach(request: String) {
            if (offline) throw IOException("Software caused connection abort")
            requests += request
        }
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnifiedReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = database.readerAnnotationDao()
        annotations = RoomReaderAnnotationStore(
            dao = dao,
            bookmarkDao = database.readerBookmarkDao(),
            studyDao = database.savedWordStudyDao(),
            ioDispatcher = Dispatchers.Unconfined,
            now = { clock },
            newId = { "h${++ids}" },
        ).withPlacement(RoomHighlightPlacementStore(dao = database.readerHighlightPlacementDao(), ioDispatcher = Dispatchers.Unconfined))
        syncStore = RoomHighlightSyncStore(database, Dispatchers.Unconfined)
        engine = HighlightSyncEngine(syncStore, now = { clock }, newId = { "s${++ids}" })
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun waiting() = syncStore.observeWaiting().first()

    private suspend fun sync(server: FakeServer) = engine.syncBook(hole, server.list(), server)

    @Test
    fun `a new highlight waits for its place the server's way, then goes up and is in step`() = runBlocking {
        val server = FakeServer()
        val made = annotations.addHighlight(hole, place, "Once", HighlightLook(), note = "a note")
        assertTrue("without its place it can't go yet", waiting().isEmpty())
        assertEquals(listOf(made.id), annotations.placement.highlightsToPlace(hole.itemId).first().map(HighlightToPlace::id))

        annotations.placement.setServerPlace(made.id, cfi)
        assertEquals(listOf(made.id), waiting().map { it.id })
        assertTrue(annotations.placement.highlightsToPlace(hole.itemId).first().isEmpty())
        sync(server)

        assertEquals(listOf("create ${made.id}"), server.requests)
        assertEquals("a note", server.list().single().note)
        assertTrue(waiting().isEmpty())
        val row = syncStore.rowsOf(hole.itemId).single()
        assertEquals(HIGHLIGHT_IN_STEP, row.syncState)
        assertEquals("100", row.remoteId)
    }

    @Test
    fun `changes made offline wait, nothing lost, and go up at the next sync`() = runBlocking {
        val server = FakeServer()
        val made = annotations.addHighlight(hole, place, "Once", HighlightLook())
        annotations.placement.setServerPlace(made.id, cfi)
        sync(server)

        server.offline = true
        clock += 60_000
        annotations.updateHighlight(made.id, color = HighlightColor.Pink, note = "offline note")
        try {
            sync(server)
            fail("the server can't be reached")
        } catch (_: IOException) {
            // As the network fails.
        }
        assertEquals(listOf(made.id), waiting().map { it.id })
        assertEquals("offline note", annotations.highlights(hole.itemId).first().single().note)

        server.offline = false
        sync(server)

        assertEquals(HighlightColor.Pink, server.list().single().color)
        assertEquals("offline note", server.list().single().note)
        assertTrue(waiting().isEmpty())
    }

    @Test
    fun `a change made while its upload is on the way stays waiting and goes next`() = runBlocking {
        val made = annotations.addHighlight(hole, place, "Once", HighlightLook())
        annotations.placement.setServerPlace(made.id, cfi)
        val server = object : HighlightServer by FakeServer() {
            override suspend fun create(upload: HighlightUpload.Create): HighlightServerAnswer {
                clock += 1_000
                annotations.updateHighlight(made.id, note = "typed meanwhile")
                return HighlightServerAnswer.Saved("100")
            }
        }

        engine.syncBook(hole, emptyList(), server)

        val row = syncStore.rowsOf(hole.itemId).single()
        assertEquals("100", row.remoteId)
        assertEquals(HIGHLIGHT_WAITING, row.syncState)
        assertEquals(listOf(made.id), waiting().map { it.id })
    }

    @Test
    fun `a new server takes the highlights as new and nothing reads as removed there`() = runBlocking {
        val first = FakeServer()
        val kept = annotations.addHighlight(hole, place, "Once", HighlightLook(), note = "kept")
        annotations.placement.setServerPlace(kept.id, cfi)
        val gone = annotations.addHighlight(hole, place.copy(progression = 0.2), "Twice", HighlightLook())
        annotations.placement.setServerPlace(gone.id, "epubcfi(/6/8!/4/4,/1:0,/1:5)")
        sync(first)
        annotations.deleteHighlight(gone.id)

        // Another desk paired: the first desk's ids mean nothing there.
        syncStore.newServer(hole.itemId)
        val second = FakeServer().apply { nextId = 500 }
        sync(second)

        assertEquals(listOf("create ${kept.id}"), second.requests)
        assertEquals("kept", second.list().single().note)
        val row = syncStore.rowsOf(hole.itemId).single()
        assertEquals("500", row.remoteId)
        assertEquals(HIGHLIGHT_IN_STEP, row.syncState)
        assertEquals(1, annotations.highlights(hole.itemId).first().size)
    }

    @Test
    fun `a highlight removed here is removed on the server, then gone for good`() = runBlocking {
        val server = FakeServer()
        val made = annotations.addHighlight(hole, place, "Once", HighlightLook())
        annotations.placement.setServerPlace(made.id, cfi)
        sync(server)

        annotations.deleteHighlight(made.id)
        sync(server)

        assertTrue(server.list().isEmpty())
        assertTrue(syncStore.rowsOf(hole.itemId).isEmpty())
    }

    @Test
    fun `highlights from the server come down once, however often the book syncs`() = runBlocking {
        val server = FakeServer()
        server.highlights["7"] =
            RemoteHighlight("7", cfi, "Once", "from the web", HighlightColor.Green, HighlightStyle.Underline, "One", 1L, 2L)

        sync(server)
        sync(server)
        sync(server)

        val row = syncStore.rowsOf(hole.itemId).single()
        assertEquals("7", row.remoteId)
        assertEquals("from the web", row.note)
        assertTrue(server.requests.isEmpty())
        // Not yet placed in the book: the reader finds it there, and lists show it meanwhile.
        assertEquals(cfi, annotations.placement.highlightsToPlace(hole.itemId).first().single().serverPlace)
        assertEquals(1, annotations.highlights(hole.itemId).first().size)

        annotations.placement.place(row.id, place)
        assertTrue(annotations.placement.highlightsToPlace(hole.itemId).first().isEmpty())
        assertEquals(place.locatorJson, annotations.highlights(hole.itemId).first().single().place.locatorJson)
    }

    @Test
    fun `a sync doesn't undo a place the reader worked out meanwhile`() = runBlocking {
        val server = FakeServer()
        server.highlights["7"] = RemoteHighlight("7", cfi, "Once", null, HighlightColor.Yellow, HighlightStyle.Highlight, "One", 1L, 2L)
        sync(server)
        val id = syncStore.rowsOf(hole.itemId).single().id
        // The reader places it after the sync read the highlights and before it writes the server's change.
        val racing = object : HighlightSyncStore by syncStore {
            override suspend fun apply(
                writes: List<com.example.unifiedreader.core.database.entity.HighlightRowWrite>,
                purges: List<com.example.unifiedreader.core.database.entity.HighlightRowPurge>,
            ) {
                annotations.placement.place(id, place)
                syncStore.apply(writes, purges)
            }
        }

        server.highlights["7"] = server.highlights.getValue("7").copy(note = "changed on the web", updatedAtEpochMillis = 3L)
        HighlightSyncEngine(racing, now = { clock }).syncBook(hole, server.list(), server)

        val shown = annotations.highlights(hole.itemId).first().single()
        assertEquals("changed on the web", shown.note)
        assertEquals(place.locatorJson, shown.place.locatorJson)
    }

    @Test
    fun `a highlight the server took again from someone else is matched, not doubled`() = runBlocking {
        val server = FakeServer()
        val made = annotations.addHighlight(hole, place, "Once", HighlightLook())
        annotations.placement.setServerPlace(made.id, cfi)
        // Someone made one at the same place after this device read the server's list.
        val stale = server.list()
        server.highlights["50"] = RemoteHighlight("50", cfi, "Once", null, HighlightColor.Yellow, HighlightStyle.Highlight, null, 1L, 1L)

        assertEquals(HighlightBookSync.ServerChangedMeanwhile, engine.syncBook(hole, stale, server))
        assertEquals(HighlightBookSync.Done, sync(server))

        assertEquals(1, server.list().size)
        assertEquals("50", syncStore.rowsOf(hole.itemId).single().remoteId)
    }

    @Test
    fun `a new highlight whose place can't be put the server's way stays on this device`() = runBlocking {
        val made = annotations.addHighlight(hole, place, "Once", HighlightLook())

        annotations.placement.keepOnDevice(made.id)

        assertEquals(HIGHLIGHT_ON_DEVICE, syncStore.rowsOf(hole.itemId).single().syncState)
        assertTrue(annotations.placement.highlightsToPlace(hole.itemId).first().isEmpty())
        assertTrue(waiting().isEmpty())
        assertNull(syncStore.rowsOf(hole.itemId).single().remoteCfi)
    }

    @Test
    fun `the books with highlights on the server or waiting for it are known`() = runBlocking {
        val server = FakeServer()
        val sent = annotations.addHighlight(hole, place, "Once", HighlightLook())
        annotations.placement.setServerPlace(sent.id, cfi)
        sync(server)
        annotations.addHighlight(AnnotatedBook("local:1", "A file", null), place, "Never sent", HighlightLook())

        assertEquals(setOf(hole.itemId), syncStore.booksOnServer())
    }
}
