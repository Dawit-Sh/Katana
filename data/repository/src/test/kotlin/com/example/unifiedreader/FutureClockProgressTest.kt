package com.example.unifiedreader

import com.example.unifiedreader.core.model.FUTURE_CLOCK_LIMIT_MILLIS
import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.ReadingProgress
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.progress.ProgressSyncSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A clock days ahead, on the server or here, never wins "the most recent read" for good. */
class FutureClockProgressTest {
    private val now = 1_800_000_000_000L
    private val day = FUTURE_CLOCK_LIMIT_MILLIS
    private val year = 365 * day

    private fun local(page: Int, at: Long, state: SyncState) = ProgressSyncSnapshot(
        itemId = "book-1",
        locatorType = LocatorType.PAGE,
        page = page,
        percent = page / 100.0,
        syncState = state,
        updatedAtEpochMillis = at,
    )

    private fun remote(page: Int, at: Long) = ReadingProgress(
        itemId = "book-1",
        locatorType = LocatorType.PAGE,
        page = page,
        percent = page / 100.0,
        updatedAtEpochMillis = at,
    )

    @Test
    fun `a server copy stamped years ahead doesn't win over a place here`() {
        val decision = decideRemoteProgressSync(local(40, now - 60_000, SyncState.CLEAN), remote(10, now + year), now)

        assertEquals(RemoteProgressSyncDecision.KeepLocal, decision)
    }

    @Test
    fun `a server copy stamped less than a day ahead still counts, a clock a little off is fine`() {
        val decision = decideRemoteProgressSync(local(40, now - 60_000, SyncState.CLEAN), remote(50, now + day / 2), now)

        assertTrue(decision is RemoteProgressSyncDecision.ApplyRemote)
    }

    @Test
    fun `a server place stamped ahead comes down where there is none here, stamped now, not in the future`() {
        val decision = decideRemoteProgressSync(local = null, remote = remote(10, now + year), nowEpochMillis = now)

        assertEquals(now, (decision as RemoteProgressSyncDecision.ApplyRemote).updatedAtEpochMillis)
    }

    @Test
    fun `a place here in step but stamped years ahead gives way to the server's next read`() {
        val decision = decideRemoteProgressSync(local(40, now + year, SyncState.CLEAN), remote(55, now - 60_000), now)

        val applied = decision as RemoteProgressSyncDecision.ApplyRemote
        assertEquals("the server's true time replaces the wrong one", now - 60_000, applied.updatedAtEpochMillis)
    }

    @Test
    fun `an unsent place here stamped years ahead counts as now, so an older server copy doesn't replace it`() {
        val decision = decideRemoteProgressSync(local(40, now + year, SyncState.DIRTY), remote(55, now - 60 * 60_000), now)

        assertEquals(RemoteProgressSyncDecision.KeepLocal, decision)
    }

    @Test
    fun `a server copy that wins over a wrongly stamped place behind it is still asked about, not applied`() {
        val here = local(40, now + year, SyncState.CLEAN)
        val decision = decideRemoteProgressSync(here, remote(10, now - 60_000), now).askingBeforeGoingBack(here)

        assertTrue(decision is RemoteProgressSyncDecision.OfferRemote)
    }
}
