package com.example.unifiedreader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadStatusTest {
    private val now = 1_763_712_000_000L
    private val minute = 60_000L
    private val unread = ReadStatusPlace(hasPlace = false, isFinished = false)
    private val started = ReadStatusPlace(hasPlace = true, isFinished = false)
    private val finished = ReadStatusPlace(hasPlace = true, isFinished = true)

    @Test
    fun `the eight statuses are Grimmory's, both ways, with the app's names`() {
        assertEquals(
            listOf("UNREAD", "READING", "RE_READING", "READ", "PARTIALLY_READ", "PAUSED", "WONT_READ", "ABANDONED"),
            ReadStatus.entries.map(ReadStatus::serverName),
        )
        assertEquals(
            listOf("Unread", "Reading", "Re-reading", "Read", "Partially read", "Paused", "Won't read", "Abandoned"),
            ReadStatus.entries.map(ReadStatus::label),
        )
        ReadStatus.entries.forEach { status -> assertEquals(status, ReadStatus.fromServer(status.serverName)) }
        assertEquals(ReadStatus.RE_READING, ReadStatus.fromServer(" re_reading "))
        // Grimmory's UNSET (never given one), nothing, and a status this version doesn't know are no status.
        assertNull(ReadStatus.fromServer("UNSET"))
        assertNull(ReadStatus.fromServer(null))
        assertNull(ReadStatus.fromServer(""))
        assertNull(ReadStatus.fromServer("SKIMMED"))
        assertEquals(setOf(ReadStatus.READ, ReadStatus.UNREAD), ReadStatus.entries.filter(ReadStatus::movesPlace).toSet())
    }

    @Test
    fun `a record is kept as a short line and read back, and a broken one is dropped`() {
        val record = ReadStatusRecord(ReadStatus.WONT_READ, now, unsent = true)
        assertEquals("WONT_READ|$now|1", record.encode())
        assertEquals(record, ReadStatusRecord.decode(record.encode()))
        assertEquals(record.copy(unsent = false), ReadStatusRecord.decode("WONT_READ|$now|0"))
        listOf(null, "", "READ", "READ|x|1", "READ|1|2", "SKIMMED|1|1", "READ|1|1|extra").forEach { broken ->
            assertNull(broken, ReadStatusRecord.decode(broken))
        }
    }

    @Test
    fun `a book with no status of its own shows what its place says`() {
        assertEquals(ReadStatus.UNREAD, shownReadStatus(null, null))
        assertEquals(ReadStatus.UNREAD, shownReadStatus(null, unread))
        assertEquals(ReadStatus.READING, shownReadStatus(null, started))
        assertEquals(ReadStatus.READ, shownReadStatus(null, finished))
        // Its own status, whatever the place.
        assertEquals(ReadStatus.PAUSED, shownReadStatus(ReadStatusRecord(ReadStatus.PAUSED, now), finished))
    }

    @Test
    fun `reading an unread book makes it Reading, finishing makes anything Read`() {
        assertEquals(ReadStatus.READING, readStatusAfterReading(ReadStatus.UNREAD, null, started))
        assertEquals(ReadStatus.READING, readStatusAfterReading(ReadStatus.UNREAD, unread, started))
        // A page turned in a book marked unread while its place stayed (unsent) is still reading it.
        assertEquals(ReadStatus.READING, readStatusAfterReading(ReadStatus.UNREAD, started, started))
        ReadStatus.entries.filter { it != ReadStatus.READ }.forEach { status ->
            assertEquals(status.name, ReadStatus.READ, readStatusAfterReading(status, started, finished))
            assertEquals(status.name, ReadStatus.READ, readStatusAfterReading(status, null, finished))
        }
        assertNull(readStatusAfterReading(ReadStatus.READ, started, finished))
    }

    @Test
    fun `Paused, Re-reading and the rest stay while the book is read`() {
        listOf(
            ReadStatus.READING,
            ReadStatus.RE_READING,
            ReadStatus.PARTIALLY_READ,
            ReadStatus.PAUSED,
            ReadStatus.WONT_READ,
            ReadStatus.ABANDONED,
        ).forEach { status ->
            assertNull(status.name, readStatusAfterReading(status, started, started))
            assertNull(status.name, readStatusAfterReading(status, null, started))
            assertNull(status.name, readStatusAfterReading(status, finished, started))
        }
    }

    @Test
    fun `going back into a finished book makes Read Reading, a page turned in one already back doesn't`() {
        assertEquals(ReadStatus.READING, readStatusAfterReading(ReadStatus.READ, finished, started))
        assertEquals(ReadStatus.READING, readStatusAfterReading(ReadStatus.READ, null, started))
        assertEquals(ReadStatus.READING, readStatusAfterReading(ReadStatus.READ, unread, started))
        assertNull(readStatusAfterReading(ReadStatus.READ, started, started))
    }

    @Test
    fun `a finished book opened again at its end is no new finish, and a cleared place is no reading`() {
        // Re-reading set on a finished book: opening it re-saves the end, which must not make it Read.
        assertNull(readStatusAfterReading(ReadStatus.RE_READING, finished, finished))
        assertNull(readStatusAfterReading(ReadStatus.PAUSED, finished, finished))
        assertNull(readStatusAfterReading(ReadStatus.READING, started, unread))
        assertNull(readStatusAfterReading(ReadStatus.UNREAD, started, null))
        // Re-read from the start and finished again: Read.
        assertEquals(ReadStatus.READ, readStatusAfterReading(ReadStatus.RE_READING, started, finished))
    }

    @Test
    fun `the statuses a book's page offers follow its source`() {
        val local = item(SourceType.LOCAL)
        assertEquals(ReadStatus.entries, local.readStatusChoices())
        assertEquals(ReadStatus.entries, item(SourceType.GRIMMORY).readStatusChoices())
        assertEquals(ReadStatus.entries, item(SourceType.KAVITA).readStatusChoices())
        // Stump can't be marked finished or unread from here: the six that are only a word.
        assertEquals(ReadStatus.entries.filterNot(ReadStatus::movesPlace), item(SourceType.STUMP).readStatusChoices())
    }

    @Test
    fun `a status waiting here goes up unless the server's was set clearly later`() {
        val waiting = ReadStatusRecord(ReadStatus.PAUSED, now - 10 * minute, unsent = true)
        fun decide(server: ReadStatus?, serverAt: Long?) = decideServerReadStatus(
            local = waiting,
            statusFromPlace = ReadStatus.READING,
            server = ServerReadStatus(server, serverAt),
            localPlaceUnsent = false,
            nowEpochMillis = now,
        )
        assertEquals(ServerReadStatusStep.Send, decide(ReadStatus.READING, serverAt = null))
        assertEquals(ServerReadStatusStep.Send, decide(ReadStatus.READING, serverAt = now - 9 * minute))
        // Within the margin: the server stamps with its own clock, so it's no later.
        assertEquals(ServerReadStatusStep.Send, decide(ReadStatus.READING, serverAt = now - 10 * minute + READ_STATUS_CLOCK_TOLERANCE_MILLIS))
        assertEquals(
            ServerReadStatusStep.Take(ReadStatusRecord(ReadStatus.ABANDONED, now - 5 * minute)),
            decide(ReadStatus.ABANDONED, serverAt = now - 5 * minute),
        )
        // A server time more than a day ahead is no time at all.
        assertEquals(ServerReadStatusStep.Send, decide(ReadStatus.ABANDONED, serverAt = now + 2 * FUTURE_CLOCK_LIMIT_MILLIS))
        // The server has it already: in step, nothing to send.
        assertEquals(ServerReadStatusStep.Settled(waiting.copy(unsent = false)), decide(ReadStatus.PAUSED, serverAt = null))
        // A server that doesn't say still gets it.
        assertEquals(ServerReadStatusStep.Send, decide(server = null, serverAt = null))
    }

    @Test
    fun `a status in step takes the server's when it changed there`() {
        val inStep = ReadStatusRecord(ReadStatus.READING, now - 60 * minute)
        fun decide(server: ReadStatus?, placeUnsent: Boolean = false, serverAt: Long? = null) = decideServerReadStatus(
            local = inStep,
            statusFromPlace = ReadStatus.READING,
            server = ServerReadStatus(server, serverAt),
            localPlaceUnsent = placeUnsent,
            nowEpochMillis = now,
        )
        assertEquals(ServerReadStatusStep.Keep, decide(ReadStatus.READING))
        assertEquals(ServerReadStatusStep.Keep, decide(server = null))
        assertEquals(ServerReadStatusStep.Take(ReadStatusRecord(ReadStatus.PAUSED, now)), decide(ReadStatus.PAUSED))
        assertEquals(
            ServerReadStatusStep.Take(ReadStatusRecord(ReadStatus.PAUSED, now - 3 * minute)),
            decide(ReadStatus.PAUSED, serverAt = now - 3 * minute),
        )
        // The server's is from before this device's place went up: the next sync says.
        assertEquals(ServerReadStatusStep.Keep, decide(ReadStatus.UNREAD, placeUnsent = true))
    }

    @Test
    fun `a book with no status of its own keeps nothing while the server agrees with its place`() {
        fun decide(server: ReadStatus?, fromPlace: ReadStatus, placeUnsent: Boolean = false) = decideServerReadStatus(
            local = null,
            statusFromPlace = fromPlace,
            server = ServerReadStatus(server),
            localPlaceUnsent = placeUnsent,
            nowEpochMillis = now,
        )
        assertEquals(ServerReadStatusStep.Keep, decide(ReadStatus.UNREAD, ReadStatus.UNREAD))
        assertEquals(ServerReadStatusStep.Keep, decide(ReadStatus.READ, ReadStatus.READ))
        assertEquals(ServerReadStatusStep.Keep, decide(server = null, fromPlace = ReadStatus.READING))
        assertEquals(ServerReadStatusStep.Take(ReadStatusRecord(ReadStatus.WONT_READ, now)), decide(ReadStatus.WONT_READ, ReadStatus.UNREAD))
        assertEquals(ServerReadStatusStep.Keep, decide(ReadStatus.UNREAD, ReadStatus.READING, placeUnsent = true))
        // Never sends a status it was never given.
        ReadStatus.entries.forEach { server ->
            assertFalse(decide(server, ReadStatus.READING) is ServerReadStatusStep.Send)
        }
    }

    @Test
    fun `the store keeps changes, tells each one once, and replaces only what it expects`() {
        val told = mutableListOf<Map<String, ReadStatusRecord>>()
        val store = MemoryReadStatusStore(onChanged = { told += it })
        val paused = ReadStatusRecord(ReadStatus.PAUSED, now, unsent = true)
        store.put("local:a", paused)
        store.put("local:a", paused)
        assertEquals(listOf(mapOf("local:a" to paused)), told)
        assertEquals(paused, store.get("local:a"))

        val sent = paused.copy(unsent = false)
        assertTrue(store.replaceIfCurrent("local:a", expected = paused, replacement = sent))
        assertEquals(sent, store.records.value["local:a"])
        // Changed meanwhile: left as it is.
        assertFalse(store.replaceIfCurrent("local:a", expected = paused, replacement = paused.copy(status = ReadStatus.READ)))
        assertEquals(sent, store.get("local:a"))
        assertEquals(2, told.size)

        store.putAll(mapOf("local:b" to sent, "local:a" to sent))
        assertEquals(mapOf("local:b" to sent), told.last())
    }

    private fun item(sourceType: SourceType) = ReadableItem(
        id = sourceType.remoteItemId("1"),
        sourceType = sourceType,
        libraryId = "library",
        title = "Book",
        format = FormatType.EPUB,
    )
}
