package com.example.unifiedreader.feature.downloads

import android.net.Uri
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeptBooksTest {
    private val zone = ZoneOffset.UTC
    private val now = at(2026, 11, 18, 20, 0)
    private lateinit var folder: File
    private lateinit var files: KeptBookFiles
    private val stored = mutableMapOf<String, File>()
    private val extras = mutableMapOf<String, File>()

    @Before
    fun setUp() {
        folder = Files.createTempDirectory("kept-books").toFile()
        files = KeptBookFiles(
            dataSources = listOf(FakeDataSource(stored)),
            extraFiles = { item -> listOfNotNull(extras[item.id]) },
        )
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    @Test
    fun `only books with a file here are listed, measured with their reader copy, latest read first`() {
        stored["hole"] = file("hole.epub", 1_500_000)
        extras["hole"] = file("hole-cache.epub", 600_000)
        stored["suspect"] = file("suspect.epub", 1_600_000)
        val candidates = listOf(
            candidate("suspect", openedAt = at(2026, 11, 16, 9, 0)),
            candidate("hole", openedAt = at(2026, 11, 18, 19, 0)),
            candidate("gone", openedAt = at(2026, 11, 18, 19, 30)),
            candidate("downloaded", openedAt = null, isDownload = true),
        )
        stored["downloaded"] = file("downloaded.epub", 100)

        val books = files.measure(candidates)

        assertEquals(listOf("hole", "suspect", "downloaded"), books.map { it.item.id })
        assertEquals(2_100_000L, books.first().sizeBytes)
        assertEquals(3_700_100L, KeptBooksSnapshot(books, isLoaded = true).totalBytes)
    }

    @Test
    fun `remove deletes the stored copy and the reader copy`() = runBlocking {
        stored["hole"] = file("hole.epub", 10)
        extras["hole"] = file("hole-cache.epub", 10)

        assertTrue(files.delete(item("hole")))

        assertFalse(stored.getValue("hole").exists())
        assertFalse(extras.getValue("hole").exists())
        assertFalse(files.delete(item("hole")))
    }

    @Test
    fun `a row says its size, when it was opened and how far`() {
        assertEquals(
            "2.1 MB · opened today · 9% read",
            kept(2_100_000, opened = at(2026, 11, 18, 8, 0), percent = 9).sheetLine(now, zone),
        )
        assertEquals(
            "4.8 MB · opened yesterday · finished",
            kept(4_800_000, opened = at(2026, 11, 17, 8, 0), percent = 100, finished = true).sheetLine(now, zone),
        )
        assertEquals(
            "1.6 MB · opened 16 Nov · just started",
            kept(1_600_000, opened = at(2026, 11, 16, 8, 0), percent = 0).sheetLine(now, zone),
        )
        assertEquals(
            "900 KB · downloaded, not opened yet",
            kept(900_000, opened = null, percent = null, download = true).sheetLine(now, zone),
        )
    }

    @Test
    fun `the summary counts the books and their size, once measured`() {
        val books = listOf(kept(2_100_000, opened = now, percent = 9), kept(6_400_000, opened = now, percent = 1))
        assertEquals("2 books on this tablet · 8.5 MB", keptBooksSummary(KeptBooksSnapshot(books, isLoaded = true)))
        assertEquals("1 book on this tablet · 2.1 MB", keptBooksSummary(KeptBooksSnapshot(books.take(1), isLoaded = true)))
        assertNull(keptBooksSummary(KeptBooksSnapshot(books, isLoaded = false)))
        assertNull(keptBooksSummary(KeptBooksSnapshot(emptyList(), isLoaded = true)))
    }

    @Test
    fun `the bar is the books' share of their space and what is free`() {
        val snapshot = KeptBooksSnapshot(listOf(kept(250, opened = now, percent = 1)), freeBytes = 750, isLoaded = true)
        assertEquals(0.25f, keptBooksShare(snapshot), 0.0001f)
        assertEquals(0f, keptBooksShare(KeptBooksSnapshot.Empty), 0f)
    }

    private fun file(name: String, size: Int): File = File(folder, name).apply { writeBytes(ByteArray(size)) }

    private fun item(id: String) = ReadableItem(
        id = id,
        sourceType = SourceType.GRIMMORY,
        libraryId = "grimmory:library:1",
        remoteId = id,
        title = id.replaceFirstChar(Char::uppercase),
        format = FormatType.EPUB,
    )

    private fun candidate(id: String, openedAt: Long?, isDownload: Boolean = false) = KeptBookCandidate(
        item = item(id),
        openedAtEpochMillis = openedAt,
        readPercent = openedAt?.let { 10 },
        isFinished = false,
        isDownload = isDownload,
    )

    private fun kept(
        size: Long,
        opened: Long?,
        percent: Int?,
        finished: Boolean = false,
        download: Boolean = false,
    ) = KeptBook(
        item = item("book$size"),
        sizeBytes = size,
        openedAtEpochMillis = opened,
        readPercent = percent,
        isFinished = finished,
        isDownload = download,
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(zone).toEpochMilli()

    private class FakeDataSource(private val stored: MutableMap<String, File>) : RemoteItemDownloadDataSource {
        override val sourceType: SourceType = SourceType.GRIMMORY

        override fun storedDocumentUri(item: ReadableItem): Uri? =
            stored[item.id]?.takeIf(File::exists)?.let(Uri::fromFile)

        override suspend fun downloadToLocalStorage(item: ReadableItem): Uri? = null

        override suspend fun storedDocumentItemIds(items: List<ReadableItem>): Set<String> =
            items.filter { storedDocumentUri(it) != null }.mapTo(mutableSetOf(), ReadableItem::id)

        override suspend fun deleteStoredDocument(item: ReadableItem): Boolean =
            stored[item.id]?.let { file -> file.exists() && file.delete() } == true
    }
}
