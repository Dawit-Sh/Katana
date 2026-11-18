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
    fun `retries while there is work, two minutes first and twice as long each time`() = runTest {
        val retriedAt = mutableListOf<Long>()
        backgroundScope.launchPendingSyncRetries(
            hasWork = { true },
            retry = { retriedAt += currentTime / MINUTE },
        )

        advanceTimeBy(15 * MINUTE)
        runCurrent()

        assertEquals(listOf(2L, 6L, 14L), retriedAt)
    }

    // A server marked offline by one stalled request is checked again, though nothing waits to upload.
    @Test
    fun `starts over at two minutes once there is nothing to do, and waits while there is none`() = runTest {
        var hasWork = true
        val retriedAt = mutableListOf<Long>()
        backgroundScope.launchPendingSyncRetries(
            hasWork = { hasWork },
            retry = {
                retriedAt += currentTime / MINUTE
                if (retriedAt.size == 2) hasWork = false
            },
        )

        advanceTimeBy(30 * MINUTE)
        runCurrent()
        hasWork = true
        advanceTimeBy(4 * MINUTE)
        runCurrent()

        // 2 and 6, then a quiet check at 14 resets the wait; the next due check is two minutes after the last.
        assertEquals(listOf(2L, 6L, 32L), retriedAt)
    }
}

private const val MINUTE = 60_000L
