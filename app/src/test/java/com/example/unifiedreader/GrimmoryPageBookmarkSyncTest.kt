package com.example.unifiedreader

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.Library
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotation
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotationChange
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotationCreate
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotationWrite
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotationsApi
import com.example.unifiedreader.data.grimmory.GrimmoryApi
import com.example.unifiedreader.data.grimmory.GrimmoryBookmark
import com.example.unifiedreader.data.grimmory.GrimmoryBookmarkPlace
import com.example.unifiedreader.data.grimmory.GrimmoryBookmarkWrite
import com.example.unifiedreader.data.grimmory.GrimmoryBookmarksApi
import com.example.unifiedreader.data.grimmory.GrimmorySession
import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.MarkRemovalHolds
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.RoomBookmarkSyncStore
import com.example.unifiedreader.feature.annotations.RoomHighlightSyncStore
import com.example.unifiedreader.feature.annotations.RoomReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.pageBookmarkPlace
import com.example.unifiedreader.feature.annotations.settleMarkRemovalHold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Page bookmarks of Grimmory's PDFs and comics go up and come down through the same sync as an EPUB's. */
@RunWith(RobolectricTestRunner::class)
class GrimmoryPageBookmarkSyncTest {
    private lateinit var database: UnifiedReaderDatabase
    private lateinit var annotations: ReaderAnnotationStore
    private lateinit var syncStore: RoomBookmarkSyncStore
    private lateinit var sync: GrimmoryBookmarkSync
    private val server = FakeBookmarks()
    private val credentials = FakeGrimmoryCredentialStore(
        GrimmoryStoredSession(
            displayName = "Grimmory",
            baseUrl = "http://grimmory.lan",
            username = "reader",
            accessToken = "access",
            refreshToken = "refresh",
        ),
    )
    private val pdf = grimmoryBook("35", FormatType.PDF)
    private val comic = grimmoryBook("36", FormatType.CBZ)

    @Before
    fun setUp() {
        MarkRemovalHolds.clear()
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UnifiedReaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        annotations = RoomReaderAnnotationStore(
            dao = database.readerAnnotationDao(),
            bookmarkDao = database.readerBookmarkDao(),
            studyDao = database.savedWordStudyDao(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        syncStore = RoomBookmarkSyncStore(
            dao = database.readerBookmarkSyncDao(),
            bookmarks = database.readerBookmarkDao(),
            runInTransaction = { block -> database.runInTransaction(Runnable(block)) },
            ioDispatcher = Dispatchers.Unconfined,
        )
        sync = GrimmoryBookmarkSync(
            grimmoryApiFactory = GrimmoryApiFactory { FakeGrimmory(server) },
            credentialStore = credentials,
            store = syncStore,
            connectivity = RemoteSourceConnectivityPolicy(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a PDF page bookmarked here waits at once, then goes up as its page number`() = runBlocking {
        annotations.addBookmark(pdf.book(), pageBookmarkPlace(pageIndex = 7, pageCount = 41), page = 8, pageCount = 41, text = null)
        assertEquals(listOf(pdf.id), syncStore.observeWaiting().first().map { it.itemId })
        assertEquals(setOf(pdf.id), syncStore.booksOnServer())

        assertTrue(sync.syncBooks(listOf(pdf)))

        assertEquals(GrimmoryBookmarkPlace.PdfPage(8), server.created.single())
        assertTrue(syncStore.observeWaiting().first().isEmpty())
    }

    @Test
    fun `a comic page goes up written in the CFI, and the server's comes down on its page`() = runBlocking {
        annotations.addBookmark(comic.book(), pageBookmarkPlace(pageIndex = 2, pageCount = 20), page = 3, pageCount = 20, text = null)
        server.bookmarks += serverBookmark("90", "36", cfi = "15", pageNumber = null, notes = "fight")

        assertTrue(sync.syncBooks(listOf(comic)))

        assertEquals(GrimmoryBookmarkPlace.Cfi("3"), server.created.single())
        val fromServer = annotations.bookmarks(comic.id).first().single { it.note == "fight" }
        assertEquals("page:14", fromServer.place.href)
        assertEquals(15, fromServer.page)
    }

    @Test
    fun `a page bookmark the server already has is matched, not made twice, and removed there it goes here`() = runBlocking {
        annotations.addBookmark(pdf.book(), pageBookmarkPlace(pageIndex = 7, pageCount = 41), page = 8, pageCount = 41, text = null)
        server.bookmarks += serverBookmark("91", "35", cfi = null, pageNumber = 8, notes = null)

        assertTrue(sync.syncBooks(listOf(pdf)))
        assertTrue(server.created.isEmpty())
        assertEquals(1, annotations.bookmarks(pdf.id).first().size)

        server.bookmarks.clear()
        assertTrue(sync.syncBooks(listOf(pdf)))
        assertTrue(annotations.bookmarks(pdf.id).first().isEmpty())
    }

    @Test
    fun `a server that lost a book's six bookmarks is asked about, and Put back sends them again`() = runBlocking {
        bookmarkSixPages()
        assertTrue(sync.syncBooks(listOf(pdf)))
        server.bookmarks.clear()
        server.created.clear()

        assertTrue(sync.syncBooks(listOf(pdf)))
        assertEquals("nothing removed here", 6, annotations.bookmarks(pdf.id).first().size)
        val hold = MarkRemovalHolds.current(pdf.id)!!
        assertEquals(6, hold.count)

        val highlights = RoomHighlightSyncStore(database, Dispatchers.Unconfined)
        settleMarkRemovalHold(hold, removeHere = false, highlights = highlights, bookmarks = syncStore)
        assertTrue(sync.syncBooks(listOf(pdf)))

        assertEquals(6, server.created.size)
        assertEquals(6, annotations.bookmarks(pdf.id).first().size)
        assertEquals(null, MarkRemovalHolds.current(pdf.id))
    }

    @Test
    fun `Remove here removes the bookmarks the server lost`() = runBlocking {
        bookmarkSixPages()
        assertTrue(sync.syncBooks(listOf(pdf)))
        server.bookmarks.clear()
        assertTrue(sync.syncBooks(listOf(pdf)))

        settleMarkRemovalHold(
            MarkRemovalHolds.current(pdf.id)!!,
            removeHere = true,
            highlights = RoomHighlightSyncStore(database, Dispatchers.Unconfined),
            bookmarks = syncStore,
        )

        assertTrue(annotations.bookmarks(pdf.id).first().isEmpty())
    }

    private suspend fun bookmarkSixPages() = repeat(6) { page ->
        annotations.addBookmark(pdf.book(), pageBookmarkPlace(page, 41), page = page + 1, pageCount = 41, text = null)
    }

    private fun serverBookmark(id: String, bookId: String, cfi: String?, pageNumber: Int?, notes: String?) =
        GrimmoryBookmark(id, bookId, cfi, pageNumber, title = "Page", notes = notes, createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    private fun grimmoryBook(bookId: String, format: FormatType) = ReadableItem(
        id = "grimmory:item:$bookId",
        sourceType = SourceType.GRIMMORY,
        libraryId = "grimmory:library:1",
        remoteId = bookId,
        title = "Book $bookId",
        format = format,
        pageCount = 41,
    )

    private fun ReadableItem.book() = AnnotatedBook(id, title, null)

    private class FakeBookmarks : GrimmoryBookmarksApi {
        val bookmarks = mutableListOf<GrimmoryBookmark>()
        val created = mutableListOf<GrimmoryBookmarkPlace>()
        private var nextId = 1

        override suspend fun bookBookmarks(accessToken: String, bookId: String) = bookmarks.filter { it.bookId == bookId }

        override suspend fun allBookmarks(accessToken: String) = bookmarks.map { it.copy(cfi = null, pageNumber = null) }

        override suspend fun create(
            accessToken: String,
            bookId: String,
            place: GrimmoryBookmarkPlace,
            title: String?,
        ): GrimmoryBookmarkWrite {
            created += place
            val saved = GrimmoryBookmark(
                id = "n${nextId++}",
                bookId = bookId,
                cfi = (place as? GrimmoryBookmarkPlace.Cfi)?.cfi,
                pageNumber = (place as? GrimmoryBookmarkPlace.PdfPage)?.pageNumber,
                title = title,
                notes = null,
                createdAtEpochMillis = 2L,
                updatedAtEpochMillis = 2L,
            )
            bookmarks += saved
            return GrimmoryBookmarkWrite.Saved(saved)
        }

        override suspend fun update(accessToken: String, bookmarkId: String, title: String, notes: String): GrimmoryBookmarkWrite =
            GrimmoryBookmarkWrite.Saved(bookmarks.first { it.id == bookmarkId })

        override suspend fun delete(accessToken: String, bookmarkId: String): Boolean = bookmarks.removeIf { it.id == bookmarkId }
    }

    private class FakeGrimmory(bookmarks: GrimmoryBookmarksApi) : GrimmoryApi {
        override val annotations: GrimmoryAnnotationsApi = object : GrimmoryAnnotationsApi {
            override val bookmarks: GrimmoryBookmarksApi = bookmarks

            override suspend fun bookHighlights(accessToken: String, bookId: String): List<GrimmoryAnnotation> = emptyList()

            override suspend fun allHighlights(accessToken: String): List<GrimmoryAnnotation> = emptyList()

            override suspend fun create(accessToken: String, highlight: GrimmoryAnnotationCreate): GrimmoryAnnotationWrite =
                error("not used")

            override suspend fun update(
                accessToken: String,
                annotationId: String,
                change: GrimmoryAnnotationChange,
            ): GrimmoryAnnotationWrite = error("not used")

            override suspend fun delete(accessToken: String, annotationId: String): Boolean = error("not used")
        }

        override suspend fun refreshSession(refreshToken: String): GrimmorySession = error("not used")

        override suspend fun login(username: String, password: String): GrimmorySession = error("not used")

        override suspend fun getLibraries(accessToken: String): List<Library> = emptyList()

        override suspend fun getBooks(accessToken: String, libraryId: String): List<ReadableItem> = emptyList()

        override fun bookContentUrl(bookId: String, bookType: String?): String = ""

        override fun bookCoverUrl(bookId: String): String = ""
    }
}
