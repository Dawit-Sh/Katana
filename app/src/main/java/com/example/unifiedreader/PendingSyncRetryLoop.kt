package com.example.unifiedreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val FirstRetryDelayMillis = 30_000L
private const val LongestRetryDelayMillis = 5 * 60_000L

/**
 * Retries while the app is open, as long as [hasWork]: uploads still waiting, or a server marked offline.
 * Half a minute after it is noticed, then twice as long each time up to five minutes, starting over once
 * there is nothing to do. (Up to half an hour before 1.1.2: a phone whose Wi-Fi dropped for a minute
 * showed Grimmory offline for most of an hour after it came back.) Without it a push that failed when a
 * book closed stayed "Waiting" until the next network change, start or book close, even with the server
 * online; and a server marked offline
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
