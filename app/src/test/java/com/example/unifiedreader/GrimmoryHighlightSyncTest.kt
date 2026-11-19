package com.example.unifiedreader

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.Library
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceConnectivityState
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotation
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotationChange
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotationCreate
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotationWrite
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotationsApi
import com.example.unifiedreader.data.grimmory.GrimmoryApi
import com.example.unifiedreader.data.grimmory.GrimmorySession
import com.example.unifiedreader.data.grimmory.GrimmoryUnauthorizedException
import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.AnnotationPlace
import com.example.unifiedreader.feature.annotations.HighlightLook
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.RoomHighlightPlacementStore
import com.example.unifiedreader.feature.annotations.RoomHighlightSyncStore
import com.example.unifiedreader.feature.annotations.RoomReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.withPlacement
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Highlight sync with Grimmory: how many requests, through the saved sign-in, and what a failure leaves. */
@RunWith(RobolectricTestRunner::class)
class GrimmoryHighlightSyncTest {
    private lateinit var database: UnifiedReaderDatabase
    private lateinit var annotations: ReaderAnnotationStore
    private lateinit var syncStore: RoomHighlightSyncStore
    private val credentials = FakeGrimmoryCredentialStore(
        GrimmoryStoredSession(
            displayName = "Grimmory",
            baseUrl = "http://grimmory.lan",
            username = "reader",
            accessToken = "access",
            refreshToken = "refresh",
        ),
    )
    private val connectivity = RemoteSourceConnectivityPolicy()
    private val server = FakeAnnotations()
    private val api = FakeGrimmory(server)
    private lateinit var sync: GrimmoryHighlightSync

    private val hole = grimmoryBook("88", "The Hole")
    private val other = grimmoryBook("90", "Another")
    private val place = AnnotationPlace("OEBPS/c1.xhtml", "{\"href\":\"OEBPS/c1.xhtml\"}", "One", 0.1)
    private val cfi = "epubcfi(/6/8!/4/2,/1:0,/1:4)"

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
        ).withPlacement(RoomHighlightPlacementStore(dao = database.readerHighlightPlacementDao(), ioDispatcher = Dispatchers.Unconfined))
        syncStore = RoomHighlightSyncStore(database, Dispatchers.Unconfined)
        sync = GrimmoryHighlightSync(
            grimmoryApiFactory = GrimmoryApiFactory { RenewingGrimmoryApi(api, GrimmoryTokenRenewals(credentials)) },
            credentialStore = credentials,
            store = syncStore,
            connectivity = connectivity,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `after a library sync every book's highlights come down in one request`() = runBlocking {
        server.add(bookId = "88", note = "from the web")
        server.add(bookId = "90", note = "from KOReader")

        assertTrue(sync.syncLibrary(listOf(hole, other), syncStore.booksOnServer()))

        assertEquals(listOf("all"), server.requests)
        assertEquals("from the web", annotations.highlights(hole.id).first().single().note)
        assertEquals("from KOReader", annotations.highlights(other.id).first().single().note)
        assertEquals(SourceConnectivityState.Online, connectivity.reachabilityFor(SourceType.GRIMMORY))
    }

    @Test
    fun `a library sync sends what waits and asks a book's own list only when it may already hold it`() = runBlocking {
        val made = annotations.addHighlight(hole.book(), place, "Once", HighlightLook())
        annotations.placement.setServerPlace(made.id, cfi)

        sync.syncLibrary(listOf(hole, other), syncStore.booksOnServer())
        assertEquals(listOf("all", "create 88"), server.requests)

        // Its answer lost: the server has it, this device doesn't know under which id.
        server.requests.clear()
        val again = annotations.addHighlight(other.book(), place, "Twice", HighlightLook())
        annotations.placement.setServerPlace(again.id, cfi)
        server.add(bookId = "90", cfi = cfi)
        sync.syncLibrary(listOf(hole, other), syncStore.booksOnServer())

        assertEquals(listOf("all", "book 90"), server.requests)
        assertEquals(1, server.highlights.count { it.bookId == "90" })
    }

    @Test
    fun `an opened book's highlights come down and its changes go up`() = runBlocking {
        server.add(bookId = "88", note = "web")
        val made = annotations.addHighlight(hole.book(), place, "Once", HighlightLook())
        annotations.placement.setServerPlace(made.id, "epubcfi(/6/10!/4,/1:0,/1:4)")

        assertTrue(sync.syncBooks(listOf(hole)))

        assertEquals(listOf("book 88", "create 88"), server.requests)
        assertEquals(2, annotations.highlights(hole.id).first().size)
    }

    @Test
    fun `an expired sign-in is renewed through the one renewal path and the sync goes on`() = runBlocking {
        api.expiredTokens += "access"
        server.add(bookId = "88")

        assertTrue(sync.syncBooks(listOf(hole)))

        assertEquals(listOf("refresh"), api.renewed)
        assertEquals("access-2", credentials.sessionFlow.value?.accessToken)
        assertEquals(1, annotations.highlights(hole.id).first().size)
    }

    @Test
    fun `without the network nothing is lost and Grimmory shows offline`() = runBlocking {
        val made = annotations.addHighlight(hole.book(), place, "Once", HighlightLook())
        annotations.placement.setServerPlace(made.id, cfi)
        server.offline = true

        assertFalse(sync.syncBooks(listOf(hole)))

        assertEquals(SourceConnectivityState.Offline, connectivity.reachabilityFor(SourceType.GRIMMORY))
        assertEquals(listOf(made.id), syncStore.observeWaiting().first().map { it.id })
    }

    @Test
    fun `books that aren't Grimmory EPUBs keep their highlights on this device`() = runBlocking {
        val mobi = grimmoryBook("91", "Converted").copy(format = FormatType.MOBI)
        val local = hole.copy(id = "local:1", sourceType = SourceType.LOCAL, remoteId = null)

        assertTrue(sync.syncBooks(listOf(mobi, local)))

        assertTrue(server.requests.isEmpty())
        assertFalse(mobi.syncsHighlightsWithGrimmory())
        assertTrue(hole.syncsHighlightsWithGrimmory())
    }

    private fun grimmoryBook(bookId: String, title: String) = ReadableItem(
        id = "grimmory:item:$bookId",
        sourceType = SourceType.GRIMMORY,
        libraryId = "grimmory:library:1",
        remoteId = bookId,
        title = title,
        format = FormatType.EPUB,
    )

    private fun ReadableItem.book() = AnnotatedBook(id, title, null)

    private class FakeAnnotations : GrimmoryAnnotationsApi {
        val highlights = mutableListOf<GrimmoryAnnotation>()
        val requests = mutableListOf<String>()
        var offline = false
        private var nextId = 1

        fun add(
            bookId: String,
            note: String? = null,
            cfi: String = "epubcfi(/6/4!/4,/1:${nextId},/1:${nextId + 1})",
            color: String = "#FACC15",
        ) {
            highlights += GrimmoryAnnotation(
                id = (nextId++).toString(),
                bookId = bookId,
                cfi = cfi,
                text = "Words",
                color = color,
                style = "highlight",
                note = note,
                chapterTitle = "One",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
            )
        }

        override suspend fun bookHighlights(accessToken: String, bookId: String): List<GrimmoryAnnotation> {
            reach("book $bookId")
            return highlights.filter { it.bookId == bookId }
        }

        override suspend fun allHighlights(accessToken: String): List<GrimmoryAnnotation> {
            reach("all")
            return highlights.map { it.copy(cfi = null) }
        }

        override suspend fun create(accessToken: String, highlight: GrimmoryAnnotationCreate): GrimmoryAnnotationWrite {
            reach("create ${highlight.bookId}")
            if (highlights.any { it.bookId == highlight.bookId && it.cfi == highlight.cfi }) return GrimmoryAnnotationWrite.AlreadyThere
            add(highlight.bookId, highlight.note, highlight.cfi, highlight.color)
            return GrimmoryAnnotationWrite.Saved(highlights.last())
        }

        override suspend fun update(accessToken: String, annotationId: String, change: GrimmoryAnnotationChange): GrimmoryAnnotationWrite {
            reach("update $annotationId")
            return GrimmoryAnnotationWrite.Saved(highlights.first { it.id == annotationId })
        }

        override suspend fun delete(accessToken: String, annotationId: String): Boolean {
            reach("delete $annotationId")
            return highlights.removeIf { it.id == annotationId }
        }

        private fun reach(request: String) {
            if (offline) throw IOException("Software caused connection abort")
            requests += request
        }
    }

    private class FakeGrimmory(private val server: FakeAnnotations) : GrimmoryApi {
        val expiredTokens = mutableSetOf<String>()
        val renewed = mutableListOf<String>()

        override val annotations: GrimmoryAnnotationsApi = object : GrimmoryAnnotationsApi by server {
            override suspend fun bookHighlights(accessToken: String, bookId: String): List<GrimmoryAnnotation> {
                if (accessToken in expiredTokens) throw GrimmoryUnauthorizedException()
                return server.bookHighlights(accessToken, bookId)
            }
        }

        override suspend fun refreshSession(refreshToken: String): GrimmorySession {
            renewed += refreshToken
            return GrimmorySession(accessToken = "access-2", refreshToken = "refresh-2")
        }

        override suspend fun login(username: String, password: String): GrimmorySession = error("not used")

        override suspend fun getLibraries(accessToken: String): List<Library> = emptyList()

        override suspend fun getBooks(accessToken: String, libraryId: String): List<ReadableItem> = emptyList()

        override fun bookContentUrl(bookId: String, bookType: String?): String = ""

        override fun bookCoverUrl(bookId: String): String = ""
    }
}
