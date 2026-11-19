package com.example.unifiedreader

import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.ReadingProgress
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.progress.ProgressSyncLocator
import com.example.unifiedreader.core.model.progress.ProgressSyncSnapshot
import com.example.unifiedreader.core.model.progress.ProgressSyncStore

sealed interface RemoteProgressSyncDecision {
    data object KeepLocal : RemoteProgressSyncDecision

    data object ClearLocal : RemoteProgressSyncDecision

    data class ApplyRemote(
        val locator: ProgressSyncLocator,
        val isFinished: Boolean,
        val updatedAtEpochMillis: Long,
    ) : RemoteProgressSyncDecision

    /**
     * The server's copy is the more recent read but sits behind this device's place: keep this device's
     * and ask before going back (KOReader asks the same way), instead of moving the book back on its own.
     */
    data class OfferRemote(
        val locator: ProgressSyncLocator,
        val isFinished: Boolean,
        val updatedAtEpochMillis: Long,
    ) : RemoteProgressSyncDecision
}

fun decideRemoteProgressSync(
    local: ProgressSyncSnapshot?,
    remote: ReadingProgress?,
    nowEpochMillis: Long,
    missingRemoteClearsLocal: Boolean = false,
    remoteClearOverridesLocal: Boolean = true,
    localRefinesRemote: (local: ProgressSyncSnapshot, remote: ReadingProgress?) -> Boolean = { _, _ -> false },
): RemoteProgressSyncDecision {
    // A source that stores positions coarsely can report the local position back less precisely.
    if (local != null && localRefinesRemote(local, remote)) {
        return RemoteProgressSyncDecision.KeepLocal
    }
    if (remote == null) {
        if (!missingRemoteClearsLocal) {
            return RemoteProgressSyncDecision.KeepLocal
        }
        return when {
            local == null -> RemoteProgressSyncDecision.KeepLocal
            local.syncState == SyncState.CLEAN -> RemoteProgressSyncDecision.ClearLocal
            remoteClearOverridesLocal -> RemoteProgressSyncDecision.ClearLocal
            else -> RemoteProgressSyncDecision.KeepLocal
        }
    }

    if (local == null) {
        return remote.toApplyRemoteDecision(
            updatedAtEpochMillis = remote.updatedAtEpochMillis ?: nowEpochMillis,
        )
    }

    val remoteUpdatedAtEpochMillis = remote.updatedAtEpochMillis
    if (remoteUpdatedAtEpochMillis == null) {
        return RemoteProgressSyncDecision.KeepLocal
    }

    // The most recent read wins. A save not yet pushed has to lose by a margin: see the tolerance.
    val pendingLocalTolerance = if (local.syncState == SyncState.CLEAN) {
        0L
    } else {
        PENDING_LOCAL_PROGRESS_CLOCK_TOLERANCE_MILLIS
    }
    return if (remoteUpdatedAtEpochMillis > local.updatedAtEpochMillis + pendingLocalTolerance) {
        remote.toApplyRemoteDecision(updatedAtEpochMillis = remoteUpdatedAtEpochMillis)
    } else {
        RemoteProgressSyncDecision.KeepLocal
    }
}

/**
 * How much newer a server copy has to be than a local save that isn't pushed yet to replace it. The
 * server stamps each push with its own clock when the push lands, so this device's previous push
 * can look newer than its next save: when the server clock runs ahead, or when a page turns while
 * a push is in flight. A read on another device made after that still wins.
 */
const val PENDING_LOCAL_PROGRESS_CLOCK_TOLERANCE_MILLIS = 2 * 60 * 1000L

/**
 * This decision, except that a server copy that wins while sitting behind [local]'s place is offered
 * ([RemoteProgressSyncDecision.OfferRemote]) instead of applied: the book asks before going back.
 */
fun RemoteProgressSyncDecision.askingBeforeGoingBack(local: ProgressSyncSnapshot?): RemoteProgressSyncDecision {
    val apply = this as? RemoteProgressSyncDecision.ApplyRemote
    return if (apply != null && local != null && isBehind(apply.locator.percent, apply.isFinished, local)) {
        RemoteProgressSyncDecision.OfferRemote(
            locator = apply.locator,
            isFinished = apply.isFinished,
            updatedAtEpochMillis = apply.updatedAtEpochMillis,
        )
    } else {
        this
    }
}

/**
 * Whether a server place ([percent] of the book, [isFinished]) sits behind [local]: not finished where
 * [local] is, or further back by more than [BACKWARD_PROGRESS_MARGIN]. A place without a percentage can't
 * be compared, so it isn't.
 */
fun isBehind(percent: Double?, isFinished: Boolean, local: ProgressSyncSnapshot): Boolean {
    val localPercent = local.percent
    return when {
        local.isFinished && !isFinished -> true
        isFinished || percent == null || localPercent == null -> false
        else -> percent < localPercent - BACKWARD_PROGRESS_MARGIN
    }
}

/** Half a percent of the book: a server copy closer than this to this device's place is not "going back". */
const val BACKWARD_PROGRESS_MARGIN = 0.005

fun applyRemoteProgressSyncDecision(
    store: ProgressSyncStore,
    item: ReadableItem,
    pageCount: Int?,
    decision: RemoteProgressSyncDecision,
) {
    when (decision) {
        RemoteProgressSyncDecision.KeepLocal -> Unit
        // The caller holds the offer until you choose (BackwardProgressOffers); the place stays.
        is RemoteProgressSyncDecision.OfferRemote -> Unit
        RemoteProgressSyncDecision.ClearLocal -> store.clearProgress(item.id)
        is RemoteProgressSyncDecision.ApplyRemote -> store.saveProgress(
            item = item,
            locator = decision.locator,
            isFinished = decision.isFinished,
            syncState = SyncState.CLEAN,
            pageCount = pageCount,
            updatedAtEpochMillis = decision.updatedAtEpochMillis,
        )
    }
}

fun ReadingProgress.toProgressSyncLocator(): ProgressSyncLocator = ProgressSyncLocator(
    locatorType = locatorType,
    page = page,
    cfi = cfi,
    percent = percent,
)

private fun ReadingProgress.toApplyRemoteDecision(
    updatedAtEpochMillis: Long,
): RemoteProgressSyncDecision.ApplyRemote = RemoteProgressSyncDecision.ApplyRemote(
    locator = toProgressSyncLocator(),
    isFinished = isFinished,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
