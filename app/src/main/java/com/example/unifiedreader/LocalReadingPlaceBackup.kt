package com.example.unifiedreader

import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.clampedToNowIfBeyondLimit
import com.example.unifiedreader.feature.annotations.LocalReadingPlace
import com.example.unifiedreader.feature.annotations.LocalReadingPlaces
import com.example.unifiedreader.feature.annotations.isLocalBookId
import com.example.unifiedreader.feature.annotations.isNewerPlace
import com.example.unifiedreader.feature.reader.core.ReaderLocator
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore

/**
 * The reading places of books from files, for Settings › Back up and Restore. A server's books are left out:
 * their place comes from the server. A restored place never replaces a newer one on this device.
 */
internal class LocalReadingPlaceBackup(
    private val sessions: ReaderSessionStore,
    private val hydrate: suspend () -> Unit,
    private val localItems: suspend () -> List<ReadableItem>,
    private val now: () -> Long = System::currentTimeMillis,
) : LocalReadingPlaces {
    override suspend fun places(): List<LocalReadingPlace> {
        hydrate()
        return sessions.progressByItemIdFlow.value.values
            .filter { isLocalBookId(it.itemId) && !it.isExplicitUnreadReset }
            .map(ReaderProgressSnapshot::toPlace)
    }

    override suspend fun restore(place: LocalReadingPlace): Boolean {
        hydrate()
        val item = if (isLocalBookId(place.itemId)) localItems().firstOrNull { it.id == place.itemId } else null
        val type = LocatorType.entries.firstOrNull { it.name == place.locatorType }
        val at = now()
        val current = sessions.progressByItemIdFlow.value[place.itemId]
        if (item == null || type == null || !isNewerPlace(place.updatedAtEpochMillis, current?.updatedAtEpochMillis, at)) {
            return false
        }
        sessions.saveProgress(
            item = item,
            locator = ReaderLocator(locatorType = type, page = place.page, cfi = place.cfi, percent = place.percent),
            isFinished = place.isFinished,
            syncState = SyncState.CLEAN,
            updatedAtEpochMillis = place.updatedAtEpochMillis.clampedToNowIfBeyondLimit(at),
        )
        return true
    }
}

private fun ReaderProgressSnapshot.toPlace() = LocalReadingPlace(
    itemId = itemId,
    locatorType = locatorType.name,
    page = page,
    cfi = cfi,
    percent = percent,
    isFinished = isFinished,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
