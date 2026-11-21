package com.example.unifiedreader

import com.example.unifiedreader.core.model.SourceConnectivityState
import com.example.unifiedreader.core.model.SourceType
import javax.inject.Inject

/**
 * Refreshes every signed-in server's library by itself, whichever library is on screen, so books added on
 * the server show up without a trip to Settings, and places, reading statuses, reading history and marks
 * read on another device come down even while this one shows its own files. (Before 1.2.3 only the library
 * on screen refreshed: a device left on Local never heard from Grimmory.) The app calls [refreshIfDue] each
 * time the library shows (the app opening or coming back, another source picked, a reader or a sheet closed)
 * and when a server answers again after being offline; a server refreshes once it last synced
 * [LIBRARY_AUTO_REFRESH_AFTER_MILLIS] ago or more, and not sooner after a try that failed. The refresh stays
 * in place: it never changes the library on screen.
 *
 * Kavita and Grimmory only: their refresh saves just the books that changed. Stump's loads every
 * series again, so it waits for Refresh library. A server that is offline, not connected or
 * already syncing is left alone.
 */
class AppLibraryAutoRefreshCoordinator internal constructor(
    private val lastSyncedAt: (SourceType) -> Long?,
    private val nowEpochMillis: () -> Long,
) {
    @Inject constructor(sourceSyncTimes: SourceSyncTimeStore) : this(
        lastSyncedAt = sourceSyncTimes::lastSyncedAt,
        nowEpochMillis = System::currentTimeMillis,
    )

    private val lastTriedAt = mutableMapOf<SourceType, Long>()

    fun refreshIfDue(
        state: AppUiState,
        refresh: (SourceType) -> Unit,
    ) {
        AutoRefreshedSources.filter { source -> isDue(state, source) }.forEach { source ->
            lastTriedAt[source] = nowEpochMillis()
            refresh(source)
        }
    }

    private fun isDue(state: AppUiState, source: SourceType): Boolean {
        val connection = state.remoteConnection(source)
        val free = connection != null && connection.hasStoredSession && !connection.isConnecting
        val reachable = state.sourceConnectivityBySource[source] != SourceConnectivityState.Offline
        val lastActivity = listOfNotNull(lastSyncedAt(source), lastTriedAt[source]).maxOrNull()
        val waited = lastActivity == null || nowEpochMillis() - lastActivity >= LIBRARY_AUTO_REFRESH_AFTER_MILLIS
        return free && reachable && waited
    }
}

/**
 * Which servers just answered again after being offline (Offline, then Checking, then Online), so they catch
 * up at once instead of at the next time the library shows: Wi-Fi back on, the device home again.
 */
internal class ServersBackOnline {
    private val wentOffline = mutableSetOf<SourceType>()

    fun next(states: Map<SourceType, SourceConnectivityState>): Set<SourceType> {
        states.forEach { (source, state) -> if (state == SourceConnectivityState.Offline) wentOffline += source }
        val back = wentOffline.filterTo(mutableSetOf()) { source -> states[source] == SourceConnectivityState.Online }
        wentOffline -= back
        return back
    }
}

/** How long after a sync the library refreshes again by itself. */
internal const val LIBRARY_AUTO_REFRESH_AFTER_MILLIS = 15 * 60 * 1000L

private val AutoRefreshedSources = setOf(SourceType.KAVITA, SourceType.GRIMMORY)
