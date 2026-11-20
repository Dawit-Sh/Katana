package com.example.unifiedreader.feature.annotations

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.database.UnifiedReaderDatabase
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

/** Restoring the marks of books from files that came back under another id (another folder, a reinstall). */
@RunWith(RobolectricTestRunner::class)
class LocalBookRestoreTest {
    private lateinit var source: UnifiedReaderDatabase
    private lateinit var target: UnifiedReaderDatabase
    private var clock = 1_000L
    private var ids = 0

    private val place = AnnotationPlace("c1.xhtml", "{\"href\":\"c1.xhtml\"}", "Chapter 1", 0.07)
    private val epub = LocalBookIdentity("document-aaaa1111", "Pride and Prejudice", "Jane Austen", "EPUB", 831_932, "0f0f")
    private val pending = mutableListOf<LocalBookIdentity>()

    @Before
    fun setUp() {
        source = inMemoryDatabase()
        target = inMemoryDatabase()
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
    }

    @Test
    fun `a book picked again under another id gets its marks back by its file`() = runBlocking {
        markUp(epub)
        val file = backups(source, here = listOf(epub)).backUp()
        val again = epub.copy(itemId = "document-bbbb2222")

        val result = backups(target, here = listOf(again)).restore(file.text)

        assertEquals(setOf("document-bbbb2222"), targetItemIds())
        assertEquals(0, result.forBooksNotHere + result.forUnsureBooks)
        assertEquals("Added 1 highlight, 1 word and 1 bookmark", result.message)
    }

    @Test
    fun `the same file wins over another edition with the same title`() = runBlocking {
        markUp(epub)
        val file = backups(source, here = listOf(epub)).backUp()
        val azw3 = epub.copy(itemId = "document-cccc3333", format = "AZW3", fileSize = 974_161, partialMd5 = "1e1e")
        val sameFile = epub.copy(itemId = "document-dddd4444")

        backups(target, here = listOf(azw3, sameFile)).restore(file.text)

        assertEquals(setOf("document-dddd4444"), targetItemIds())
    }

    @Test
    fun `an older backup without fingerprints goes to the only book with that title and author`() = runBlocking {
        markUp(epub)
        val oldFile = backups(source, here = null).backUp()
        val again = epub.copy(itemId = "document-bbbb2222", fileSize = null, partialMd5 = null)

        val result = backups(target, here = listOf(again)).restore(oldFile.text)

        assertEquals(setOf("document-bbbb2222"), targetItemIds())
        assertEquals(0, result.forUnsureBooks)
    }

    @Test
    fun `with two books of that title and author here, an older backup's marks stay as they were and the restore says so`() =
        runBlocking {
            markUp(epub)
            val oldFile = backups(source, here = null).backUp()
            val editions = listOf(
                epub.copy(itemId = "document-bbbb2222"),
                epub.copy(itemId = "document-cccc3333", format = "AZW3", fileSize = 974_161, partialMd5 = "1e1e"),
            )

            val result = backups(target, here = editions).restore(oldFile.text)

            assertEquals(setOf("document-aaaa1111"), targetItemIds())
            assertEquals(3, result.forUnsureBooks)
            assertTrue(result.message, result.message.contains("3 are for a book this device has more than one copy of"))
        }

    @Test
    fun `a book whose file isn't here keeps its id, is counted, and waits for its import`() = runBlocking {
        markUp(epub)
        val file = backups(source, here = listOf(epub)).backUp()
        val otherBook = LocalBookIdentity("document-eeee5555", "Emma", "Jane Austen", "EPUB", 10, "abcd")

        val result = backups(target, here = listOf(otherBook)).restore(file.text)

        assertEquals(setOf("document-aaaa1111"), targetItemIds())
        assertEquals(3, result.forBooksNotHere)
        assertTrue(result.message, result.message.endsWith("3 are for books not on this device"))
        assertEquals(listOf(epub), pending)
    }

    @Test
    fun `server books are never moved`() = runBlocking {
        val server = LocalBookIdentity("grimmory:item:2694", "Pride and Prejudice", "Jane Austen", "EPUB")
        markUp(server)
        val file = backups(source, here = emptyList()).backUp()

        val result = backups(target, here = listOf(epub)).restore(file.text)

        assertEquals(setOf("grimmory:item:2694"), targetItemIds())
        assertEquals(0, result.forBooksNotHere + result.forUnsureBooks)
    }

    @Test
    fun `a book still here under its id keeps its marks, and restoring again adds nothing`() = runBlocking {
        markUp(epub)
        val file = backups(source, here = listOf(epub)).backUp()
        val restore = backups(target, here = listOf(epub, epub.copy(itemId = "document-ffff6666")))

        restore.restore(file.text)
        val again = restore.restore(file.text)

        assertEquals(setOf("document-aaaa1111"), targetItemIds())
        assertEquals("Nothing new: this device has it all", again.message)
    }

    @Test
    fun `a book's reading place comes back under its new id, and only a newer one replaces this device's`() = runBlocking {
        markUp(epub)
        val server = LocalReadingPlace("grimmory:item:2694", "CFI", cfi = "server", updatedAtEpochMillis = 500)
        val sourcePlaces = FakePlaces(mutableMapOf(epub.itemId to place(at = 2_000), server.itemId to server))
        val file = backups(source, here = listOf(epub), places = sourcePlaces).backUp()
        val again = epub.copy(itemId = "document-bbbb2222")

        val fresh = FakePlaces(mutableMapOf())
        val result = backups(target, here = listOf(again), places = fresh).restore(file.text)
        val newerHere = FakePlaces(mutableMapOf(again.itemId to place(at = 3_000).copy(itemId = again.itemId)))
        val third = inMemoryDatabase()
        val kept = try {
            backups(third, here = listOf(again), places = newerHere).restore(file.text)
        } finally {
            third.close()
        }

        assertEquals(listOf("document-bbbb2222"), fresh.places().map(LocalReadingPlace::itemId))
        assertEquals(42, fresh.places().single().page)
        assertEquals(1, result.restoredPlaces)
        assertTrue(result.message, result.message.contains("1 reading place"))
        assertEquals(0, kept.restoredPlaces)
        assertEquals(3_000L, newerHere.places().single().updatedAtEpochMillis)
        assertEquals(listOf(epub.itemId), decodeAnnotationBackup(file.text).places.map(BackupPlace::itemId))
    }

    @Test
    fun `a backed-up place is newer only when it is, and a clock far ahead counts as now`() {
        val now = 1_000_000_000_000L
        assertTrue(isNewerPlace(backupAt = 5, currentAt = null, now = now))
        assertTrue(isNewerPlace(backupAt = 6, currentAt = 5, now = now))
        assertTrue(!isNewerPlace(backupAt = 5, currentAt = 5, now = now))
        assertTrue(!isNewerPlace(backupAt = 4, currentAt = 5, now = now))
        assertTrue(!isNewerPlace(backupAt = now + 3 * 86_400_000L, currentAt = now + 1, now = now))
    }

    @Test
    fun `local ids are told from a server's`() {
        assertTrue(isLocalBookId("document-8054263d"))
        assertTrue(isLocalBookId("comic-5c210df4"))
        assertTrue(!isLocalBookId("grimmory:item:2694"))
        assertTrue(!isLocalBookId("kavita:12:34"))
        assertTrue(!isLocalBookId("document-not-hex"))
    }

    /** A highlight, a word and a bookmark in [book], made in the source database. */
    private suspend fun markUp(book: LocalBookIdentity) {
        val marks = RoomReaderAnnotationStore(
            dao = source.readerAnnotationDao(),
            bookmarkDao = source.readerBookmarkDao(),
            studyDao = source.savedWordStudyDao(),
            ioDispatcher = Dispatchers.Unconfined,
            now = { clock++ },
            newId = { "m${++ids}" },
        )
        val annotated = AnnotatedBook(book.itemId, book.title, book.author)
        marks.addHighlight(annotated, place, "a truth universally acknowledged", HighlightLook(), note = null)
        marks.saveWord(annotated, "entailed", "was entailed.", place)
        marks.addBookmark(annotated, place, page = 4, pageCount = 357, text = "It is a truth")
    }

    private suspend fun targetItemIds(): Set<String> {
        val highlights = target.readerAnnotationDao().observeAllHighlights().first().map { it.itemId }
        val words = target.readerAnnotationDao().observeAllWords().first().map { it.itemId }
        val bookmarks = target.readerBookmarkDao().observeAllBookmarks().first().map { it.itemId }
        return (highlights + words + bookmarks).toSet()
    }

    private fun backups(
        database: UnifiedReaderDatabase,
        here: List<LocalBookIdentity>?,
        places: FakePlaces? = null,
    ) = AnnotationBackupStore(
        dao = database.readerAnnotationDao(),
        bookmarkDao = database.readerBookmarkDao(),
        ioDispatcher = Dispatchers.Unconfined,
        now = { 1_763_424_000_000L },
        local = here?.let { books ->
            LocalBooksInBackup(
                directory = { books },
                pendingMarks = object : PendingLocalMarks {
                    override suspend fun remember(books: List<LocalBookIdentity>) {
                        pending += books
                    }

                    override suspend fun takeSameFile(file: LocalBookIdentity): List<LocalBookIdentity> = emptyList()
                },
                readingPlaces = places,
            )
        },
    )

    private fun place(at: Long) =
        LocalReadingPlace(epub.itemId, "CFI", page = 42, cfi = "{\"href\":\"c1.xhtml\"}", updatedAtEpochMillis = at)

    /** Places by book, restored the way the app does it: only a newer one replaces what is here. */
    private class FakePlaces(private val byItem: MutableMap<String, LocalReadingPlace>) : LocalReadingPlaces {
        override suspend fun places(): List<LocalReadingPlace> = byItem.values.toList()

        override suspend fun restore(place: LocalReadingPlace): Boolean {
            val current = byItem[place.itemId]
            if (!isNewerPlace(place.updatedAtEpochMillis, current?.updatedAtEpochMillis, now = 1_763_424_000_000L)) return false
            byItem[place.itemId] = place
            return true
        }
    }

    private fun inMemoryDatabase(): UnifiedReaderDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        UnifiedReaderDatabase::class.java,
    ).allowMainThreadQueries().build()
}
