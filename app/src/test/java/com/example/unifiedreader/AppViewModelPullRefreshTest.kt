package com.example.unifiedreader

import android.os.Looper
import com.example.unifiedreader.core.model.SourceConnectivityState
import com.example.unifiedreader.core.model.SourceType
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Pulling the library down, against fake servers: the cloud's Sync now, and what offline does. */
@RunWith(RobolectricTestRunner::class)
class AppViewModelPullRefreshTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a pull on Grimmory runs the library's sync, in place, with the indicator until it ends`() {
        val gate = CompletableDeferred<Unit>()
        val syncer = FakeGrimmoryCatalogSyncer(syncGate = gate)
        val viewModel = createViewModel(
            grimmoryCatalogSyncer = syncer,
            grimmoryCredentialStore = FakeGrimmoryCredentialStore(savedSession()),
        )
        idle()
        viewModel.openLibrarySource(SourceType.GRIMMORY)
        idle()

        viewModel.libraryPullRefresh.pull()
        idle()

        // The cloud menu's Sync now: the library's refresh, which keeps you where you are.
        assertEquals(1, syncer.refreshCount)
        assertNull(syncer.lastSyncRequest)
        assertTrue(viewModel.libraryPullRefresh.isRefreshing)
        assertTrue(viewModel.uiState.libraryRefreshTopBarState().isRefreshing)
        gate.complete(Unit)
        idle()
        assertFalse(viewModel.libraryPullRefresh.isRefreshing)
        assertEquals(false, viewModel.uiState.grimmoryConnection.isConnecting)
        assertEquals(SourceType.GRIMMORY, viewModel.uiState.selectedSource)
        assertEquals(TopLevelDestination.Library, viewModel.uiState.topLevel)
        clearViewModel(viewModel)
    }

    @Test
    fun `a pull while the library refreshes by itself starts no second sync`() {
        val gate = CompletableDeferred<Unit>()
        val syncer = FakeGrimmoryCatalogSyncer(syncGate = gate)
        val viewModel = createViewModel(
            grimmoryCatalogSyncer = syncer,
            grimmoryCredentialStore = FakeGrimmoryCredentialStore(savedSession()),
        )
        idle()
        viewModel.openLibrarySource(SourceType.GRIMMORY)
        idle()
        viewModel.onLibraryShown()
        idle()
        // The refresh by itself shows no pull indicator (the ring on ••• says it).
        assertFalse(viewModel.libraryPullRefresh.isRefreshing)

        viewModel.libraryPullRefresh.pull()
        idle()

        assertEquals(1, syncer.refreshCount)
        assertTrue(viewModel.libraryPullRefresh.isRefreshing)
        gate.complete(Unit)
        idle()
        assertFalse(viewModel.libraryPullRefresh.isRefreshing)
        clearViewModel(viewModel)
    }

    @Test
    fun `offline, a pull checks Grimmory and syncs once it answers`() {
        val policy = RemoteSourceConnectivityPolicy().apply { markUnavailable(SourceType.GRIMMORY) }
        val healthCheck = PullTestHealthCheck(SourceType.GRIMMORY, reachable = true)
        val syncer = FakeGrimmoryCatalogSyncer()
        val viewModel = createViewModel(
            grimmoryCatalogSyncer = syncer,
            grimmoryCredentialStore = FakeGrimmoryCredentialStore(savedSession()),
            remoteSourceConnectivityPolicy = policy,
            remoteSourceHealthChecks = listOf(healthCheck),
        )
        idle()
        viewModel.openLibrarySource(SourceType.GRIMMORY)
        idle()
        assertEquals(SourceConnectivityState.Offline, viewModel.uiState.selectedSourceConnectivity)

        viewModel.libraryPullRefresh.pull()
        idle()

        assertEquals(1, healthCheck.callCount)
        assertEquals(1, syncer.refreshCount)
        assertEquals(SourceConnectivityState.Online, policy.reachabilityFor(SourceType.GRIMMORY))
        clearViewModel(viewModel)
    }

    @Test
    fun `offline and still out of reach, a pull syncs nothing and Grimmory stays offline`() {
        val policy = RemoteSourceConnectivityPolicy().apply { markUnavailable(SourceType.GRIMMORY) }
        val healthCheck = PullTestHealthCheck(SourceType.GRIMMORY, reachable = false)
        val syncer = FakeGrimmoryCatalogSyncer()
        val viewModel = createViewModel(
            grimmoryCatalogSyncer = syncer,
            grimmoryCredentialStore = FakeGrimmoryCredentialStore(savedSession()),
            remoteSourceConnectivityPolicy = policy,
            remoteSourceHealthChecks = listOf(healthCheck),
        )
        idle()
        viewModel.openLibrarySource(SourceType.GRIMMORY)
        idle()

        viewModel.libraryPullRefresh.pull()
        idle()

        assertEquals(1, healthCheck.callCount)
        assertEquals(0, syncer.refreshCount)
        assertFalse(viewModel.libraryPullRefresh.isRefreshing)
        assertEquals(SourceConnectivityState.Offline, policy.reachabilityFor(SourceType.GRIMMORY))
        clearViewModel(viewModel)
    }

    @Test
    fun `the library on this device has no pull`() {
        val syncer = FakeGrimmoryCatalogSyncer()
        val viewModel = createViewModel(
            grimmoryCatalogSyncer = syncer,
            grimmoryCredentialStore = FakeGrimmoryCredentialStore(savedSession()),
        )
        idle()
        viewModel.openLibrarySource(SourceType.LOCAL)
        idle()

        assertFalse(viewModel.libraryPullRefresh.canPull)
        viewModel.libraryPullRefresh.pull()
        idle()
        assertEquals(0, syncer.refreshCount)
        assertEquals(0, syncer.syncCount)
        clearViewModel(viewModel)
    }

    private fun savedSession() = GrimmoryStoredSession(
        displayName = "Home Grimmory",
        baseUrl = "https://grimmory.example",
        username = "reader",
        accessToken = "saved-access-token",
        refreshToken = "saved-refresh-token",
    )
}

private class PullTestHealthCheck(
    override val sourceType: SourceType,
    private val reachable: Boolean,
) : RemoteSourceHealthCheck {
    var callCount = 0
        private set

    override suspend fun check(): RemoteSourceHealthCheckResult {
        callCount += 1
        if (!reachable) throw IOException("No route to host")
        return RemoteSourceHealthCheckResult.Online
    }
}
