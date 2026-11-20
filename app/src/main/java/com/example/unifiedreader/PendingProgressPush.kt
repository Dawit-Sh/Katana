package com.example.unifiedreader

import com.example.unifiedreader.core.model.ReadingProgress
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.progress.ProgressSyncSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore

/** What pushing one queued progress save came to. */
internal sealed interface PendingProgressPush {
    /** Sent: the save is now the server's copy. */
    data object Pushed : PendingProgressPush

    /** Not sent: the server's copy is the more recent read and replaces the save. */
    data class ServerNewer(val serverCopy: ReaderProgressSnapshot) : PendingProgressPush

    /** Not sent, and left queued for a later push. */
    data object Skipped : PendingProgressPush
}

/**
 * What becomes of this queued save, given the server's copy: null to push it. The most recent read wins,
 * by the rule a pull follows ([decideRemoteProgressSync]); no server copy, or one without a date, never
 * beats a local save. A server copy that wins ([PendingProgressPush.ServerNewer]) replaces the save,
 * unless it is behind this device's place: then the save stays queued and the server's place waits as an
 * offer the book asks about before it opens ([BackwardProgressOffers]).
 */
internal fun ReaderProgressSnapshot.serverAnswer(
    remote: ReadingProgress?,
    localRefinesRemote: (ProgressSyncSnapshot, ReadingProgress?) -> Boolean = { _, _ -> false },
    nowEpochMillis: Long = System.currentTimeMillis(),
): PendingProgressPush? {
    val local = ProgressSyncSnapshot(
        itemId = itemId,
        locatorType = locatorType,
        page = page,
        cfi = cfi,
        percent = percent,
        isFinished = isFinished,
        isExplicitUnreadReset = isExplicitUnreadReset,
        syncState = syncState,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
    val decision = decideRemoteProgressSync(
        local = local,
        remote = remote,
        nowEpochMillis = nowEpochMillis,
        localRefinesRemote = localRefinesRemote,
    ).askingBeforeGoingBack(local)
    if (decision !is RemoteProgressSyncDecision.OfferRemote) BackwardProgressOffers.withdraw(itemId)
    return when (decision) {
        is RemoteProgressSyncDecision.OfferRemote -> {
            BackwardProgressOffers.offer(decision.toOffer(itemId))
            PendingProgressPush.Skipped
        }
        is RemoteProgressSyncDecision.ApplyRemote -> PendingProgressPush.ServerNewer(
            ReaderProgressSnapshot(
                itemId = itemId,
                locatorType = decision.locator.locatorType,
                page = decision.locator.page,
                cfi = decision.locator.cfi,
                percent = decision.locator.percent,
                isFinished = decision.isFinished,
                syncState = SyncState.CLEAN,
                updatedAtEpochMillis = decision.updatedAtEpochMillis,
            ),
        )
        else -> null
    }
}

/** Records how pushing [progress] went, unless a newer local save replaced it meanwhile. */
internal suspend fun ReaderSessionStore.settlePendingProgress(
    progress: ReaderProgressSnapshot,
    push: PendingProgressPush,
) {
    val replacement = when (push) {
        PendingProgressPush.Pushed -> progress.copy(syncState = SyncState.CLEAN)
            .takeUnless { progress.isExplicitUnreadReset }
        is PendingProgressPush.ServerNewer -> push.serverCopy
        PendingProgressPush.Skipped -> return
    }
    val settled = replaceProgressIfCurrent(expected = progress, replacement = replacement)
    if (!settled && push == PendingProgressPush.Pushed && replacement != null) settleSamePlaceSavedMeanwhile(progress)
}

/**
 * The reader saving the very same place again while the push of [sent] was out (the Fire does as a book
 * closes) saved what was sent, so it is in step too. Left unsent, it went again on the next open and, saved
 * under two minutes before another device's newer read, put this device's older place back over it.
 */
private suspend fun ReaderSessionStore.settleSamePlaceSavedMeanwhile(sent: ReaderProgressSnapshot) {
    val current = progressByItemIdFlow.value[sent.itemId]
    if (current != null && current.syncState == SyncState.DIRTY && current.isSamePlaceAs(sent)) {
        replaceProgressIfCurrent(expected = current, replacement = current.copy(syncState = SyncState.CLEAN))
    }
}

// The same place, finish and reset: everything but when it was saved and whether it was sent.
private fun ReaderProgressSnapshot.isSamePlaceAs(other: ReaderProgressSnapshot): Boolean =
    copy(syncState = other.syncState, updatedAtEpochMillis = other.updatedAtEpochMillis) == other
