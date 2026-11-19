package com.example.unifiedreader

import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.remoteLibraryId
import com.example.unifiedreader.core.model.remoteItemId
import com.example.unifiedreader.core.network.PartialDownloadFile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteDocumentDownloadRepositoryTest {
    @Test
    fun `download invalidates stored document cache entry`() = runBlocking {
        val invalidatedItemIds = mutableListOf<String>()
        val cacheInvalidationDispatcher = DownloadedRemoteComicCacheInvalidationDispatcher().also { dispatcher ->
            dispatcher.register(invalidatedItemIds::add)
        }
        val repository = TestRemoteDocumentDownloadRepository(
            cacheInvalidationDispatcher = cacheInvalidationDispatcher,
        )
        val item = testRemoteComic(id = "download-cache-entry")

        val uri = repository.downloadToLocalStorage(item)

        assertNotNull(uri)
        assertEquals(listOf(item.id), invalidatedItemIds)
    }

    @Test
    fun `delete invalidates stored document cache entry`() = runBlocking {
        val invalidatedItemIds = mutableListOf<String>()
        val cacheInvalidationDispatcher = DownloadedRemoteComicCacheInvalidationDispatcher().also { dispatcher ->
            dispatcher.register(invalidatedItemIds::add)
        }
        val repository = TestRemoteDocumentDownloadRepository(
            cacheInvalidationDispatcher = cacheInvalidationDispatcher,
        )
        val item = testRemoteComic(id = "delete-cache-entry")
        repository.downloadToLocalStorage(item)
        invalidatedItemIds.clear()

        val deleted = repository.deleteStoredDocument(item)

        assertEquals(true, deleted)
        assertEquals(listOf(item.id), invalidatedItemIds)
        assertNull(repository.storedDocumentUri(item))
    }

    @Test
    fun `a download that breaks off keeps its bytes apart and the next try goes on from them`() = runBlocking {
        val repository = TestRemoteDocumentDownloadRepository(
            cacheInvalidationDispatcher = DownloadedRemoteComicCacheInvalidationDispatcher(),
            breakOffAfter = "first half|",
        )
        val item = testRemoteComic(id = "broken-off")

        val failure = runCatching { repository.downloadToLocalStorage(item) }.exceptionOrNull()

        assertTrue("$failure", failure is java.net.SocketException)
        // Half a file is never taken for the book.
        assertNull(repository.storedDocumentUri(item))
        assertEquals(listOf(0L), repository.bytesHeldAtStart)

        val uri = repository.downloadToLocalStorage(item)

        assertNotNull(uri)
        assertEquals(listOf(0L, "first half|".length.toLong()), repository.bytesHeldAtStart)
        assertEquals("first half|second half", File(checkNotNull(uri?.path)).readText())
        assertTrue(repository.partialFiles().isEmpty())
    }

    @Test
    fun `removing a book or its download forgets the bytes a broken-off download kept`() = runBlocking {
        val repository = TestRemoteDocumentDownloadRepository(
            cacheInvalidationDispatcher = DownloadedRemoteComicCacheInvalidationDispatcher(),
            breakOffAfter = "first half|",
        )
        val first = testRemoteComic(id = "forget-on-cancel")
        val second = testRemoteComic(id = "forget-on-delete")
        runCatching { repository.downloadToLocalStorage(first) }
        runCatching { repository.downloadToLocalStorage(second) }
        assertEquals(4, repository.partialFiles().size)

        repository.deletePartialDownload(first)
        repository.deleteStoredDocument(second)

        assertTrue(repository.partialFiles().isEmpty())
    }
}

private class TestRemoteDocumentDownloadRepository(
    private val cacheInvalidationDispatcher: DownloadedRemoteComicCacheInvalidationDispatcher,
    // Each first try writes this much, then the connection drops.
    private val breakOffAfter: String? = null,
) : RemoteDocumentDownloadRepository<TestDownloadStoredSession>(
    context = ApplicationProvider.getApplicationContext(),
    sourceType = SourceType.KAVITA,
    storageDirectoryName = "test-remote-document-downloads",
    supportedFormats = setOf(FormatType.CBZ),
    sessionStore = TestDownloadSourceSessionStore(
        TestDownloadStoredSession(
            sourceType = SourceType.KAVITA,
            displayName = "Test Kavita",
            baseUrl = "https://example.test",
            username = "reader",
        ),
    ),
    missingSessionMessage = "Missing test session.",
) {
    val bytesHeldAtStart = mutableListOf<Long>()
    private val brokenOffItemIds = mutableSetOf<String>()

    fun partialFiles(): List<File> {
        val filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
        return File(filesDir, "download-partials/test-remote-document-downloads")
            .walkTopDown()
            .filter(File::isFile)
            .toList()
    }

    override suspend fun download(
        session: TestDownloadStoredSession,
        item: ReadableItem,
        remoteItemId: String,
        target: PartialDownloadFile,
    ) {
        bytesHeldAtStart += target.downloadedBytes
        target.dataFile.parentFile?.mkdirs()
        if (breakOffAfter == null) {
            target.dataFile.writeText("downloaded:$remoteItemId")
        } else if (brokenOffItemIds.add(item.id)) {
            target.dataFile.writeText(breakOffAfter)
            target.infoFile.writeText("totalBytes=22")
            throw java.net.SocketException("Software caused connection abort")
        } else {
            target.dataFile.appendText("second half")
        }
    }

    override fun onStoredDocumentChanged(
        item: ReadableItem,
    ) {
        cacheInvalidationDispatcher.invalidate(item.id)
    }
}

private data class TestDownloadStoredSession(
    override val sourceType: SourceType,
    override val displayName: String,
    override val baseUrl: String,
    override val backupBaseUrl: String? = null,
    override val activeBaseUrl: String? = null,
    override val username: String,
) : StoredSourceSession

private class TestDownloadSourceSessionStore(
    initialSession: TestDownloadStoredSession?,
) : SourceSessionStore<TestDownloadStoredSession> {
    private val state = MutableStateFlow(initialSession)

    override val sessionFlow: StateFlow<TestDownloadStoredSession?> = state

    override suspend fun save(
        session: TestDownloadStoredSession,
    ) {
        state.value = session
    }

    override suspend fun clear() {
        state.value = null
    }
}

private fun testRemoteComic(
    id: String,
): ReadableItem = ReadableItem(
    id = SourceType.KAVITA.remoteItemId(id),
    sourceType = SourceType.KAVITA,
    libraryId = SourceType.KAVITA.remoteLibraryId("test-library"),
    remoteId = id,
    title = "Test Comic",
    format = FormatType.CBZ,
)
