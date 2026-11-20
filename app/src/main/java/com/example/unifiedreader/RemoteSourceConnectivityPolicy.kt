package com.example.unifiedreader

import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.SourceConnectivityState
import com.example.unifiedreader.core.model.displayName
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Checks a server once more after one of its requests found no connection, before the app shows it offline:
 * on patchy Wi-Fi one slow reply shouldn't stop covers, books and downloads.
 */
fun interface RemoteSourceOfflineCheck {
    /** Checks [sourceType] again without showing it; [onResult] gets true when it still can't be reached. */
    fun recheck(
        sourceType: SourceType,
        onResult: (stillUnreachable: Boolean) -> Unit,
    )

    companion object {
        /** No second check: the first failure marks the server offline, as the app did before. */
        val None = RemoteSourceOfflineCheck { _, onResult -> onResult(true) }
    }
}

/**
 * Whether each server can be reached now, for the whole app: the library bar's Offline, covers, opening books
 * that aren't downloaded, downloads and syncing all follow it. Kept in memory only, so every server is Unknown
 * after a start until something reaches it.
 *
 * A request that finds no connection marks a server offline only after [offlineCheck] agrees, with nothing
 * reaching the server in between ([markFromFailure]); a server already offline stays so.
 */
@Singleton
class RemoteSourceConnectivityPolicy @Inject constructor(
    private val offlineCheck: RemoteSourceOfflineCheck,
) {
    /** A policy that marks a server offline on its first failed request (tests, stand-ins). */
    constructor() : this(RemoteSourceOfflineCheck.None)

    private val reachabilityBySource = MutableStateFlow<Map<SourceType, SourceConnectivityState>>(emptyMap())
    val reachabilityFlow: StateFlow<Map<SourceType, SourceConnectivityState>> = reachabilityBySource.asStateFlow()

    // The servers being checked again now, so failures meanwhile don't start more checks.
    private val recheckingSources: MutableSet<SourceType> = ConcurrentHashMap.newKeySet()

    // How often each server was reached, to see whether a request succeeded while a check ran.
    private val reachedCounts = ConcurrentHashMap<SourceType, Long>()

    fun reachabilityFor(
        sourceType: SourceType,
    ): SourceConnectivityState = reachabilityBySource.value[sourceType] ?: SourceConnectivityState.Unknown

    fun isReachable(
        sourceType: SourceType,
    ): Boolean = reachabilityFor(sourceType) == SourceConnectivityState.Online

    fun isUnavailable(
        sourceType: SourceType,
    ): Boolean = reachabilityFor(sourceType) == SourceConnectivityState.Offline

    /** The servers marked offline now, which a retry checks again. */
    fun offlineRemoteSources(): Set<SourceType> =
        reachabilityBySource.value.entries.mapNotNullTo(linkedSetOf()) { (sourceType, state) ->
            sourceType.takeIf { sourceType != SourceType.LOCAL && state == SourceConnectivityState.Offline }
        }

    fun canAttemptReaderContent(
        sourceType: SourceType,
    ): Boolean = sourceType != SourceType.LOCAL && when (reachabilityFor(sourceType)) {
        SourceConnectivityState.Checking,
        SourceConnectivityState.Offline -> false
        SourceConnectivityState.Online,
        SourceConnectivityState.Unknown -> true
    }

    fun canOpenRemoteItem(
        sourceType: SourceType,
    ): Boolean = canAttemptReaderContent(sourceType)

    fun canAttemptRemoteOpen(
        sourceType: SourceType,
    ): Boolean = canAttemptReaderContent(sourceType)

    fun canFetchCover(
        sourceType: SourceType,
    ): Boolean = isReachable(sourceType)

    fun canScheduleDownload(
        sourceType: SourceType,
    ): Boolean = isReachable(sourceType)

    fun canRunExplicitProbe(
        sourceType: SourceType,
    ): Boolean = sourceType != SourceType.LOCAL

    /**
     * A downloaded book opens after a short, bounded wait for its place, so it asks the server unless that is
     * known to be offline: the first book opened after Katana starts, before anything has reached the server,
     * asks too. (Before 1.1.2 it opened at the place on this device without asking.)
     */
    fun canAttemptDownloadedProgressSync(
        sourceType: SourceType,
    ): Boolean = sourceType != SourceType.LOCAL && reachabilityFor(sourceType) != SourceConnectivityState.Offline

    fun canFlushDownloadedStartupProgress(
        sourceType: SourceType,
    ): Boolean = canAttemptProgressSync(sourceType)

    fun canAttemptProgressSync(
        sourceType: SourceType,
    ): Boolean = sourceType != SourceType.LOCAL && isReachable(sourceType)

    fun canFlushStartupProgress(
        sourceType: SourceType,
        hasStoredDocument: Boolean,
    ): Boolean = if (hasStoredDocument) {
        canFlushDownloadedStartupProgress(sourceType)
    } else {
        canAttemptProgressSync(sourceType)
    }

    fun markReachable(
        sourceType: SourceType,
    ) {
        reachedCounts.merge(sourceType, 1L, Long::plus)
        reachabilityBySource.update { current ->
            if (current[sourceType] == SourceConnectivityState.Online) {
                current
            } else {
                current + (sourceType to SourceConnectivityState.Online)
            }
        }
    }

    fun markUnavailable(
        sourceType: SourceType,
    ) {
        reachabilityBySource.update { current ->
            if (current[sourceType] == SourceConnectivityState.Offline) {
                current
            } else {
                current + (sourceType to SourceConnectivityState.Offline)
            }
        }
    }

    fun markChecking(
        sourceType: SourceType,
    ) {
        reachabilityBySource.update { current ->
            if (current[sourceType] == SourceConnectivityState.Checking) {
                current
            } else {
                current + (sourceType to SourceConnectivityState.Checking)
            }
        }
    }

    fun clear(
        sourceType: SourceType,
    ) {
        reachabilityBySource.update { current ->
            current - sourceType
        }
    }

    fun markFromFailure(
        sourceType: SourceType,
        throwable: Throwable,
    ) {
        if (throwable.isRemoteServerAddressRetryableFailure()) {
            markUnreachable(sourceType)
        } else {
            markReachable(sourceType)
        }
    }

    /**
     * A request to [sourceType] found no connection. A server already offline stays so; any other is checked
     * once more, and shown offline when that check fails too and no request reached it meanwhile. One check
     * at a time per server: failures while it runs wait for its answer.
     */
    fun markUnreachable(
        sourceType: SourceType,
    ) {
        if (reachabilityFor(sourceType) == SourceConnectivityState.Offline) return
        if (!recheckingSources.add(sourceType)) return
        val reachedBefore = reachedCounts[sourceType] ?: 0L
        offlineCheck.recheck(sourceType) { stillUnreachable ->
            recheckingSources.remove(sourceType)
            if (stillUnreachable && (reachedCounts[sourceType] ?: 0L) == reachedBefore) {
                markUnavailable(sourceType)
            }
        }
    }

    fun recordReaderContentSuccess(
        sourceType: SourceType,
    ) {
        markReachable(sourceType)
    }

    fun recordReaderContentFailure(
        sourceType: SourceType,
        throwable: Throwable,
    ) {
        markFromFailure(sourceType, throwable)
    }

    fun recordProgressSyncSuccess(
        sourceType: SourceType,
    ) {
        markReachable(sourceType)
    }

    fun recordProgressSyncFailure(
        sourceType: SourceType,
        throwable: Throwable,
    ) {
        markFromFailure(sourceType, throwable)
    }

    fun markFromSyncFailures(
        attemptedSourceTypes: Set<SourceType>,
        failures: List<SourceProgressSyncFailure>,
    ) {
        val networkFailureSourceTypes = failures.asSequence()
            .filter { failure -> failure.throwable.isRemoteServerAddressRetryableFailure() }
            .map(SourceProgressSyncFailure::sourceType)
            .toSet()
        attemptedSourceTypes.forEach { sourceType ->
            if (sourceType in networkFailureSourceTypes) {
                markUnreachable(sourceType)
            } else {
                markReachable(sourceType)
            }
        }
    }
}

class RemoteSourceUnavailableException(
    sourceType: SourceType,
) : RuntimeException(
    "${sourceType.displayName()} local network cannot be accessed. Only downloaded or cached items can be opened.",
)
