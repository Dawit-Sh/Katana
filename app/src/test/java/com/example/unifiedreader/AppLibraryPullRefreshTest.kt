package com.example.unifiedreader

import com.example.unifiedreader.core.model.SourceConnectivityState
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.feature.settings.RemoteSourceConnectionUiState
import com.example.unifiedreader.feature.settings.ServerConnectionAction
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppLibraryPullRefreshTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private var state = grimmory(connectivity = SourceConnectivityState.Online)
    private var syncs = 0
    private val checks = mutableListOf<SourceType>()
    private var checkAnswer = CompletableDeferred<RemoteSourceConnectivityProbeResult>()

    // Sync now, as the view model's: the connection starts syncing at once.
    private val pull = AppLibraryPullRefresh(
        state = { state },
        checkServer = { source ->
            checks += source
            checkAnswer.await()
        },
        sync = {
            syncs += 1
            state = state.syncing(true)
        },
        scope = scope,
    )

    @Test
    fun `a pull on a server's library is the cloud's Sync now, and the indicator turns until it ends`() {
        pull.pull()

        assertEquals(1, syncs)
        assertTrue(pull.isRefreshing)
        // Pulled again while it syncs: nothing more starts.
        pull.pull()
        assertEquals(1, syncs)

        state = state.syncing(false)
        assertFalse(pull.isRefreshing)
        pull.settle()
        // The library refreshing by itself afterwards shows no indicator.
        state = state.syncing(true)
        assertFalse(pull.isRefreshing)
    }

    @Test
    fun `a pull while the library already refreshes follows that sync instead of starting another`() {
        state = state.syncing(true)

        pull.pull()

        assertEquals(0, syncs)
        assertTrue(pull.isRefreshing)
        state = state.syncing(false)
        assertFalse(pull.isRefreshing)
    }

    @Test
    fun `offline, a pull checks the server first and syncs once it answers`() {
        state = grimmory(connectivity = SourceConnectivityState.Offline)

        pull.pull()

        assertEquals(listOf(SourceType.GRIMMORY), checks)
        assertEquals(0, syncs)
        assertTrue(pull.isRefreshing)
        checkAnswer.complete(RemoteSourceConnectivityProbeResult.Online)
        scope.runCurrent()
        assertEquals(1, syncs)
        assertTrue(pull.isRefreshing)
    }

    @Test
    fun `offline and still out of reach, the indicator goes back up and nothing syncs`() {
        state = grimmory(connectivity = SourceConnectivityState.Offline)

        pull.pull()
        checkAnswer.complete(RemoteSourceConnectivityProbeResult.Offline(IOException("no route")))
        scope.runCurrent()

        assertEquals(0, syncs)
        assertFalse(pull.isRefreshing)
        pull.settle()
        // The next pull checks again.
        checkAnswer = CompletableDeferred()
        pull.pull()
        assertEquals(2, checks.size)
    }

    @Test
    fun `a check that answers after you left that library syncs nothing`() {
        state = grimmory(connectivity = SourceConnectivityState.Offline)

        pull.pull()
        state = state.copy(selectedSource = SourceType.LOCAL)
        checkAnswer.complete(RemoteSourceConnectivityProbeResult.Online)
        scope.runCurrent()

        assertEquals(0, syncs)
        assertFalse(pull.isRefreshing)
    }

    @Test
    fun `books on this device and a server not signed in have no pull`() {
        state = state.copy(selectedSource = SourceType.LOCAL)
        assertFalse(pull.canPull)
        pull.pull()

        state = AppUiState(selectedSource = SourceType.GRIMMORY)
        assertFalse(pull.canPull)
        pull.pull()

        assertEquals(0, syncs)
        assertTrue(checks.isEmpty())
    }

    @Test
    fun `every server's library can be pulled`() {
        listOf(SourceType.KAVITA, SourceType.STUMP, SourceType.GRIMMORY).forEach { source ->
            state = connectedEverywhere().copy(selectedSource = source)
            assertTrue(source.name, pull.canPull)
        }
    }

    private fun grimmory(connectivity: SourceConnectivityState) = connectedEverywhere().copy(
        selectedSource = SourceType.GRIMMORY,
        sourceConnectivityBySource = mapOf(SourceType.GRIMMORY to connectivity),
    )

    private fun connectedEverywhere() = AppUiState(
        kavitaConnection = RemoteSourceConnectionUiState(source = SourceType.KAVITA, hasStoredSession = true),
        stumpConnection = RemoteSourceConnectionUiState(source = SourceType.STUMP, hasStoredSession = true),
        grimmoryConnection = RemoteSourceConnectionUiState(source = SourceType.GRIMMORY, hasStoredSession = true),
    )

    private fun AppUiState.syncing(running: Boolean) = copy(
        grimmoryConnection = grimmoryConnection.copy(
            isConnecting = running,
            activeAction = if (running) ServerConnectionAction.FastSync else null,
        ),
    )
}
