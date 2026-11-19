package com.example.unifiedreader.feature.downloads

import android.util.Log
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.network.isConnectionFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class RemoteDownloadWorkerOutcome {
    Success,
    Failure,
    Canceled,

    /** Stopped by the system, not the user; the entry is queued again for WorkManager's rerun. */
    Stopped,

    /**
     * The connection to the server was lost part way (a timeout, a reset, no route): the entry waits for the
     * server, keeping the files and bytes it has, and WorkManager tries again after a while.
     */
    Paused,
}

/**
 * Runs one download entry: each of its files in turn, skipping those already done. A lost connection
 * ([isConnectionFailure]) pauses the entry as waiting for its server instead of failing it, and tells
 * [onConnectionLost], which marks the server offline once a check agrees; the server coming back starts the
 * entry again, and the file it was on goes on from the bytes it had. Any other error (an error answer, a
 * broken or unsupported file, a full disk) fails the entry with its message.
 */
class RemoteDownloadWorkerRunner(
    private val store: DownloadQueueStore,
    private val remoteDownloadDataSources: List<RemoteItemDownloadDataSource>,
    private val completionSideEffects: List<DownloadCompletionSideEffect>,
    private val canRunSource: (SourceType) -> Boolean = { true },
    private val onConnectionLost: (SourceType, Throwable) -> Unit = { _, _ -> },
    private val timeProvider: () -> Long = System::currentTimeMillis,
) {
    suspend fun runEntry(
        entryId: String,
    ): RemoteDownloadWorkerOutcome {
        val entry = store.getEntry(entryId) ?: return RemoteDownloadWorkerOutcome.Success
        if (entry.status == DownloadEntryStatus.Completed || entry.status == DownloadEntryStatus.Canceled) {
            return RemoteDownloadWorkerOutcome.Success
        }
        if (entry.status == DownloadEntryStatus.Canceling) {
            return finalizeCanceled(entryId)
        }
        if (!canRunSource(entry.sourceType)) {
            store.updateStatusIfCurrentStatusIn(
                entryId = entryId,
                expectedStatuses = setOf(
                    DownloadEntryStatus.Queued,
                    DownloadEntryStatus.PausedConnectivity,
                    DownloadEntryStatus.Running,
                ),
                status = DownloadEntryStatus.PausedConnectivity,
                currentItemId = entry.currentItemId,
                currentItemTitle = entry.currentItemTitle,
            )
            return RemoteDownloadWorkerOutcome.Success
        }
        return try {
            val transitionedToRunning = store.updateStatusIfCurrentStatusIn(
                entryId = entryId,
                expectedStatuses = setOf(entry.status),
                status = DownloadEntryStatus.Running,
            )
            if (!transitionedToRunning) {
                return resolveStoppedOutcome(entryId)
            }
            var completedItemIds = entry.completedItemIds.toMutableSet()
            entry.actionItems.forEach { actionItem ->
                val currentStatus = store.getEntry(entryId)?.status
                if (currentStatus == DownloadEntryStatus.Canceled || currentStatus == DownloadEntryStatus.Canceling) {
                    return finalizeCanceled(entryId)
                }
                if (actionItem.id in completedItemIds) return@forEach
                val readableItem = actionItem.toReadableItem()
                store.updateProgress(
                    entryId = entryId,
                    completedItemIds = completedItemIds,
                    currentItemId = readableItem.id,
                    currentItemTitle = readableItem.title,
                )
                val dataSource = remoteDownloadDataSources.downloadDataSourceFor(readableItem)
                    ?: error("Offline download is not supported for ${readableItem.title}.")
                dataSource.downloadToLocalStorage(readableItem)
                    ?: error("Unable to download ${readableItem.title}.")
                completedItemIds += readableItem.id
                store.updateProgress(
                    entryId = entryId,
                    completedItemIds = completedItemIds,
                    currentItemId = readableItem.id,
                    currentItemTitle = readableItem.title,
                )
                completionSideEffects.forEach { effect ->
                    if (effect.supports(readableItem)) {
                        try {
                            effect.onDownloadCompleted(readableItem)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (throwable: Throwable) {
                            Log.w(LOG_TAG, "Post-download side effect failed for ${readableItem.id}.", throwable)
                        }
                    }
                }
            }
            val transitionedToCompleted = store.updateStatusIfCurrentStatusIn(
                entryId = entryId,
                expectedStatuses = setOf(DownloadEntryStatus.Running),
                status = DownloadEntryStatus.Completed,
                completedAtEpochMillis = timeProvider(),
            )
            if (transitionedToCompleted) {
                RemoteDownloadWorkerOutcome.Success
            } else {
                resolveStoppedOutcome(entryId)
            }
        } catch (cancellation: CancellationException) {
            // Not rethrown on purpose. WorkManager cancels the worker when the user cancels a download or the
            // system stops the job; either way the entry must be settled (Canceled, or back to Queued so it
            // resumes) before the worker returns, and the returned outcome tells WorkManager what happened.
            // NonCancellable lets that last write run although this coroutine is already cancelled.
            withContext(NonCancellable) {
                finalizeStopped(entryId)
            }
        } catch (throwable: Throwable) {
            if (throwable.isConnectionFailure()) {
                pauseForLostConnection(entryId, entry.sourceType, throwable)
            } else {
                store.updateStatus(
                    entryId = entryId,
                    status = DownloadEntryStatus.Failed,
                    errorMessage = throwable.message?.takeIf(String::isNotBlank) ?: "Unknown error",
                )
                RemoteDownloadWorkerOutcome.Failure
            }
        }
    }

    private suspend fun pauseForLostConnection(
        entryId: String,
        sourceType: SourceType,
        failure: Throwable,
    ): RemoteDownloadWorkerOutcome {
        Log.i(LOG_TAG, "Connection to ${sourceType.name} lost while downloading; waiting for it.", failure)
        val current = store.getEntry(entryId)
        val paused = store.updateStatusIfCurrentStatusIn(
            entryId = entryId,
            expectedStatuses = setOf(DownloadEntryStatus.Running),
            status = DownloadEntryStatus.PausedConnectivity,
            currentItemId = current?.currentItemId,
            currentItemTitle = current?.currentItemTitle,
        )
        onConnectionLost(sourceType, failure)
        return if (paused) RemoteDownloadWorkerOutcome.Paused else resolveStoppedOutcome(entryId)
    }

    private suspend fun resolveStoppedOutcome(
        entryId: String,
    ): RemoteDownloadWorkerOutcome = when (store.getEntry(entryId)?.status) {
        DownloadEntryStatus.Canceling -> finalizeCanceled(entryId)
        DownloadEntryStatus.Canceled -> RemoteDownloadWorkerOutcome.Canceled
        DownloadEntryStatus.Failed -> RemoteDownloadWorkerOutcome.Failure
        else -> RemoteDownloadWorkerOutcome.Success
    }

    // A user cancel marks the entry Canceling (or Canceled) before WorkManager stops the work. Any
    // other stop (network constraint lost, execution time limit, Doze) leaves it Running: queue it
    // again so the rerun resumes after the files already downloaded, instead of recording a cancel.
    private suspend fun finalizeStopped(
        entryId: String,
    ): RemoteDownloadWorkerOutcome = when (store.getEntry(entryId)?.status) {
        DownloadEntryStatus.Canceling,
        DownloadEntryStatus.Canceled -> finalizeCanceled(entryId)
        else -> {
            store.updateStatusIfCurrentStatusIn(
                entryId = entryId,
                expectedStatuses = setOf(DownloadEntryStatus.Running),
                status = DownloadEntryStatus.Queued,
            )
            RemoteDownloadWorkerOutcome.Stopped
        }
    }

    private suspend fun finalizeCanceled(
        entryId: String,
    ): RemoteDownloadWorkerOutcome {
        val entry = store.getEntry(entryId)
        // A canceled download won't go on: the bytes its unfinished files kept go too.
        entry?.let { canceled -> remoteDownloadDataSources.forgetPartialDownloads(canceled) }
        val currentStatus = entry?.status
        if (currentStatus != DownloadEntryStatus.Canceled) {
            store.updateStatusIfCurrentStatusIn(
                entryId = entryId,
                expectedStatuses = setOf(
                    DownloadEntryStatus.Queued,
                    DownloadEntryStatus.PausedConnectivity,
                    DownloadEntryStatus.Running,
                    DownloadEntryStatus.Canceling,
                ),
                status = DownloadEntryStatus.Canceled,
                completedAtEpochMillis = timeProvider(),
            )
        }
        return RemoteDownloadWorkerOutcome.Canceled
    }

    private companion object {
        const val LOG_TAG = "RemoteDownloadWorker"
    }
}
