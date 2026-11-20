package com.example.unifiedreader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingSyncRetryLoopTest {
    @Test
    fun `retries while there is work, half a minute first, twice as long each time, five minutes at most`() = runTest {
        val retriedAt = mutableListOf<Long>()
        backgroundScope.launchPendingSyncRetries(
            hasWork = { true },
            retry = { retriedAt += currentTime / SECOND },
        )

        advanceTimeBy(18 * MINUTE)
        runCurrent()

        // 0:30, 1:30, 3:30, 7:30, then every five minutes: 12:30, 17:30.
        assertEquals(listOf(30L, 90L, 210L, 450L, 750L, 1050L), retriedAt)
    }

    // A server marked offline by one stalled request is checked again, though nothing waits to upload.
    @Test
    fun `starts over at half a minute once there is nothing to do, and waits while there is none`() = runTest {
        var hasWork = true
        val retriedAt = mutableListOf<Long>()
        backgroundScope.launchPendingSyncRetries(
            hasWork = { hasWork },
            retry = {
                retriedAt += currentTime / SECOND
                if (retriedAt.size == 2) hasWork = false
            },
        )

        advanceTimeBy(10 * MINUTE)
        runCurrent()
        hasWork = true
        advanceTimeBy(1 * MINUTE)
        runCurrent()

        // 0:30 and 1:30, then a quiet check at 3:30 resets the wait to half a minute; work again at 10:00 is
        // retried at the next half-minute check.
        assertEquals(listOf(30L, 90L, 630L), retriedAt)
    }
}

private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
