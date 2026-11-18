package com.example.unifiedreader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val FirstRetryDelayMillis = 2 * 60_000L
private const val LongestRetryDelayMillis = 30 * 60_000L

/**
 * Retries uploads that are still waiting while the app is open: two minutes after one is noticed,
 * then twice as long each time up to half an hour, starting over once the queue is empty. Without
 * it a push that failed when a book closed stayed "Waiting" until the next network change, start
 * or book close, even with the server online.
 */
internal fun CoroutineScope.launchPendingSyncRetries(
    hasPendingUploads: () -> Boolean,
    retry: () -> Unit,
): Job = launch {
    var waitMillis = FirstRetryDelayMillis
    while (isActive) {
        delay(waitMillis)
        if (hasPendingUploads()) {
            retry()
            waitMillis = (waitMillis * 2).coerceAtMost(LongestRetryDelayMillis)
        } else {
            waitMillis = FirstRetryDelayMillis
        }
    }
}
