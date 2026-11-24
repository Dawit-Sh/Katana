package com.example.unifiedreader

import androidx.test.core.app.ApplicationProvider
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.network.buildHttpClient
import com.example.unifiedreader.data.grimmory.KtorGrimmoryApi
import com.example.unifiedreader.journal.JournalItemRef
import com.example.unifiedreader.journal.JournalProgressInput
import com.example.unifiedreader.journal.JournalReadingSession
import com.example.unifiedreader.journal.JournalRepository
import com.example.unifiedreader.journal.JournalSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KatanaDeskTimeInTest {
    private val book =
        ReadableItem(id = "document-1", sourceType = SourceType.LOCAL, libraryId = LOCAL_LIBRARY_ID, title = "B", format = FormatType.EPUB)

    private fun row(id: String, book: Long, start: String, end: String, seconds: Long) =
        """{"id":"$id","bookId":$book,"title":"Desk book","format":"EPUB","startTime":"$start","endTime":"$end","seconds":$seconds}"""

    private fun answer(vararg rows: String) = """{"deskId":"d-1","sessions":[${rows.joinToString(",")}]}"""

    @Test
    fun `the desk's time counts once, on the right book, and never goes back`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val server = MockWebServer()
        server.start()
        try {
            val pc = KatanaDeskPaired(address = "127.0.0.1:${server.port}", token = "t", deskId = "d-1", name = "PC")
            val connection = KatanaDeskConnection(context).apply { save(pc) }
            val journal = MapJournal()
            val now = java.time.OffsetDateTime.parse("2026-11-24T12:00:00Z").toInstant().toEpochMilli()
            val pulling = KatanaDeskTimeIn(buildHttpClient(), journal, connection) { now }
            val links = mapOf(book.id to "7")
            val hour = row("desk:d-1:2026-11-24:9:7", 7, "2026-11-24T09:00:00Z", "2026-11-24T10:00:00Z", 600)
            val elsewhere = row("desk:d-1:2026-11-24:9:8", 8, "2026-11-24T09:00:00Z", "2026-11-24T10:00:00Z", 300)
            server.enqueue(MockResponse().setBody(answer(hour, elsewhere)))
            pulling.pull(pc, listOf(book), links)
            assertEquals(600_000L, journal.rows.getValue("desk:d-1:2026-11-24:9:7").durationMillis)
            assertEquals(book.id, journal.rows.getValue("desk:d-1:2026-11-24:9:7").itemId)
            // a book not on this device counts in the totals, not on a wrong book
            assertEquals("katana-desk:8", journal.rows.getValue("desk:d-1:2026-11-24:9:8").itemId)
            // the same hour asked again, read on: replaced, not added
            val grown = row("desk:d-1:2026-11-24:9:7", 7, "2026-11-24T09:00:00Z", "2026-11-24T10:00:00Z", 900)
            server.enqueue(MockResponse().setBody(answer(grown)))
            pulling.pull(pc, listOf(book), links)
            assertTrue(server.takeRequest().path!!.endsWith("/katana-sync/v1/reading-sessions"))
            assertTrue(server.takeRequest().path!!.contains("since="))
            assertEquals(2, journal.rows.size)
            assertEquals(900_000L, journal.rows.getValue("desk:d-1:2026-11-24:9:7").durationMillis)
            // a desk too old to know the question: nothing changes
            server.enqueue(MockResponse().setResponseCode(404))
            pulling.pull(pc, listOf(book), links)
            assertEquals(2, journal.rows.size)
            // what came from the desk never goes back to it
            server.enqueue(MockResponse().setResponseCode(200))
            val before = server.requestCount
            val api = KtorGrimmoryApi(httpClient = buildHttpClient(), baseUrl = pc.baseUrl)
            KatanaDeskReadingTime(journal, connection).send(api, "t", book, "7")
            assertEquals(before, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `no time, time to come and too much time are kept safe`() {
        val now = java.time.OffsetDateTime.parse("2026-11-24T12:00:00Z").toInstant().toEpochMilli()
        val rows = parseKatanaDeskTime(
            answer(
                row("desk:a", 1, "2026-11-24T09:00:00Z", "2026-11-24T09:10:00Z", 0),
                row("desk:b", 1, "2026-11-24T11:50:00Z", "2026-11-24T13:00:00Z", 3600),
                row("desk:c", 1, "2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z", 600),
                row("desk:d", 1, "2026-11-24T09:00:00Z", "2026-11-24T09:01:00Z", 3600),
                row("not-the-desk", 1, "2026-11-24T09:00:00Z", "2026-11-24T09:01:00Z", 60),
                """{"id":"desk:e","startTime":"garbled"}""",
            ),
        )
        assertEquals(listOf("desk:a", "desk:b", "desk:c", "desk:d"), rows.map { it.id })
        assertNull(rows[0].toJournalSession(null, now))
        // the end past now is cut at now, and the length at its span
        val later = rows[1].toJournalSession(null, now)!!
        assertEquals(now, later.endedAtEpochMillis)
        assertEquals(600_000L, later.durationMillis)
        assertNull(rows[2].toJournalSession(null, now))
        assertEquals(60_000L, rows[3].toJournalSession(null, now)!!.durationMillis)
        assertEquals(emptyList<KatanaDeskTimeRow>(), parseKatanaDeskTime("not json"))
    }

    private class MapJournal : JournalRepository {
        val rows = linkedMapOf<String, JournalReadingSession>()

        override suspend fun saveReadingSessions(sessions: List<JournalReadingSession>) = sessions.forEach { rows[it.id] = it }

        override suspend fun getReadingSessionsForItem(itemId: String) = rows.values.filter { it.itemId == itemId }

        override fun observeSnapshot(): Flow<JournalSnapshot> = emptyFlow()

        override suspend fun saveJournalEntry(item: ReadableItem, rating: Int?, reviewText: String) = Unit

        override suspend fun saveJournalEntry(item: JournalItemRef, rating: Int?, reviewText: String) = Unit

        override suspend fun deleteItemJournal(itemId: String) = Unit

        override suspend fun recordReaderProgress(input: JournalProgressInput) = Unit
    }
}
