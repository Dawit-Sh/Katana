package com.example.unifiedreader

import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.SourceConnectivityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSourceConnectivityPolicyTest {
    @Test
    fun `remote work gates by operation type and reachability`() {
        val policy = RemoteSourceConnectivityPolicy()

        assertEquals(SourceConnectivityState.Unknown, policy.reachabilityFor(SourceType.KAVITA))
        assertTrue(policy.canOpenRemoteItem(SourceType.KAVITA))
        assertTrue(policy.canAttemptReaderContent(SourceType.KAVITA))
        assertFalse(policy.canFetchCover(SourceType.KAVITA))
        assertFalse(policy.canScheduleDownload(SourceType.KAVITA))
        assertFalse(policy.canAttemptProgressSync(SourceType.KAVITA))

        policy.markChecking(SourceType.KAVITA)

        assertFalse(policy.canOpenRemoteItem(SourceType.KAVITA))
        assertFalse(policy.canAttemptReaderContent(SourceType.KAVITA))
        assertFalse(policy.canFetchCover(SourceType.KAVITA))
        assertFalse(policy.canScheduleDownload(SourceType.KAVITA))
        assertFalse(policy.canAttemptProgressSync(SourceType.KAVITA))

        policy.markUnavailable(SourceType.KAVITA)

        assertFalse(policy.canOpenRemoteItem(SourceType.KAVITA))
        assertFalse(policy.canAttemptReaderContent(SourceType.KAVITA))
        assertFalse(policy.canFetchCover(SourceType.KAVITA))
        assertFalse(policy.canScheduleDownload(SourceType.KAVITA))
        assertFalse(policy.canAttemptProgressSync(SourceType.KAVITA))

        policy.markReachable(SourceType.KAVITA)

        assertTrue(policy.canOpenRemoteItem(SourceType.KAVITA))
        assertTrue(policy.canAttemptReaderContent(SourceType.KAVITA))
        assertTrue(policy.canFetchCover(SourceType.KAVITA))
        assertTrue(policy.canScheduleDownload(SourceType.KAVITA))
        assertTrue(policy.canAttemptProgressSync(SourceType.KAVITA))

        assertFalse(policy.canOpenRemoteItem(SourceType.LOCAL))
        assertFalse(policy.canAttemptReaderContent(SourceType.LOCAL))
        assertFalse(policy.canAttemptProgressSync(SourceType.LOCAL))
    }

    // His Pixel, 2026-11-20: the first PDF opened after Katana started kept p. 2 though Grimmory had a newer p. 1.
    @Test
    fun `a downloaded book asks the server for its place unless the server is known to be offline`() {
        val policy = RemoteSourceConnectivityPolicy()

        assertTrue(policy.canAttemptDownloadedProgressSync(SourceType.GRIMMORY))
        policy.markChecking(SourceType.GRIMMORY)
        assertTrue(policy.canAttemptDownloadedProgressSync(SourceType.GRIMMORY))
        policy.markReachable(SourceType.GRIMMORY)
        assertTrue(policy.canAttemptDownloadedProgressSync(SourceType.GRIMMORY))
        policy.markUnavailable(SourceType.GRIMMORY)
        assertFalse(policy.canAttemptDownloadedProgressSync(SourceType.GRIMMORY))
        assertFalse(policy.canAttemptDownloadedProgressSync(SourceType.LOCAL))
    }

    @Test
    fun `explicit probes are allowed for remote sources`() {
        val policy = RemoteSourceConnectivityPolicy()

        assertTrue(policy.canRunExplicitProbe(SourceType.KAVITA))
        assertFalse(policy.canRunExplicitProbe(SourceType.LOCAL))
    }

    @Test
    fun `offline remote sources are the servers marked offline now`() {
        val policy = RemoteSourceConnectivityPolicy()
        policy.markUnavailable(SourceType.GRIMMORY)
        policy.markChecking(SourceType.KAVITA)
        policy.markReachable(SourceType.STUMP)

        assertEquals(setOf(SourceType.GRIMMORY), policy.offlineRemoteSources())

        policy.markReachable(SourceType.GRIMMORY)

        assertEquals(emptySet<SourceType>(), policy.offlineRemoteSources())
    }
}
