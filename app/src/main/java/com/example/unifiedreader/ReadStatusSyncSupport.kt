package com.example.unifiedreader

import com.example.unifiedreader.core.common.ApplicationScope
import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.ReadStatus
import com.example.unifiedreader.core.model.ReadStatusPlace
import com.example.unifiedreader.core.model.ReadStatusRecord
import com.example.unifiedreader.core.model.ReadStatusStore
import com.example.unifiedreader.core.model.ServerReadStatus
import com.example.unifiedreader.core.model.ServerReadStatusStep
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.decideServerReadStatus
import com.example.unifiedreader.core.model.placeReadStatus
import com.example.unifiedreader.core.model.readStatusAfterReading
import com.example.unifiedreader.feature.annotations.isLocalBookId
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A place as the status rules read it, the way the library does: started once any of it is read, finished
 * when the reader said so or at the very end by its share (a PDF's last page is 100% before Next finishes
 * it, and the Finished shelf already counts it). An unread reset is no place.
 */
internal fun ReaderProgressSnapshot.toReadStatusPlace(): ReadStatusPlace {
    val atEnd = (percent ?: 0.0) >= FINISHED_SHARE
    val finished = !isExplicitUnreadReset && (isFinished || atEnd)
    val started = when (locatorType) {
        LocatorType.PAGE -> page != null
        LocatorType.CFI, LocatorType.PERCENT -> (percent ?: 0.0) > 0.0
    }
    return ReadStatusPlace(hasPlace = !isExplicitUnreadReset && (finished || started), isFinished = finished)
}

/** The share read that counts as the end (the library's Finished shelf goes by it too). */
private const val FINISHED_SHARE = 0.999

/** A book's status on its server, as a sync pulls it: the server's word, and when it was set if the server says. */
internal data class ServerBookStatus(
    val itemId: String,
    val status: String?,
    val setAtEpochMillis: Long? = null,
)

/**
 * What a pull does with the servers' [statuses] ([decideServerReadStatus] book by book): the ones to keep
 * replace this device's, the ones the server already has stop waiting. Each change is made only while the
 * book's record is still the one decided on, so a status picked meanwhile is never lost. [placeOf] is the
 * book's place here; [placeUnsent] says whether it waits to go up (its server's status is then from before
 * it). Returns the books whose status here waits to go up.
 */
internal fun ReadStatusStore.applyServerStatuses(
    statuses: List<ServerBookStatus>,
    placeOf: (itemId: String) -> ReaderProgressSnapshot?,
    placeUnsent: (itemId: String) -> Boolean,
    nowEpochMillis: Long,
): Set<String> {
    val toSend = mutableSetOf<String>()
    statuses.forEach { server ->
        val local = get(server.itemId)
        val step = decideServerReadStatus(
            local = local,
            statusFromPlace = placeReadStatus(placeOf(server.itemId)?.toReadStatusPlace()),
            server = ServerReadStatus(ReadStatus.fromServer(server.status), server.setAtEpochMillis),
            localPlaceUnsent = placeUnsent(server.itemId),
            nowEpochMillis = nowEpochMillis,
        )
        when (step) {
            ServerReadStatusStep.Keep -> Unit
            ServerReadStatusStep.Send -> toSend += server.itemId
            is ServerReadStatusStep.Take -> replaceIfCurrent(server.itemId, local, step.record)
            is ServerReadStatusStep.Settled -> replaceIfCurrent(server.itemId, local, step.record)
        }
    }
    return toSend
}

/**
 * The statuses reading here changes between two looks at the places ([before], [after]), as
 * [readStatusAfterReading] says. Only books with a status of their own change (one that shows its place's
 * follows the place by itself), only places saved after that status was set (a server's place applied after
 * its status is the server's own reading), and only places this device saved: a server's book's reading here
 * waits to go up (not [SyncState.CLEAN]); a file here (whose PDF and comic places are saved in step) counts
 * whatever its state. The new status waits to go up, stamped with the reading's time.
 */
internal fun readStatusChangesFromReading(
    before: Map<String, ReaderProgressSnapshot>,
    after: Map<String, ReaderProgressSnapshot>,
    records: Map<String, ReadStatusRecord>,
): Map<String, ReadStatusRecord> = records.mapNotNull { (itemId, record) ->
    val was = before[itemId]
    after[itemId]
        ?.takeIf { now ->
            !now.isSamePlaceAs(was) &&
                now.updatedAtEpochMillis >= record.setAtEpochMillis &&
                (now.syncState != SyncState.CLEAN || isLocalBookId(itemId))
        }
        ?.let { now ->
            readStatusAfterReading(record.status, was?.toReadStatusPlace(), now.toReadStatusPlace())?.let { status ->
                itemId to ReadStatusRecord(status = status, setAtEpochMillis = now.updatedAtEpochMillis, unsent = true)
            }
        }
}.toMap()

// The same place saved at the same time: only its sync state moved (a push going up), which is no reading.
private fun ReaderProgressSnapshot.isSamePlaceAs(other: ReaderProgressSnapshot?): Boolean =
    other != null && copy(syncState = other.syncState) == other

/**
 * Reading here moves a book's status on as Katana Desk does ([readStatusChangesFromReading]): finishing makes
 * it Read, reading an Unread book (or going back into a Read one) makes it Reading. It follows the places from
 * the moment they are all loaded, so the places loaded at start are not taken for reading.
 */
@Singleton
class ReadStatusAutoUpdates @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val readerSessionStore: ReaderSessionStore,
    private val store: ReadStatusStore,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            readerSessionStore.isHydratedFlow.first { it }
            var before = readerSessionStore.progressByItemIdFlow.value
            readerSessionStore.progressByItemIdFlow.collect { after ->
                val records = store.records.value
                // Each only while its status is still the one read: one picked meanwhile stays.
                readStatusChangesFromReading(before, after, records).forEach { (itemId, record) ->
                    store.replaceIfCurrent(itemId, records[itemId], record)
                }
                before = after
            }
        }
    }
}
