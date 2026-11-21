package com.example.unifiedreader

import com.example.unifiedreader.core.model.ReadStatus
import com.example.unifiedreader.core.model.ReadStatusRecord
import com.example.unifiedreader.core.model.ReadStatusStore
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.readStatusChoices
import javax.inject.Inject

/**
 * A reading status picked on a book's page. Read is Mark finished and Unread is Mark unread
 * ([ApplyReadStatusChangeUseCase]: the place, the journal and the status). The others only set the status:
 * the place stays where it is (Re-reading too, as on Katana Desk). Each waits to go to the book's server, if
 * it has one; a book that syncs nowhere keeps it here.
 */
class SetReadStatusUseCase @Inject constructor(
    private val applyReadStatusChange: ApplyReadStatusChangeUseCase,
    private val readStatusStore: ReadStatusStore,
) {
    /** False when [item] doesn't take [status] (a series, or Read and Unread on Stump). */
    suspend operator fun invoke(item: ReadableItem, status: ReadStatus): Boolean = when {
        status !in item.readStatusChoices() -> false
        status.movesPlace -> applyReadStatusChange(item = item, markAsRead = status == ReadStatus.READ).applied
        else -> {
            readStatusStore.put(item.id, ReadStatusRecord(status = status, setAtEpochMillis = System.currentTimeMillis(), unsent = true))
            true
        }
    }
}
