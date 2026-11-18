package com.example.unifiedreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val FirstRetryDelayMillis = 2 * 60_000L
private const val LongestRetryDelayMillis = 30 * 60_000L

/**
 * Retries while the app is open, as long as [hasWork]: uploads still waiting, or a server marked offline.
 * Two minutes after it is noticed, then twice as long each time up to half an hour, starting over once
 * there is nothing to do. Without it a push that failed when a book closed stayed "Waiting" until the
 * next network change, start or book close, even with the server online; and a server marked offline
 * by one failed request (the Wi-Fi's traffic stalling while it stays joined) stayed offline, its books
 * closed and its covers not loading, until the device changed networks.
 */
internal fun CoroutineScope.launchPendingSyncRetries(
    hasWork: () -> Boolean,
    retry: () -> Unit,
): Job = launch {
    var waitMillis = FirstRetryDelayMillis
    while (isActive) {
        delay(waitMillis)
        if (hasWork()) {
            retry()
            waitMillis = (waitMillis * 2).coerceAtMost(LongestRetryDelayMillis)
        } else {
            waitMillis = FirstRetryDelayMillis
        }
    }
}
