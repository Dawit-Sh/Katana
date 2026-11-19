package com.example.unifiedreader

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.unifiedreader.feature.downloads.DownloadCompletionSideEffect
import com.example.unifiedreader.feature.downloads.DownloadWorkScheduler
import com.example.unifiedreader.feature.downloads.RemoteDownloadWorkerOutcome
import com.example.unifiedreader.feature.downloads.RemoteDownloadWorkerRunner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

class WorkManagerDownloadScheduler(
    private val workManager: WorkManager,
) : DownloadWorkScheduler {
    override fun scheduleEntry(
        entryId: String,
    ) {
        workManager.enqueueUniqueWork(
            uniqueWorkName(entryId),
            ExistingWorkPolicy.KEEP,
            remoteDownloadWorkRequest(entryId, Build.VERSION.SDK_INT),
        )
    }

    override fun cancelEntry(
        entryId: String,
    ) {
        workManager.cancelUniqueWork(uniqueWorkName(entryId))
    }
}

class SourceProgressSyncDownloadCompletionSideEffect(
    private val sourceProgressSyncs: List<SourceProgressSync>,
) : DownloadCompletionSideEffect {
    override fun supports(
        item: com.example.unifiedreader.core.model.ReadableItem,
    ): Boolean = sourceProgressSyncs.any { sync -> sync.supports(item) }

    override suspend fun onDownloadCompleted(
        item: com.example.unifiedreader.core.model.ReadableItem,
    ) {
        sourceProgressSyncs.firstOrNull { sync -> sync.supports(item) }
            ?.syncItem(item)
    }
}

class RemoteDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val entryId = inputData.getString(EntryIdKey) ?: return Result.failure()
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            RemoteDownloadWorkerEntryPoint::class.java,
        )
        return when (entryPoint.remoteDownloadWorkerRunner().runEntry(entryId)) {
            RemoteDownloadWorkerOutcome.Success,
            RemoteDownloadWorkerOutcome.Canceled -> Result.success()

            RemoteDownloadWorkerOutcome.Failure -> Result.failure()

            // WorkManager ignores the result of work it stopped and reruns it itself; retry covers
            // a cancellation that didn't come from a stop.
            RemoteDownloadWorkerOutcome.Stopped,

            // The connection was lost: try again after the backoff, once a network is there. If the server
            // is marked offline by then, the rerun leaves the entry waiting and ends; the server coming back
            // schedules it again.
            RemoteDownloadWorkerOutcome.Paused -> Result.retry()
        }
    }

    companion object {
        const val EntryIdKey = "entry_id"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RemoteDownloadWorkerEntryPoint {
    fun remoteDownloadWorkerRunner(): RemoteDownloadWorkerRunner
}

/**
 * Expedited only on Android 12 and later, where it is a plain expedited job. Before 12 WorkManager runs
 * expedited work as a foreground service and asks the worker for its notification, which
 * [RemoteDownloadWorker] doesn't have (CoroutineWorker throws "Not implemented"), so every queued
 * download on Android 9-11 failed before it started.
 */
internal fun remoteDownloadWorkRequest(
    entryId: String,
    sdkInt: Int,
): OneTimeWorkRequest = OneTimeWorkRequestBuilder<RemoteDownloadWorker>()
    .setInputData(workDataOf(RemoteDownloadWorker.EntryIdKey to entryId))
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build(),
    )
    // After a lost connection: 30 s, 1 min, 1.5 min... rather than doubling to hours, since each try goes
    // on from the bytes it has.
    .setBackoffCriteria(BackoffPolicy.LINEAR, DOWNLOAD_RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
    .apply {
        if (sdkInt >= Build.VERSION_CODES.S) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    }
    .build()

private const val DOWNLOAD_RETRY_BACKOFF_SECONDS = 30L

private fun uniqueWorkName(
    entryId: String,
): String = "remote-download-entry-$entryId"
