package com.example.unifiedreader

import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.Library
import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.MemoryReadStatusStore
import com.example.unifiedreader.core.model.ReadStatus
import com.example.unifiedreader.core.model.ReadStatusPlace
import com.example.unifiedreader.core.model.ReadStatusRecord
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.remoteItemId
import com.example.unifiedreader.data.grimmory.GrimmoryApi
import com.example.unifiedreader.data.grimmory.GrimmoryBookCatalogEntry
import com.example.unifiedreader.data.grimmory.GrimmoryReadStatusAnswer
import com.example.unifiedreader.data.grimmory.GrimmorySession
import com.example.unifiedreader.feature.reader.core.ReaderLocator
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Reading statuses: reading here moving them on, what a sync takes from a server, and what goes up to Grimmory. */
@RunWith(RobolectricTestRunner::class)
class ReadStatusSyncTest {
    private val now = 1_795_256_130_000L
    private val minute = 60_000L
    private val grimmoryBook = book(SourceType.GRIMMORY.remoteItemId("34"), SourceType.GRIMMORY, remoteId = "34")
    private val otherGrimmoryBook = book(SourceType.GRIMMORY.remoteItemId("35"), SourceType.GRIMMORY, remoteId = "35")
    private val localBook = book("document-8054263d", SourceType.LOCAL)
    private val kavitaBook = book(SourceType.KAVITA.remoteItemId("7"), SourceType.KAVITA, remoteId = "7")

    @Test
    fun `a place reads as the library does`() {
        assertEquals(ReadStatusPlace(hasPlace = false, isFinished = false), place(percent = 0.0).toReadStatusPlace())
        assertEquals(ReadStatusPlace(hasPlace = true, isFinished = false), place(percent = 0.4).toReadStatusPlace())
        assertEquals(ReadStatusPlace(hasPlace = true, isFinished = true), place(percent = 0.4, finished = true).toReadStatusPlace())
        // The very end by share is finished, as on the Finished shelf.
        assertEquals(ReadStatusPlace(hasPlace = true, isFinished = true), place(percent = 0.9995).toReadStatusPlace())
        // A PDF's last page is the end (its share is 100%), as the Finished shelf has it.
        assertEquals(
            ReadStatusPlace(hasPlace = true, isFinished = true),
            place(percent = 1.0, type = LocatorType.PAGE, page = 68).toReadStatusPlace(),
        )
        // A comic or PDF's first page is a place.
        assertEquals(
            ReadStatusPlace(hasPlace = true, isFinished = false),
            place(percent = null, type = LocatorType.PAGE, page = 0).toReadStatusPlace(),
        )
        // Marked unread: no place, whatever it held.
        assertEquals(ReadStatusPlace(hasPlace = false, isFinished = false), place(percent = 1.0, finished = true, reset = true).toReadStatusPlace())
    }

    @Test
    fun `reading a book here moves its own status on, and only its own`() {
        val records = mapOf(
            grimmoryBook.id to ReadStatusRecord(ReadStatus.UNREAD, now - 10 * minute),
            otherGrimmoryBook.id to ReadStatusRecord(ReadStatus.PAUSED, now - 10 * minute),
            localBook.id to ReadStatusRecord(ReadStatus.READING, now - 10 * minute),
        )
        val before = mapOf(localBook.id to place(percent = 0.5, item = localBook, at = now - 9 * minute, state = SyncState.CLEAN))
        val after = mapOf(
            grimmoryBook.id to place(percent = 0.1, item = grimmoryBook, at = now),
            otherGrimmoryBook.id to place(percent = 0.2, item = otherGrimmoryBook, at = now),
            // A local PDF's page is saved in step: still reading here.
            localBook.id to place(percent = 1.0, finished = true, item = localBook, at = now, state = SyncState.CLEAN),
            kavitaBook.id to place(percent = 1.0, finished = true, item = kavitaBook, at = now),
        )

        assertEquals(
            mapOf(
                grimmoryBook.id to ReadStatusRecord(ReadStatus.READING, now, unsent = true),
                localBook.id to ReadStatusRecord(ReadStatus.READ, now, unsent = true),
            ),
            readStatusChangesFromReading(before, after, records),
        )
    }

    @Test
    fun `a server's place, an older place and a push going up are no reading here`() {
        val records = mapOf(grimmoryBook.id to ReadStatusRecord(ReadStatus.UNREAD, now - 10 * minute))
        // Grimmory's place came down (saved in step).
        assertTrue(readStatusChangesFromReading(emptyMap(), mapOf(grimmoryBook.id to place(0.3, item = grimmoryBook, at = now, state = SyncState.CLEAN)), records).isEmpty())
        // A place from before the status was set (a server's, applied after its status).
        assertTrue(readStatusChangesFromReading(emptyMap(), mapOf(grimmoryBook.id to place(0.3, item = grimmoryBook, at = now - 11 * minute)), records).isEmpty())
        // The same place, only its state moving as it goes up.
        val waiting = place(0.3, item = grimmoryBook, at = now)
        val unread = mapOf(grimmoryBook.id to ReadStatusRecord(ReadStatus.UNREAD, now))
        assertTrue(readStatusChangesFromReading(mapOf(grimmoryBook.id to waiting), mapOf(grimmoryBook.id to waiting.copy(syncState = SyncState.SYNCING)), unread).isEmpty())
        // Nothing changed for the book.
        assertTrue(readStatusChangesFromReading(mapOf(grimmoryBook.id to waiting), mapOf(grimmoryBook.id to waiting), unread).isEmpty())
    }

    @Test
    fun `Re-reading set on a finished book survives opening it at its end, and becomes Read when finished again`() {
        val rereading = mapOf(localBook.id to ReadStatusRecord(ReadStatus.RE_READING, now - 5 * minute, unsent = true))
        val end = place(1.0, finished = true, item = localBook, at = now - 10 * minute)
        // Opened: the reader saves its end again.
        assertTrue(readStatusChangesFromReading(mapOf(localBook.id to end), mapOf(localBook.id to end.copy(updatedAtEpochMillis = now)), rereading).isEmpty())
        // Back to the start and read: still Re-reading.
        val back = place(0.05, item = localBook, at = now + minute)
        assertTrue(readStatusChangesFromReading(mapOf(localBook.id to end), mapOf(localBook.id to back), rereading).isEmpty())
        // The end again: Read.
        val finishedAgain = place(1.0, finished = true, item = localBook, at = now + 30 * minute)
        assertEquals(
            ReadStatusRecord(ReadStatus.READ, now + 30 * minute, unsent = true),
            readStatusChangesFromReading(mapOf(localBook.id to back), mapOf(localBook.id to finishedAgain), rereading)[localBook.id],
        )
    }

    @Test
    fun `a pull takes the server's status, settles one it has, and names those to send`() {
        val store = MemoryReadStatusStore(
            mapOf(
                grimmoryBook.id to ReadStatusRecord(ReadStatus.READING, now - 60 * minute),
                otherGrimmoryBook.id to ReadStatusRecord(ReadStatus.PAUSED, now - minute, unsent = true),
                localBook.id to ReadStatusRecord(ReadStatus.ABANDONED, now - minute, unsent = true),
            ),
        )
        val toSend = store.applyServerStatuses(
            statuses = listOf(
                ServerBookStatus(grimmoryBook.id, "WONT_READ"),
                ServerBookStatus(otherGrimmoryBook.id, "PAUSED"),
                ServerBookStatus(localBook.id, "READING"),
                ServerBookStatus(kavitaBook.id, "UNREAD"),
            ),
            placeOf = { null },
            placeUnsent = { false },
            nowEpochMillis = now,
        )

        assertEquals(setOf(localBook.id), toSend)
        assertEquals(ReadStatusRecord(ReadStatus.WONT_READ, now), store.get(grimmoryBook.id))
        assertEquals(ReadStatusRecord(ReadStatus.PAUSED, now - minute), store.get(otherGrimmoryBook.id))
        assertEquals(ReadStatusRecord(ReadStatus.ABANDONED, now - minute, unsent = true), store.get(localBook.id))
        // A book the server has as its place says keeps nothing.
        assertNull(store.get(kavitaBook.id))
    }

    @Test
    fun `a pull never replaces a status picked while it ran`() {
        val picked = ReadStatusRecord(ReadStatus.ABANDONED, now, unsent = true)
        val store = MemoryReadStatusStore(mapOf(grimmoryBook.id to ReadStatusRecord(ReadStatus.READING, now - 60 * minute)))
        store.applyServerStatuses(
            statuses = listOf(ServerBookStatus(grimmoryBook.id, "PAUSED")),
            // The place is asked for after the record was read: the status is picked right then.
            placeOf = { store.put(grimmoryBook.id, picked); null },
            placeUnsent = { false },
            nowEpochMillis = now,
        )
        assertEquals(picked, store.get(grimmoryBook.id))
    }

    @Test
    fun `Grimmory's book answers bring their status, and one waiting to go up beats them`() {
        val places = TestReaderSessionStore()
        places.save(grimmoryBook, place(0.3, item = grimmoryBook, at = now - 20 * minute, state = SyncState.CLEAN))
        val store = MemoryReadStatusStore(mapOf(otherGrimmoryBook.id to ReadStatusRecord(ReadStatus.RE_READING, now - minute, unsent = true)))
        store.takeGrimmoryStatuses(
            entries = listOf(
                GrimmoryBookCatalogEntry(item = grimmoryBook, progress = null, readStatus = "PAUSED", readStatusModifiedTime = "2026-11-21T10:10:30Z"),
                GrimmoryBookCatalogEntry(item = otherGrimmoryBook, progress = null, readStatus = "READ"),
            ),
            readerSessionStore = places,
            nowEpochMillis = now,
        )
        assertEquals(ReadStatusRecord(ReadStatus.PAUSED, now - 5 * minute), store.get(grimmoryBook.id))
        assertEquals(ReadStatusRecord(ReadStatus.RE_READING, now - minute, unsent = true), store.get(otherGrimmoryBook.id))

        // Read here since, the place waiting to go up: Grimmory's status is from before it.
        places.save(grimmoryBook, place(0.5, item = grimmoryBook, at = now))
        store.takeGrimmoryStatuses(
            entries = listOf(GrimmoryBookCatalogEntry(item = grimmoryBook, progress = null, readStatus = "UNREAD")),
            readerSessionStore = places,
            nowEpochMillis = now,
        )
        assertEquals(ReadStatus.PAUSED, store.get(grimmoryBook.id)?.status)
    }

    @Test
    fun `statuses go to Grimmory for its books only, after their place, and stop waiting`() = runTest {
        val setup = PushSetup(items = listOf(grimmoryBook, otherGrimmoryBook, localBook, kavitaBook))
        setup.store.putAll(
            mapOf(
                grimmoryBook.id to ReadStatusRecord(ReadStatus.RE_READING, now, unsent = true),
                otherGrimmoryBook.id to ReadStatusRecord(ReadStatus.PAUSED, now, unsent = true),
                localBook.id to ReadStatusRecord(ReadStatus.ABANDONED, now, unsent = true),
                kavitaBook.id to ReadStatusRecord(ReadStatus.WONT_READ, now, unsent = true),
            ),
        )
        // Its place waits to go up: its status follows it.
        setup.places.save(otherGrimmoryBook, place(0.4, item = otherGrimmoryBook, at = now))
        setup.api.answers["34"] = GrimmoryReadStatusAnswer("RE_READING", setAtEpochMillis = now)

        assertTrue(setup.sync.hasWaiting())
        assertTrue(setup.sync.sendWaiting())

        assertEquals(listOf("34" to "RE_READING"), setup.api.sent)
        assertEquals(ReadStatusRecord(ReadStatus.RE_READING, now, unsent = false), setup.store.get(grimmoryBook.id))
        assertTrue(setup.store.get(otherGrimmoryBook.id)!!.unsent)
        // A file here and a Kavita book sync their statuses nowhere through Grimmory.
        assertTrue(setup.store.get(localBook.id)!!.unsent)
        assertTrue(setup.store.get(kavitaBook.id)!!.unsent)

        // The place went up: the status goes next.
        setup.places.save(otherGrimmoryBook, place(0.4, item = otherGrimmoryBook, at = now, state = SyncState.CLEAN))
        assertTrue(setup.sync.sendWaiting())
        assertEquals(listOf("34" to "RE_READING", "35" to "PAUSED"), setup.api.sent)
        assertFalse(setup.store.get(otherGrimmoryBook.id)!!.unsent)
        assertFalse(setup.sync.hasWaiting())
    }

    @Test
    fun `Grimmory's answer is kept, a refusal and a book it doesn't list are left until the status changes`() = runTest {
        val gone = book(SourceType.GRIMMORY.remoteItemId("99"), SourceType.GRIMMORY, remoteId = "99")
        val setup = PushSetup(items = listOf(grimmoryBook, otherGrimmoryBook))
        setup.store.putAll(
            mapOf(
                grimmoryBook.id to ReadStatusRecord(ReadStatus.READ, now, unsent = true),
                otherGrimmoryBook.id to ReadStatusRecord(ReadStatus.ABANDONED, now, unsent = true),
                gone.id to ReadStatusRecord(ReadStatus.PAUSED, now, unsent = true),
            ),
        )
        setup.api.refused += "35"
        // Grimmory keeps its own word for it.
        setup.api.answers["34"] = GrimmoryReadStatusAnswer("PARTIALLY_READ")

        assertTrue(setup.sync.sendWaiting())
        assertEquals(ReadStatusRecord(ReadStatus.PARTIALLY_READ, now, unsent = false), setup.store.get(grimmoryBook.id))
        assertTrue(setup.store.get(otherGrimmoryBook.id)!!.unsent)
        assertTrue(setup.store.get(gone.id)!!.unsent)
        // Not tried again and again: nothing waits that could go.
        assertFalse(setup.sync.hasWaiting())
        setup.api.sent.clear()
        assertTrue(setup.sync.sendWaiting())
        assertTrue(setup.api.sent.isEmpty())

        // Changed: tried again.
        setup.api.refused.clear()
        setup.store.put(otherGrimmoryBook.id, ReadStatusRecord(ReadStatus.WONT_READ, now + minute, unsent = true))
        assertTrue(setup.sync.hasWaiting())
        assertTrue(setup.sync.sendWaiting())
        assertEquals(listOf("35" to "WONT_READ"), setup.api.sent)
    }

    @Test
    fun `a status picked while it went up stays waiting, and nothing goes without a sign-in`() = runTest {
        val setup = PushSetup(items = listOf(grimmoryBook))
        val first = ReadStatusRecord(ReadStatus.PAUSED, now, unsent = true)
        val second = ReadStatusRecord(ReadStatus.ABANDONED, now + minute, unsent = true)
        setup.store.put(grimmoryBook.id, first)
        setup.api.onSend = { setup.store.put(grimmoryBook.id, second) }
        assertTrue(setup.sync.sendWaiting())
        assertEquals(second, setup.store.get(grimmoryBook.id))

        setup.api.onSend = {}
        setup.credentials.clear()
        setup.api.sent.clear()
        assertFalse(setup.sync.sendWaiting())
        assertFalse(setup.sync.hasWaiting())
        assertTrue(setup.api.sent.isEmpty())
    }

    private inner class PushSetup(items: List<ReadableItem>) {
        val store = MemoryReadStatusStore()
        val places = TestReaderSessionStore()
        val api = StatusGrimmoryApi()
        val credentials = FakeGrimmoryCredentialStore(
            GrimmoryStoredSession(
                displayName = "Grimmory",
                baseUrl = "http://grimmory.lan",
                username = "reader",
                accessToken = "access",
                refreshToken = "refresh",
            ),
        )
        val sync = GrimmoryReadStatusSync(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            grimmoryApiFactory = GrimmoryApiFactory { api },
            credentialStore = credentials,
            store = store,
            readerSessionStore = places,
            catalog = FakeReadableItemRepository(items),
            connectivity = RemoteSourceConnectivityPolicy(),
        )
    }

    private fun place(
        percent: Double?,
        finished: Boolean = false,
        reset: Boolean = false,
        type: LocatorType = LocatorType.PERCENT,
        page: Int? = null,
        item: ReadableItem = localBook,
        at: Long = now,
        state: SyncState = SyncState.DIRTY,
    ) = ReaderProgressSnapshot(
        itemId = item.id,
        locatorType = type,
        page = page,
        percent = percent,
        isFinished = finished,
        isExplicitUnreadReset = reset,
        syncState = state,
        updatedAtEpochMillis = at,
    )

    private fun book(id: String, source: SourceType, remoteId: String? = null) = ReadableItem(
        id = id,
        sourceType = source,
        libraryId = "${source.name.lowercase()}:library:1",
        remoteId = remoteId,
        title = "Book $id",
        format = FormatType.EPUB,
    )
}

private class StatusGrimmoryApi : GrimmoryApi {
    val sent = mutableListOf<Pair<String, String>>()
    val answers = mutableMapOf<String, GrimmoryReadStatusAnswer>()
    val refused = mutableSetOf<String>()
    var onSend: () -> Unit = {}

    override suspend fun setReadStatus(accessToken: String, bookId: String, status: String): GrimmoryReadStatusAnswer {
        if (bookId in refused) error("$status isn't a reading status")
        sent += bookId to status
        onSend()
        return answers[bookId] ?: GrimmoryReadStatusAnswer(status)
    }

    override suspend fun login(username: String, password: String): GrimmorySession = error("not used")

    override suspend fun getLibraries(accessToken: String): List<Library> = emptyList()

    override suspend fun getBooks(accessToken: String, libraryId: String): List<ReadableItem> = emptyList()

    override fun bookContentUrl(bookId: String, bookType: String?): String = "http://grimmory.lan/$bookId"

    override fun bookCoverUrl(bookId: String): String = "http://grimmory.lan/$bookId/cover"
}

private class TestReaderSessionStore : ReaderSessionStore {
    private val progressState = MutableStateFlow<Map<String, ReaderProgressSnapshot>>(emptyMap())

    override val isHydratedFlow: StateFlow<Boolean> = MutableStateFlow(true)
    override val progressByItemIdFlow: StateFlow<Map<String, ReaderProgressSnapshot>> = progressState
    override val sessionByItemIdFlow: StateFlow<Map<String, ReaderSessionSnapshot>> = MutableStateFlow(emptyMap())

    fun save(item: ReadableItem, snapshot: ReaderProgressSnapshot) {
        progressState.value = progressState.value + (item.id to snapshot)
    }

    override fun getProgress(item: ReadableItem): ReaderProgressSnapshot? = progressState.value[item.id]

    override fun saveProgress(
        item: ReadableItem,
        locator: ReaderLocator,
        isFinished: Boolean,
        isExplicitUnreadReset: Boolean,
        syncState: SyncState,
        pageCount: Int?,
        updatedAtEpochMillis: Long,
    ) = save(
        item,
        ReaderProgressSnapshot(
            itemId = item.id,
            locatorType = locator.locatorType,
            page = locator.page,
            cfi = locator.cfi,
            percent = locator.percent,
            isFinished = isFinished,
            isExplicitUnreadReset = isExplicitUnreadReset,
            syncState = syncState,
            updatedAtEpochMillis = updatedAtEpochMillis,
        ),
    )

    override suspend fun replaceProgressIfCurrent(expected: ReaderProgressSnapshot, replacement: ReaderProgressSnapshot?): Boolean {
        if (progressState.value[expected.itemId] != expected) return false
        progressState.value = if (replacement == null) progressState.value - expected.itemId else progressState.value + (expected.itemId to replacement)
        return true
    }

    override fun getSession(item: ReadableItem): ReaderSessionSnapshot? = null

    override fun updateSession(itemId: String, pageCount: Int?, preferences: Map<String, String>) = Unit

    override fun clearProgress(itemId: String) {
        progressState.value = progressState.value - itemId
    }

    override fun clearItem(itemId: String) = clearProgress(itemId)

    override fun restoreItem(itemId: String) = Unit
}
