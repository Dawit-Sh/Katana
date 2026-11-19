package com.example.unifiedreader

import com.example.unifiedreader.core.model.DeviceClock
import com.example.unifiedreader.core.model.SourceType
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LibrarySyncStatusTest {
    // These read times the 24-hour way; DeviceClockTest covers the 12-hour default.
    @Before
    fun useTwentyFourHourClock() {
        DeviceClock.uses24Hour = true
    }

    @After
    fun restoreClock() {
        DeviceClock.uses24Hour = false
    }

    private val zone = ZoneId.of("Africa/Addis_Ababa")
    private val now = Instant.parse("2026-11-18T15:30:00Z") // 18:30 in Addis Ababa

    @Test
    fun `the last sync reads as today, yesterday or the day`() {
        assertEquals("Last synced today at 17:07", lastSyncedLabel(Instant.parse("2026-11-18T14:07:00Z").toEpochMilli(), now, zone))
        assertEquals("Last synced yesterday at 09:12", lastSyncedLabel(Instant.parse("2026-11-17T06:12:00Z").toEpochMilli(), now, zone))
        assertEquals("Last synced 2 Nov at 21:40", lastSyncedLabel(Instant.parse("2026-11-02T18:40:00Z").toEpochMilli(), now, zone))
        assertEquals("Last synced 30 Dec 2025 at 08:00", lastSyncedLabel(Instant.parse("2025-12-30T05:00:00Z").toEpochMilli(), now, zone))
        assertEquals(LibrarySyncCopy.NeverSynced, lastSyncedLabel(null, now, zone))
    }

    @Test
    fun `what waits says how many changes and whether they can go now`() {
        val synced = status(waiting = 0, offline = false)
        assertEquals(LibrarySyncCopy.UpToDate, waitingLabel(synced))
        assertFalse(synced.needsAttention)

        assertEquals("2 changes waiting to sync", waitingLabel(status(waiting = 2, offline = false)))
        assertEquals(
            "1 change waiting; they'll sync when Grimmory can be reached",
            waitingLabel(status(waiting = 1, offline = true)),
        )
        assertEquals("Grimmory can't be reached now; nothing is waiting", waitingLabel(status(waiting = 0, offline = true)))
        assertEquals(LibrarySyncCopy.Syncing, waitingLabel(status(waiting = 3, offline = false).copy(syncing = true)))
        assertTrue(status(waiting = 1, offline = false).needsAttention)
    }

    @Test
    fun `books on the tablet have no sync status`() {
        assertNull(
            librarySyncStatus(
                source = SourceType.LOCAL,
                syncTimes = emptyMap(),
                pending = AppPendingRemoteSyncSummary(),
                offline = false,
                syncing = false,
            ),
        )
        val grimmory = librarySyncStatus(
            source = SourceType.GRIMMORY,
            syncTimes = mapOf(SourceType.GRIMMORY to 5L),
            pending = AppPendingRemoteSyncSummary(),
            offline = false,
            syncing = false,
        )!!
        assertEquals("Grimmory", grimmory.sourceName)
        assertEquals(5L, grimmory.lastSyncedAtEpochMillis)
        assertEquals(0, grimmory.waitingCount)
    }

    private fun status(waiting: Int, offline: Boolean) = LibrarySyncStatus(
        sourceName = "Grimmory",
        lastSyncedAtEpochMillis = 1L,
        waitingCount = waiting,
        failedCount = 0,
        offline = offline,
        syncing = false,
    )
}
