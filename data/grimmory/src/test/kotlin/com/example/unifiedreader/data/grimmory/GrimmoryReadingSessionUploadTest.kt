package com.example.unifiedreader.data.grimmory

import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.ReadableItemKind
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.journal.JournalReadingSession
import com.example.unifiedreader.journal.journalMediaType
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GrimmoryReadingSessionUploadTest {
    private val item = ReadableItem(
        id = "grimmory:item:88",
        sourceType = SourceType.GRIMMORY,
        libraryId = "grimmory:library:1",
        remoteId = "88",
        title = "The Hole",
        format = FormatType.EPUB,
        kind = ReadableItemKind.BOOK,
        pageCount = 78,
    )

    // Read from 00:00:01.000 to 00:10:01.000; the app sends milliseconds.
    private val session = JournalReadingSession(
        id = "local:grimmory:item:88:1000:601000",
        itemId = item.id,
        sourceType = item.sourceType,
        libraryId = item.libraryId,
        itemTitle = item.title,
        coverUrl = item.coverUrl,
        itemKind = item.kind,
        format = item.format,
        mediaType = item.journalMediaType(),
        startedAtEpochMillis = 1_000L,
        endedAtEpochMillis = 601_000L,
        durationMillis = 600_000L,
        fromPercent = 0.1,
        toPercent = 0.4,
        fromPage = 2,
        toPage = 8,
        pageCount = item.pageCount,
        syncState = SyncState.DIRTY,
    )

    private val repository = RecordingJournalRepository(dirtySessions = listOf(session))

    // An upload whose answer was lost stays waiting; the server kept it, to the second.
    @Test
    fun `a reading session the server already holds isn't posted again`() = runTest {
        val api = FakeGrimmoryApi(
            sessionsByBook = mapOf("88" to listOf(serverSession(end = "1970-01-01T00:10:01Z"))),
        )

        push(api)

        assertTrue(api.createdReadingSessions.isEmpty())
        assertEquals(listOf("88"), api.requestedSessionsForBooks)
        assertEquals(SyncState.CLEAN, repository.markedSessionSyncStates[session.id])
    }

    @Test
    fun `a reading session is posted when the server holds only other sessions of the book`() = runTest {
        val api = FakeGrimmoryApi(
            sessionsByBook = mapOf("88" to listOf(serverSession(end = "1970-01-01T00:05:00Z"))),
        )

        push(api)

        assertEquals(1, api.createdReadingSessions.size)
        assertEquals(SyncState.CLEAN, repository.markedSessionSyncStates[session.id])
    }

    @Test
    fun `a reading session waits when the server's list of sessions doesn't arrive`() = runTest {
        val api = FakeGrimmoryApi(sessionFailuresByBook = mapOf("88" to IOException("timeout")))

        try {
            push(api)
            fail("An unreachable server goes to the address failover")
        } catch (expected: IOException) {
            assertEquals("timeout", expected.message)
        }

        assertTrue(api.createdReadingSessions.isEmpty())
        assertTrue(repository.markedSessionSyncStates.isEmpty())
    }

    @Test
    fun `a reading session is posted when the server's list fails for another reason`() = runTest {
        val api = FakeGrimmoryApi(sessionFailuresByBook = mapOf("88" to IllegalStateException("Internal Server Error")))

        push(api)

        assertEquals(1, api.createdReadingSessions.size)
        assertEquals(SyncState.CLEAN, repository.markedSessionSyncStates[session.id])
    }

    private suspend fun push(api: FakeGrimmoryApi) = pushDirtyGrimmoryJournalEntries(
        api = api,
        accessToken = "token",
        entries = listOf(item),
        journalRepository = repository,
    )

    private fun serverSession(end: String) = GrimmoryReadingSessionDto(
        id = 5,
        bookId = 88,
        startTime = "1970-01-01T00:00:01Z",
        endTime = end,
    )
}
