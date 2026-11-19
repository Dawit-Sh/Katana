package com.example.unifiedreader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.unifiedreader.core.model.SourceConnectivityState
import com.example.unifiedreader.core.model.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pulling the library down, on a server's library (Grimmory, Kavita, Stump; books on this device have
 * nothing to fetch, so no pull): the same work as the cloud menu's Sync now, [sync] (the book list,
 * the uploads that wait, reading places, highlights). A sync already running (the library's own
 * refresh, Settings' Sync now) is not started again: the pull's indicator follows it to its end.
 *
 * On a server marked offline the pull first checks it, as a tap on Offline does (the field's pill says
 * Checking), and syncs once it answers; still out of reach, the indicator goes back up and Offline
 * stays: being offline is a state, not an error. The indicator turns for a pull only: the library
 * refreshing by itself keeps to the ring on "•••", as before.
 */
@Stable
internal class AppLibraryPullRefresh(
    private val state: () -> AppUiState,
    private val checkServer: suspend (SourceType) -> RemoteSourceConnectivityProbeResult,
    private val sync: () -> Unit,
    private val scope: CoroutineScope,
) {
    // The library a pull is under way in, and whether its check of an offline server runs.
    private var pulledSource by mutableStateOf<SourceType?>(null)
    private var checking by mutableStateOf(false)

    /** Whether the library on screen can be pulled: a server with a saved sign-in. */
    val canPull: Boolean
        get() = state().libraryRefreshTopBarState().isVisible

    /** Whether the pull's indicator turns: its check, then the sync it started or joined. */
    val isRefreshing: Boolean
        get() {
            val current = state()
            return pulledSource == current.selectedSource &&
                (checking || current.libraryRefreshTopBarState().isRefreshing)
        }

    fun pull() {
        val current = state()
        if (!canPull || isRefreshing) return
        val source = current.selectedSource
        val refresh = current.libraryRefreshTopBarState()
        pulledSource = source
        when {
            // A sync of this library runs already, or the server is signing in: nothing more to start.
            refresh.isRefreshing || !refresh.isEnabled -> Unit
            current.selectedSourceConnectivity == SourceConnectivityState.Offline -> {
                checking = true
                scope.launch {
                    val result = try {
                        checkServer(source)
                    } finally {
                        checking = false
                    }
                    // Still in that library, and it answers: sync it now.
                    if (result == RemoteSourceConnectivityProbeResult.Online && state().selectedSource == source) sync()
                }
            }
            else -> sync()
        }
    }

    /** The indicator is back up: the pull is over, so the library refreshing by itself later shows none. */
    fun settle() {
        if (!isRefreshing) pulledSource = null
    }
}
