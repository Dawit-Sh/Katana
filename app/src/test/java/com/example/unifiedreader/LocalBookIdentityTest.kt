package com.example.unifiedreader

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.model.BookMetadata
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.PartialMd5
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.feature.annotations.LocalBookIdentity
import com.example.unifiedreader.feature.annotations.LocalBookMarksMover
import com.example.unifiedreader.feature.annotations.PendingLocalMarks
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Books from files known by their files: one book per file, and a restore's waiting marks finding theirs. */
@RunWith(RobolectricTestRunner::class)
class LocalBookIdentityTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val calculus = LocalFileFingerprint(sizeBytes = 3_800_000, partialMd5 = "c0ffee")
    private val documents = PickedFiles()
    private val released = mutableListOf<Uri>()

    private fun imports(
        files: Map<String, LocalFileFingerprint?>,
        homing: LocalMarksHoming? = null,
    ) = LocalBookImports(
        documents = documents,
        hydrator = documents,
        fingerprints = { uri -> files[uri] },
        marksHoming = homing,
        releaseRead = { uri -> released += uri },
    )

    @Test
    fun `the same file picked again from another folder opens the book already here, not a second copy`() = runBlocking {
        val first = Uri.parse("content://storage/document/primary%3ADownload%2FCalculus.pdf")
        val again = Uri.parse("content://downloads/document/msf%3A28")
        val books = imports(mapOf(first.toString() to calculus, again.toString() to calculus))

        val imported = books.import(first)
        val importedAgain = books.import(again)

        assertEquals(imported?.id, importedAgain?.id)
        assertEquals(1, documents.getImportedItems().size)
        assertEquals("the second address's permission is given back", listOf(again), released)
    }

    @Test
    fun `another file under the same name is another book, and an unreadable file is never taken for one here`() =
        runBlocking {
            val one = Uri.parse("content://storage/Notes.pdf")
            val other = Uri.parse("content://downloads/Notes.pdf")
            val unreadable = Uri.parse("content://cloud/Notes.pdf")
            val books = imports(
                mapOf(
                    one.toString() to calculus,
                    other.toString() to calculus.copy(partialMd5 = "beef"),
                    unreadable.toString() to null,
                ),
            )

            val first = books.import(one)
            val second = books.import(other)
            val third = books.import(unreadable)

            assertNotEquals(first?.id, second?.id)
            assertNotEquals(first?.id, third?.id)
            assertEquals(3, documents.getImportedItems().size)
            assertTrue(released.isEmpty())
        }

    @Test
    fun `the books from files are listed with their files' fingerprints`() = runBlocking {
        val uri = Uri.parse("content://storage/Calculus.pdf")
        val books = imports(mapOf(uri.toString() to calculus))
        val imported = requireNotNull(books.import(uri))

        assertEquals(
            listOf(LocalBookIdentity(imported.id, "Calculus", "Strang", "PDF", calculus.sizeBytes, calculus.partialMd5)),
            books.localBooks(),
        )
    }

    @Test
    fun `importing a file a restore was waiting for gives it that book's marks, once`() = runBlocking {
        val moved = mutableListOf<Pair<String, String>>()
        val pending = InMemoryPendingMarks()
        pending.remember(listOf(waiting("document-aa01", calculus), waiting("document-bb02", calculus.copy(sizeBytes = 1))))
        val uri = Uri.parse("content://storage/Calculus.pdf")
        val books = imports(
            mapOf(uri.toString() to calculus),
            LocalMarksHoming(pending, LocalBookMarksMover { from, to -> moved += from to to; 3 }),
        )

        val imported = requireNotNull(books.import(uri))
        books.import(uri)

        assertEquals(listOf("document-aa01" to imported.id), moved)
        assertEquals(listOf("document-bb02"), pending.books.map(LocalBookIdentity::itemId))
    }

    @Test
    fun `a file's fingerprint is KOReader's partial MD5 of it and its size`() {
        val file = File(Files.createTempDirectory("local-books").toFile(), "Big.pdf")
        file.writeBytes(ByteArray(70_000) { index -> (index % 251).toByte() })
        file.deleteOnExit()

        val fingerprint = RandomAccessFile(file, "r").use { fingerprintOf(it.channel) }

        val expected = PartialMd5.of(70_000L) { start, length ->
            RandomAccessFile(file, "r").use { it.seek(start); ByteArray(length).also(it::readFully) }
        }
        assertEquals(LocalFileFingerprint(70_000L, expected), fingerprint)
    }

    @Test
    fun `waiting books survive being written and read back, and are handed over once`() = runBlocking {
        val store = PendingLocalMarksStore(context, Dispatchers.Unconfined)
        val book = waiting("document-1", LocalFileFingerprint(10, "abc")).copy(author = null)
        val other = waiting("comic-2", LocalFileFingerprint(20, "def"))
        store.remember(listOf(book, other))

        assertEquals(listOf(book), store.takeSameFile(book.copy(itemId = "document-9")))
        assertTrue(store.takeSameFile(book).isEmpty())
        assertEquals(listOf(other), decodePendingBooks(encodePendingBooks(listOf(other))))
        assertTrue(decodePendingBooks("not json").isEmpty())
    }

    private fun waiting(itemId: String, fingerprint: LocalFileFingerprint) = LocalBookIdentity(
        itemId = itemId,
        title = "Calculus",
        author = "Strang",
        format = "PDF",
        fileSize = fingerprint.sizeBytes,
        partialMd5 = fingerprint.partialMd5,
    )
}

/** A Local library where every picked address becomes its own book, as the real one does. */
private class PickedFiles : LocalDocumentDataSource, LocalDocumentHydrator {
    private val items = MutableStateFlow<List<ReadableItem>>(emptyList())
    private val uris = mutableMapOf<String, Uri>()

    override val isHydratedFlow: StateFlow<Boolean> = MutableStateFlow(true)
    override val importedItemsFlow: StateFlow<List<ReadableItem>> = items

    override fun getImportedItems(): List<ReadableItem> = items.value

    override suspend fun hydrate() = Unit

    override suspend fun import(uri: Uri): ReadableItem {
        val id = "document-" + uri.toString().hashCode().toUInt().toString(16)
        val item = ReadableItem(
            id = id,
            sourceType = SourceType.LOCAL,
            libraryId = LOCAL_LIBRARY_ID,
            isLocalImport = true,
            title = "Calculus",
            format = FormatType.PDF,
            bookMetadata = BookMetadata(author = "Strang"),
        )
        uris[id] = uri
        items.value = items.value.filterNot { it.id == id } + item
        return item
    }

    override suspend fun removeById(itemId: String): Boolean = items.value.any { it.id == itemId }.also {
        items.value = items.value.filterNot { it.id == itemId }
    }

    override fun getPageCount(item: ReadableItem): Int = 0

    override fun getDocumentUri(item: ReadableItem): Uri? = uris[item.id]
}

private class InMemoryPendingMarks : PendingLocalMarks {
    val books = mutableListOf<LocalBookIdentity>()

    override suspend fun remember(books: List<LocalBookIdentity>) {
        this.books += books
    }

    override suspend fun takeSameFile(file: LocalBookIdentity): List<LocalBookIdentity> =
        books.filter { it.sameFileAs(file) }.also { books -= it.toSet() }
}
