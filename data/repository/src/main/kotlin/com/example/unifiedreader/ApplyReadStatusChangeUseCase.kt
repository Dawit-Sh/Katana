package com.example.unifiedreader

import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.ReadStatus
import com.example.unifiedreader.core.model.ReadStatusRecord
import com.example.unifiedreader.core.model.ReadStatusStore
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.supportsManualReadStatusAction
import com.example.unifiedreader.core.model.progress.ProgressSyncLocator
import com.example.unifiedreader.core.model.progress.ProgressSyncStore
import com.example.unifiedreader.journal.JournalProgressInput
import com.example.unifiedreader.journal.JournalRepository
import javax.inject.Inject

data class ReadStatusChangeResult(
    val applied: Boolean,
)

/**
 * Mark finished and Mark unread: the book's place (finished, or cleared) waits to go to its server, the
 * journal follows, and its reading status becomes Read or Unread, waiting to go up too ([ReadStatusStore]).
 */
class ApplyReadStatusChangeUseCase @Inject constructor(
    private val progressSyncStore: ProgressSyncStore,
    private val journalRepository: JournalRepository? = null,
    private val readStatusStore: ReadStatusStore? = null,
) {
    suspend operator fun invoke(
        item: ReadableItem,
        markAsRead: Boolean,
    ): ReadStatusChangeResult {
        if (!item.supportsManualReadStatusAction()) {
            return ReadStatusChangeResult(applied = false)
        }
        val updatedAtEpochMillis = System.currentTimeMillis()
        // The status first: the place saved next then reads as this very change, not as reading here.
        readStatusStore?.put(
            itemId = item.id,
            record = ReadStatusRecord(
                status = if (markAsRead) ReadStatus.READ else ReadStatus.UNREAD,
                setAtEpochMillis = updatedAtEpochMillis,
                unsent = true,
            ),
        )
        applyLocalReadStatus(
            item = item,
            markAsRead = markAsRead,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
        updateLocalJournalReadStatus(
            item = item,
            markAsRead = markAsRead,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
        return ReadStatusChangeResult(applied = true)
    }

    private fun applyLocalReadStatus(
        item: ReadableItem,
        markAsRead: Boolean,
        updatedAtEpochMillis: Long,
    ) {
        progressSyncStore.saveProgress(
            item = item,
            locator = ProgressSyncLocator(
                locatorType = LocatorType.PERCENT,
                percent = if (markAsRead) 1.0 else 0.0,
            ),
            isFinished = markAsRead,
            isExplicitUnreadReset = !markAsRead,
            syncState = SyncState.DIRTY,
            pageCount = item.pageCount,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
        if (!markAsRead) {
            progressSyncStore.clearLocalReaderPosition(item)
        }
    }

    private suspend fun updateLocalJournalReadStatus(
        item: ReadableItem,
        markAsRead: Boolean,
        updatedAtEpochMillis: Long,
    ) {
        val repository = journalRepository ?: return
        if (markAsRead) {
            repository.recordReaderProgress(
                JournalProgressInput(
                    item = item,
                    page = item.pageCount,
                    percent = 1.0,
                    pageCount = item.pageCount,
                    isFinished = true,
                    occurredAtEpochMillis = updatedAtEpochMillis,
                ),
            )
        } else {
            // A finish from the last day was a mistake to undo; an older one is a read worth keeping,
            // and the book's reading days stay either way.
            repository.takeBackFinishesSince(item.id, updatedAtEpochMillis - MarkUnreadTakeBackMillis)
        }
    }
}

private const val MarkUnreadTakeBackMillis = 24L * 60 * 60 * 1000
