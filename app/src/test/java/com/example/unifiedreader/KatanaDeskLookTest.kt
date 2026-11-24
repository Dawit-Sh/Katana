package com.example.unifiedreader

import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.ReadableItemKind
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.network.buildHttpClient
import com.example.unifiedreader.data.grimmory.KtorGrimmoryApi
import com.example.unifiedreader.journal.JournalItemRef
import com.example.unifiedreader.journal.JournalMediaType
import com.example.unifiedreader.journal.JournalProgressInput
import com.example.unifiedreader.journal.JournalReadingSession
import com.example.unifiedreader.journal.JournalRepository
import com.example.unifiedreader.journal.JournalSnapshot
import com.example.unifiedreader.ui.components.AppStatusTone
import com.example.unifiedreader.ui.components.AppToastIcon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KatanaDeskLookTest {
    private val pc = KatanaDeskPaired(address = "10.0.2.2:47615", token = "t", deskId = "d-1", name = "KATANA-PC")

    @Test
    fun `the Sources row says how the pairing stands`() {
        val at = { _: Long -> "2:14 PM" }
        assertEquals(
            KatanaDeskRowStatus("Not paired · sync with your PC on this Wi-Fi", null),
            katanaDeskRowStatus(null, false, null, 0L, at),
        )
        assertEquals(KatanaDeskRowStatus("Syncing…", AppStatusTone.Neutral), katanaDeskRowStatus(pc, true, null, 1L, at))
        assertEquals(
            KatanaDeskRowStatus("Paired with KATANA-PC · synced 2:14 PM", AppStatusTone.Good),
            katanaDeskRowStatus(pc, false, null, 1L, at),
        )
        assertEquals(AppStatusTone.Problem, katanaDeskRowStatus(pc, false, "Can't reach", 1L, at).tone)
    }

    @Test
    fun `the note after a sync says the PC's book came, and when it was a long sync`() {
        fun ended(ms: Long, result: KatanaDeskSyncResult) =
            KatanaDeskSyncEvent.Ended(atEpochMillis = ms, deskName = "KATANA-PC", sinceEpochMillis = 0L, result = result)
        val came = KatanaDeskSyncResult(brought = KatanaDeskBringing.Arrived("i", "The Hole"))
        val arrived = katanaDeskDoneNote(ended(9_000L, came)) { "p. 7" }
        assertEquals("Synced with KATANA-PC · The Hole is on this device, at p. 7", arrived?.text)
        assertEquals("Synced with KATANA-PC", katanaDeskDoneNote(ended(2_000L, KatanaDeskSyncResult(books = 3))) { null }?.text)
        // a quick check-in that changed nothing: no note
        assertNull(katanaDeskDoneNote(ended(150L, KatanaDeskSyncResult(books = 3))) { null })
        val failed = katanaDeskDoneNote(ended(12_000L, KatanaDeskSyncResult(problem = "Can't reach Katana Desk."))) { null }
        assertEquals(AppToastIcon.Error, failed?.icon)
    }

    @Test
    fun `a book coming over says how much is here`() {
        assertEquals("Getting The Hole · 3.1 of 7.8 MB", gettingText(KatanaDeskGetting("The Hole", 3_250_586, 8_178_892)))
        assertEquals("Getting Notes · 3 of 7 KB", gettingText(KatanaDeskGetting("Notes", 3_100, 7_813)))
        assertEquals("Getting X · 2.0 MB", gettingText(KatanaDeskGetting("X", 2_097_152, null)))
        assertEquals(0.5f, KatanaDeskGetting("X", 50, 100).fraction)
    }

    @Test
    fun `reading sessions go to the desk once, and wait for a desk that can't take them`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val connection = KatanaDeskConnection(context).apply { save(pc) }
        val journal = FakeJournal(listOf(session("s1", 1_000L, 61_000L, 0.10, 0.12), session("s2", 100_000L, 400_000L, 0.12, 0.20)))
        val sending = KatanaDeskReadingTime(journal, connection)
        val server = MockWebServer()
        server.start()
        try {
            val api = KtorGrimmoryApi(httpClient = buildHttpClient(), baseUrl = "http://127.0.0.1:${server.port}")
            // a desk too old to take them: nothing marked sent, no failure
            server.enqueue(MockResponse().setResponseCode(404))
            sending.send(api, "t", book, "7")
            assertEquals(0L, connection.readingSentUntil(book.id))
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(200))
            sending.send(api, "t", book, "7")
            assertEquals(400_000L, connection.readingSentUntil(book.id))
            server.takeRequest()
            val first = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(7L, first.getLong("bookId"))
            assertEquals(60, first.getInt("durationSeconds"))
            assertEquals(10.0, first.getDouble("startProgress"), 0.01)
            assertEquals(12.0, first.getDouble("endProgress"), 0.01)
            assertEquals("/api/v1/reading-sessions", server.takeRequest().path)
            // sent: not again
            val before = server.requestCount
            sending.send(api, "t", book, "7")
            assertEquals(before, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    private val book =
        ReadableItem(id = "document-1", sourceType = SourceType.LOCAL, libraryId = LOCAL_LIBRARY_ID, title = "B", format = FormatType.EPUB)

    private fun session(id: String, start: Long, end: Long, from: Double, to: Double) = JournalReadingSession(
        id = id,
        itemId = book.id,
        sourceType = SourceType.LOCAL,
        libraryId = LOCAL_LIBRARY_ID,
        itemTitle = "B",
        coverUrl = null,
        itemKind = ReadableItemKind.BOOK,
        format = FormatType.EPUB,
        mediaType = JournalMediaType.BOOK,
        startedAtEpochMillis = start,
        endedAtEpochMillis = end,
        durationMillis = end - start,
        fromPercent = from,
        toPercent = to,
        fromPage = null,
        toPage = null,
        pageCount = null,
    )

    private class FakeJournal(private val sessions: List<JournalReadingSession>) : JournalRepository {
        override suspend fun getReadingSessionsForItem(itemId: String) = sessions.filter { it.itemId == itemId }

        override fun observeSnapshot(): Flow<JournalSnapshot> = emptyFlow()

        override suspend fun saveJournalEntry(item: ReadableItem, rating: Int?, reviewText: String) = Unit

        override suspend fun saveJournalEntry(item: JournalItemRef, rating: Int?, reviewText: String) = Unit

        override suspend fun deleteItemJournal(itemId: String) = Unit

        override suspend fun recordReaderProgress(input: JournalProgressInput) = Unit
    }
}
