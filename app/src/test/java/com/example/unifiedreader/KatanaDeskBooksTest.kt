package com.example.unifiedreader

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.network.buildHttpClient
import com.example.unifiedreader.feature.annotations.HighlightServer
import com.example.unifiedreader.feature.annotations.HighlightServerAnswer
import com.example.unifiedreader.feature.annotations.HighlightUpload
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KatanaDeskBooksTest {
    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()
    private val bookBytes = Random(7).nextBytes(300_000)
    private lateinit var server: MockWebServer
    private val requests: Int get() = server.requestCount
    private lateinit var bookFile: File

    @Before
    fun startDesk() {
        bookFile = Files.createTempFile("desk-book", ".epub").toFile().apply { writeBytes(bookBytes) }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path != "/api/v1/books/7/content" -> MockResponse().setResponseCode(404)
                request.getHeader("Authorization") != "Bearer token-1" -> MockResponse().setResponseCode(401)
                else -> MockResponse().setHeader("ETag", "\"v1\"").setBody(Buffer().write(bookBytes))
            }
        }
        server.start()
    }

    @After
    fun stopDesk() {
        server.shutdown()
        bookFile.delete()
    }

    private val desk get() = KatanaDeskPaired(address = "127.0.0.1:${server.port}", token = "token-1", deskId = "d-1", name = "PC")

    private fun reading(md5: String? = fingerprint(bookFile).partialMd5) = KatanaDeskReading(
        bookId = "7",
        title = "Sync Test Novel",
        author = null,
        formatName = "epub",
        sizeBytes = bookBytes.size.toLong(),
        partialMd5 = md5,
        fileName = "Sync: Test Novel?.epub",
        grimmoryId = null,
    )

    private fun bringer(documents: FakeDocuments, onGrimmory: Boolean = false, grimmoryDownloads: Boolean = false) = KatanaDeskBookBringer(
        context = context,
        httpClient = buildHttpClient(),
        documents = documents,
        fingerprints = FileFingerprints(),
        fromGrimmory = { if (onGrimmory) grimmoryDownloads else null },
        syncsWithGrimmory = { false },
    )

    @Test
    fun `a book the PC is reading and this device hasn't comes into Local and is linked`() = runBlocking {
        val documents = FakeDocuments()
        val links = mutableMapOf<String, String>()
        val seen = mutableListOf<Long>()
        val brought = bringer(documents).bring(desk, reading(), links, { seen += it }) { item, book -> links[item] = book }
        assertTrue(brought is KatanaDeskBringing.Arrived)
        // how much came over is told as it comes, ending with the whole file
        assertEquals(bookBytes.size.toLong(), seen.last())
        val item = documents.items.single()
        assertEquals(mapOf(item.id to "7"), links)
        val file = fileOfUri(documents.uris.getValue(item.id))!!
        assertEquals(File(context.filesDir, KATANA_DESK_BOOKS_DIR).canonicalFile, file.canonicalFile.parentFile)
        assertEquals("Sync_ Test Novel_.epub", file.name)
        assertTrue(file.readBytes().contentEquals(bookBytes))
        assertEquals(file.canonicalFile, katanaDeskDownloadAt(context.filesDir, documents.uris.getValue(item.id))?.canonicalFile)
    }

    @Test
    fun `the same file here already is linked, not downloaded`() = runBlocking {
        val documents = FakeDocuments()
        val here = documents.add(bookFile)
        val links = mutableMapOf<String, String>()
        val brought = bringer(documents).bring(desk, reading(), links, {}) { item, book -> links[item] = book }
        assertEquals(KatanaDeskBringing.AlreadyHere(here.id, here.title), brought)
        assertEquals(mapOf(here.id to "7"), links)
        assertEquals(0, requests)
        // linked before: nothing to do
        val again = bringer(documents).bring(desk, reading(), links, {}) { _, _ -> error("linked twice") }
        assertTrue(again is KatanaDeskBringing.AlreadyHere)
    }

    @Test
    fun `a file that comes damaged isn't kept`() = runBlocking {
        val documents = FakeDocuments()
        try {
            bringer(documents).bring(desk, reading(md5 = "00000000000000000000000000000000"), mutableMapOf(), {}) { _, _ -> }
            error("a damaged file was kept")
        } catch (damaged: java.io.IOException) {
            assertTrue(damaged.message!!.contains("damaged"))
        }
        assertTrue(documents.items.isEmpty())
        assertFalse(File(context.filesDir, KATANA_DESK_BOOKS_DIR).listFiles().orEmpty().any())
    }

    @Test
    fun `a Grimmory book on Grimmory here comes through Grimmory, and a kind that doesn't sync stays`() = runBlocking {
        val documents = FakeDocuments()
        val onGrimmory = bringer(documents, onGrimmory = true)
            .bring(desk, reading().copy(grimmoryId = "812"), mutableMapOf(), {}) { _, _ -> }
        assertEquals(KatanaDeskBringing.OnGrimmory("Sync Test Novel"), onGrimmory)
        // not on this device yet: Grimmory's own download of it starts
        val coming = bringer(documents, onGrimmory = true, grimmoryDownloads = true)
            .bring(desk, reading().copy(grimmoryId = "812"), mutableMapOf(), {}) { _, _ -> }
        assertEquals(KatanaDeskBringing.OnGrimmory("Sync Test Novel", downloading = true), coming)
        val mobi = bringer(documents).bring(desk, reading().copy(formatName = "mobi"), mutableMapOf(), {}) { _, _ -> }
        assertEquals(KatanaDeskBringing.NotSynced("Sync Test Novel", "MOBI"), mobi)
        assertEquals(0, requests)
    }

    @Test
    fun `file names from the PC are made safe`() {
        assertEquals("A_B_C.epub", safeBookFileName("A/B\\C.epub", "x", FormatType.EPUB))
        assertEquals("Title.pdf", safeBookFileName(null, "Title", FormatType.PDF))
        assertEquals("book.cbz", safeBookFileName("...", "", FormatType.CBZ))
        assertEquals("Novel.EPUB.epub".length, safeBookFileName("Novel.EPUB.epub", "x", FormatType.EPUB).length)
        assertEquals(120 + ".epub".length, safeBookFileName("x".repeat(300) + ".epub", "x", FormatType.EPUB).length)
    }

    @Test
    fun `a name already taken gets a number`() {
        val folder = Files.createTempDirectory("desk-names").toFile()
        try {
            assertEquals("a.epub", uniqueFileIn(folder, "a.epub").name)
            File(folder, "a.epub").writeText("1")
            File(folder, "a (2).epub").writeText("2")
            assertEquals("a (3).epub", uniqueFileIn(folder, "a.epub").name)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `only the app's own desk downloads count as such`() {
        val inside = File(File(context.filesDir, KATANA_DESK_BOOKS_DIR), "b.epub")
        assertEquals(inside.canonicalFile, katanaDeskDownloadAt(context.filesDir, inside.toURI().toString())?.canonicalFile)
        assertNull(katanaDeskDownloadAt(context.filesDir, File(context.filesDir, "b.epub").toURI().toString()))
        assertNull(katanaDeskDownloadAt(context.filesDir, "content://com.android.providers/document/1"))
        val outside = File(File(context.filesDir, KATANA_DESK_BOOKS_DIR), "../x.epub")
        assertNull(katanaDeskDownloadAt(context.filesDir, outside.toURI().toString()))
        // Android's own form of a file address
        val android = fileOfUri("file:///data/user/0/app/files/katana-desk-books/a%20b%20(2).epub")
        assertEquals("/data/user/0/app/files/katana-desk-books/a b (2).epub", android?.invariantSeparatorsPath?.substringAfter(':'))
    }

    @Test
    fun `a sync counts the marks that reached the desk`() = runBlocking {
        val tally = KatanaDeskTally()
        val server = CountingHighlightServer(
            object : HighlightServer {
                override suspend fun create(upload: HighlightUpload.Create) = HighlightServerAnswer.Saved("1")

                override suspend fun update(upload: HighlightUpload.Update) = HighlightServerAnswer.Missing

                override suspend fun delete(upload: HighlightUpload.Delete) = true
            },
            tally,
        )
        server.delete(HighlightUpload.Delete(localId = "l", expectedUpdatedAt = 0L, remoteId = "r"))
        assertEquals(1, tally.marksSent)
        val result = tally.result(books = 2, placesHere = 0, brought = null, problem = null)
        assertTrue(result.changed)
        assertFalse(KatanaDeskSyncResult(books = 2).changed)
        assertTrue(KatanaDeskSyncResult(brought = KatanaDeskBringing.Arrived("i", "T")).changed)
        assertNotNull(broughtText(KatanaDeskBringing.Arrived("i", "T")))
        assertNull(broughtText(KatanaDeskBringing.NothingRead))
    }

    private fun fingerprint(file: File): LocalFileFingerprint = RandomAccessFile(file, "r").use { fingerprintOf(it.channel) }

    /** Fingerprints of `file:` addresses, read straight from the file. */
    private class FileFingerprints : LocalFileFingerprinter {
        override suspend fun of(uri: String): LocalFileFingerprint? {
            val file = fileOfUri(uri) ?: return null
            return RandomAccessFile(file, "r").use { fingerprintOf(it.channel) }
        }
    }

    /** The Local library: a book is whatever file is imported. */
    private class FakeDocuments : LocalDocumentDataSource {
        val items = mutableListOf<ReadableItem>()
        val uris = mutableMapOf<String, String>()
        private val state = MutableStateFlow<List<ReadableItem>>(emptyList())
        override val isHydratedFlow: StateFlow<Boolean> = MutableStateFlow(true)
        override val importedItemsFlow: StateFlow<List<ReadableItem>> = state

        fun add(file: File): ReadableItem {
            val item = ReadableItem(
                id = "document-${items.size}",
                sourceType = SourceType.LOCAL,
                libraryId = LOCAL_LIBRARY_ID,
                isLocalImport = true,
                title = file.nameWithoutExtension,
                format = FormatType.EPUB,
            )
            items += item
            uris[item.id] = file.toURI().toString()
            state.value = items.toList()
            return item
        }

        override fun getImportedItems(): List<ReadableItem> = items.toList()

        // the bringer hands over Android's own file address; its decoded path is the file on any system
        override suspend fun import(uri: Uri): ReadableItem = add(File(uri.path!!))

        override suspend fun removeById(itemId: String): Boolean = items.removeAll { it.id == itemId }

        override fun getPageCount(item: ReadableItem): Int = 0

        override fun getDocumentUri(item: ReadableItem): Uri? = uris[item.id]?.let(Uri::parse)
    }
}
