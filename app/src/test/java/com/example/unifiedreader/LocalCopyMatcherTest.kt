package com.example.unifiedreader

import com.example.unifiedreader.core.database.entity.LOCAL_LINK_CHANGED
import com.example.unifiedreader.core.database.entity.LOCAL_LINK_LINKED
import com.example.unifiedreader.core.database.entity.LOCAL_LINK_NOT_FOUND
import com.example.unifiedreader.core.database.entity.LocalServerLinkEntity
import com.example.unifiedreader.core.database.entity.RemoteFileCandidate
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A local file links to a Grimmory book only when it is the very same file, and never to two books. */
class LocalCopyMatcherTest {
    private val server = "reader@http://grimmory.lan"
    private val book = ReadableItem(
        id = "local:document:7",
        sourceType = SourceType.LOCAL,
        libraryId = "local",
        title = "The Hole",
        format = FormatType.EPUB,
    )
    private val size = 2_049_000L
    private val sizeKb = size / 1024
    private var file: LocalFileFingerprint? = LocalFileFingerprint(size, "aa11")
    private val serverFiles = mutableMapOf<String, RemoteFileFingerprint>()
    private val serverReads = mutableListOf<String>()
    private val store = FakeLinks()
    private val matcher = LocalCopyMatcher(
        store = store,
        fingerprintOf = { file },
        serverFingerprintOf = { bookId, _ -> serverReads += bookId; serverFiles[bookId] },
        now = { 1_000L },
    )

    private fun check(canAskServer: Boolean = true, serverKey: String = server) =
        runBlocking { matcher.check(book, serverKey, canAskServer) }

    @Test
    fun `the same format, exact size and fingerprint links it`() {
        store.catalog += RemoteFileCandidate("88", sizeKb)
        serverFiles["88"] = RemoteFileFingerprint(size, "aa11")

        assertEquals(LocalCopyCheck.Linked("88"), check())
        assertEquals(LOCAL_LINK_LINKED, store.links.getValue(book.id).state)
    }

    @Test
    fun `a book of the same size that is another file doesn't link, and isn't read again`() {
        store.catalog += RemoteFileCandidate("88", sizeKb)
        serverFiles["88"] = RemoteFileFingerprint(size, "bb22")

        assertEquals(LocalCopyCheck.NotLinked, check())
        assertEquals(LocalCopyCheck.NotLinked, check())

        assertEquals("one read of its samples, the miss remembered", listOf("88"), serverReads)
        assertEquals("88", store.links.getValue(book.id).rejectedBookIds)
    }

    @Test
    fun `the same samples but another exact size is another file`() {
        store.catalog += RemoteFileCandidate("88", sizeKb)
        serverFiles["88"] = RemoteFileFingerprint(size + 10, "aa11")

        assertEquals(LocalCopyCheck.NotLinked, check())
    }

    @Test
    fun `two server books that are both this file link neither`() {
        store.catalog += listOf(RemoteFileCandidate("88", sizeKb), RemoteFileCandidate("90", sizeKb))
        serverFiles["88"] = RemoteFileFingerprint(size, "aa11")
        serverFiles["90"] = RemoteFileFingerprint(size, "aa11")

        assertEquals(LocalCopyCheck.NotLinked, check())
    }

    @Test
    fun `no more than a handful of books of the same size are read`() {
        repeat(10) { index -> store.catalog += RemoteFileCandidate("b$index", sizeKb) }

        check()

        assertEquals(LocalCopyMatcher.MAX_CANDIDATES, serverReads.size)
    }

    @Test
    fun `no book of its size means not on the server, told without asking it, even offline`() {
        assertEquals(LocalCopyCheck.NotLinked, check(canAskServer = false))
        assertEquals(LOCAL_LINK_NOT_FOUND, store.links.getValue(book.id).state)
        assertTrue(serverReads.isEmpty())
    }

    @Test
    fun `offline with a book of its size to read, nothing is decided`() {
        store.catalog += RemoteFileCandidate("88", sizeKb)

        assertEquals(LocalCopyCheck.Unknown, check(canAskServer = false))
        assertTrue(store.links.isEmpty())
    }

    @Test
    fun `a linked file changed here stops syncing, keeps its book, and links again only to that book`() {
        store.catalog += RemoteFileCandidate("88", sizeKb)
        serverFiles["88"] = RemoteFileFingerprint(size, "aa11")
        check()

        file = LocalFileFingerprint(size, "cc33")
        store.catalog += RemoteFileCandidate("90", sizeKb)
        serverFiles["90"] = RemoteFileFingerprint(size, "cc33")

        assertEquals("never moved to another book", LocalCopyCheck.NotLinked, check())
        assertEquals(LOCAL_LINK_CHANGED, store.links.getValue(book.id).state)
        assertEquals("88", store.links.getValue(book.id).remoteBookId)

        file = LocalFileFingerprint(size, "aa11")
        assertEquals(LocalCopyCheck.Linked("88"), check())
    }

    @Test
    fun `a linked file is read again on the server only when the server's size for it changes`() {
        store.catalog += RemoteFileCandidate("88", sizeKb)
        serverFiles["88"] = RemoteFileFingerprint(size, "aa11")
        check()
        serverReads.clear()

        assertEquals(LocalCopyCheck.Linked("88"), check())
        assertTrue("an unchanged link costs the server nothing", serverReads.isEmpty())

        store.catalog.clear()
        store.catalog += RemoteFileCandidate("88", sizeKb + 3)
        serverFiles["88"] = RemoteFileFingerprint(size + 3_000, "dd44")
        assertEquals(LocalCopyCheck.NotLinked, check())
        assertEquals(LOCAL_LINK_CHANGED, store.links.getValue(book.id).state)
    }

    @Test
    fun `a book linked on one server never links on another`() {
        store.catalog += RemoteFileCandidate("88", sizeKb)
        serverFiles["88"] = RemoteFileFingerprint(size, "aa11")
        check()

        assertEquals(LocalCopyCheck.NotLinked, check(serverKey = "reader@http://other.lan"))
    }

    @Test
    fun `an unreadable file, or a format whose marks don't sync, links nothing`() {
        file = null
        assertEquals(LocalCopyCheck.Unknown, check())

        val djvu = book.copy(format = FormatType.DJVU)
        assertEquals(LocalCopyCheck.NotLinked, runBlocking { matcher.check(djvu, server, true) })
    }

    private class FakeLinks : LocalCopyLinkStore {
        val links = mutableMapOf<String, LocalServerLinkEntity>()
        val catalog = mutableListOf<RemoteFileCandidate>()

        override suspend fun link(localItemId: String) = links[localItemId]

        override suspend fun save(link: LocalServerLinkEntity) {
            links[link.localItemId] = link
        }

        override suspend fun candidates(item: ReadableItem, sizeKb: Long) = catalog.filter { it.remoteFileSizeKb == sizeKb }

        override suspend fun catalogBook(remoteBookId: String) = catalog.firstOrNull { it.remoteId == remoteBookId }
    }
}
