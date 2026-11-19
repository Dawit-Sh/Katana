package com.example.unifiedreader

import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.ReadingProgress
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.progress.ProgressSyncLocator
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackwardProgressOffersTest {
    @Before
    fun start() = BackwardProgressOffers.clear()

    @After
    fun end() = BackwardProgressOffers.clear()

    private val queued = ReaderProgressSnapshot(
        itemId = "grimmory:1",
        locatorType = LocatorType.CFI,
        page = 8,
        cfi = "/6/8",
        percent = 0.09,
        syncState = SyncState.DIRTY,
        updatedAtEpochMillis = 1_000L,
    )

    @Test
    fun `a queued save stays queued while the server's newer place is behind it`() {
        val behind = ReadingProgress(
            itemId = "grimmory:1",
            locatorType = LocatorType.CFI,
            page = 4,
            cfi = "/6/4",
            percent = 0.06,
            updatedAtEpochMillis = 1_000_000L,
        )

        val answer = queued.serverAnswer(remote = behind)

        assertEquals(PendingProgressPush.Skipped, answer)
        val offer = BackwardProgressOffers.current("grimmory:1")
        assertNotNull(offer)
        assertEquals(4, offer!!.server.page)
        assertEquals(1_000_000L, offer.serverUpdatedAtEpochMillis)
    }

    @Test
    fun `a server place ahead still replaces the save and ends any offer`() {
        BackwardProgressOffers.offer(
            BackwardProgressOffer(
                itemId = "grimmory:1",
                server = ProgressSyncLocator(LocatorType.PAGE, page = 2),
                serverFinished = false,
                serverUpdatedAtEpochMillis = 5L,
            ),
        )
        val ahead = ReadingProgress(
            itemId = "grimmory:1",
            locatorType = LocatorType.CFI,
            page = 20,
            cfi = "/6/20",
            percent = 0.2,
            updatedAtEpochMillis = 1_000_000L,
        )

        val answer = queued.serverAnswer(remote = ahead)

        assertTrue(answer is PendingProgressPush.ServerNewer)
        assertNull(BackwardProgressOffers.current("grimmory:1"))
    }

    @Test
    fun `an older server place lets the save go out`() {
        val older = ReadingProgress(
            itemId = "grimmory:1",
            locatorType = LocatorType.CFI,
            page = 4,
            percent = 0.06,
            updatedAtEpochMillis = 500L,
        )

        assertNull(queued.serverAnswer(remote = older))
        assertNull(BackwardProgressOffers.current("grimmory:1"))
    }

    @Test
    fun `places read as pages, or as a share of the book`() {
        assertEquals("p. 8", progressPlaceLabel(page = 8, percent = 0.09, pageCount = 90))
        assertEquals("p. 5", progressPlaceLabel(page = null, percent = 0.06, pageCount = 90))
        assertEquals("6%", progressPlaceLabel(page = null, percent = 0.06, pageCount = null))
        assertNull(progressPlaceLabel(page = null, percent = null, pageCount = 90))
    }
}
