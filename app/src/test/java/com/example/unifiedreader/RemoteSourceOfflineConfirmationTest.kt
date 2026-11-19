package com.example.unifiedreader

import com.example.unifiedreader.core.model.SourceConnectivityState
import com.example.unifiedreader.core.model.SourceType
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One slow reply on patchy Wi-Fi shouldn't show a server offline (covers stop, books that aren't downloaded
 * won't open), and a real outage should still show within seconds.
 */
class RemoteSourceOfflineConfirmationTest {
    @Test
    fun `one slow reply doesn't show an online server offline`() {
        val check = PendingOfflineCheck()
        val policy = RemoteSourceConnectivityPolicy(check).apply { markReachable(SourceType.GRIMMORY) }

        policy.markFromFailure(SourceType.GRIMMORY, SocketTimeoutException("timeout"))

        assertEquals(SourceConnectivityState.Online, policy.reachabilityFor(SourceType.GRIMMORY))
        check.answer(stillUnreachable = false)
        assertEquals(SourceConnectivityState.Online, policy.reachabilityFor(SourceType.GRIMMORY))
    }

    @Test
    fun `a server is shown offline once the second look can't reach it either`() {
        val check = PendingOfflineCheck()
        val policy = RemoteSourceConnectivityPolicy(check).apply { markReachable(SourceType.GRIMMORY) }

        policy.markFromFailure(SourceType.GRIMMORY, ConnectException("Connection refused"))
        check.answer(stillUnreachable = true)

        assertEquals(SourceConnectivityState.Offline, policy.reachabilityFor(SourceType.GRIMMORY))
    }

    @Test
    fun `a request that gets through while the check runs keeps the server online`() {
        val check = PendingOfflineCheck()
        val policy = RemoteSourceConnectivityPolicy(check).apply { markReachable(SourceType.GRIMMORY) }

        policy.markFromFailure(SourceType.GRIMMORY, SocketTimeoutException("timeout"))
        policy.recordReaderContentSuccess(SourceType.GRIMMORY)
        check.answer(stillUnreachable = true)

        assertEquals(SourceConnectivityState.Online, policy.reachabilityFor(SourceType.GRIMMORY))
    }

    @Test
    fun `failures while a check runs wait for it instead of starting more`() {
        val check = PendingOfflineCheck()
        val policy = RemoteSourceConnectivityPolicy(check).apply { markReachable(SourceType.GRIMMORY) }

        repeat(5) { policy.markFromFailure(SourceType.GRIMMORY, SocketTimeoutException("timeout")) }

        assertEquals(1, check.pending.size)
        check.answer(stillUnreachable = true)
        assertEquals(SourceConnectivityState.Offline, policy.reachabilityFor(SourceType.GRIMMORY))

        // Online again later: the next lost request gets its own check.
        policy.markReachable(SourceType.GRIMMORY)
        policy.markFromFailure(SourceType.GRIMMORY, SocketTimeoutException("timeout"))
        assertEquals(1, check.pending.size)
    }

    @Test
    fun `a server already offline stays offline without another check`() {
        val check = PendingOfflineCheck()
        val policy = RemoteSourceConnectivityPolicy(check).apply { markUnavailable(SourceType.GRIMMORY) }

        policy.markFromFailure(SourceType.GRIMMORY, SocketTimeoutException("timeout"))

        assertTrue(check.pending.isEmpty())
        assertEquals(SourceConnectivityState.Offline, policy.reachabilityFor(SourceType.GRIMMORY))
    }

    @Test
    fun `sync failures that lost the connection go through the same check`() {
        val check = PendingOfflineCheck()
        val policy = RemoteSourceConnectivityPolicy(check).apply { markReachable(SourceType.GRIMMORY) }

        policy.markFromSyncFailures(
            attemptedSourceTypes = setOf(SourceType.GRIMMORY),
            failures = listOf(SourceProgressSyncFailure(SourceType.GRIMMORY, IOException("Connect timeout has expired"))),
        )

        assertEquals(SourceConnectivityState.Online, policy.reachabilityFor(SourceType.GRIMMORY))
        check.answer(stillUnreachable = true)
        assertEquals(SourceConnectivityState.Offline, policy.reachabilityFor(SourceType.GRIMMORY))
    }

    @Test
    fun `without a check the first failure marks the server offline as before`() {
        val policy = RemoteSourceConnectivityPolicy().apply { markReachable(SourceType.GRIMMORY) }

        policy.markFromFailure(SourceType.GRIMMORY, SocketTimeoutException("timeout"))

        assertEquals(SourceConnectivityState.Offline, policy.reachabilityFor(SourceType.GRIMMORY))
    }

    @Test
    fun `the health check runs once more a moment later, and a server that answers stays online`() = runTest {
        val healthCheck = ScriptedHealthCheck(RemoteSourceHealthCheckResult.Online)
        val policy = RemoteSourceConnectivityPolicy(HealthCheckOfflineRecheck(scope = this, healthChecks = listOf(healthCheck)))
        policy.markReachable(SourceType.GRIMMORY)

        policy.markFromFailure(SourceType.GRIMMORY, SocketTimeoutException("timeout"))
        advanceTimeBy(OFFLINE_RECHECK_DELAY_MILLIS - 1)
        runCurrent()
        assertEquals(0, healthCheck.callCount)

        advanceUntilIdle()

        assertEquals(1, healthCheck.callCount)
        assertEquals(SourceConnectivityState.Online, policy.reachabilityFor(SourceType.GRIMMORY))
    }

    @Test
    fun `a real outage shows offline about a second and a half after the first failure`() = runTest {
        val healthCheck = ScriptedHealthCheck(ConnectException("Network is unreachable"))
        val policy = RemoteSourceConnectivityPolicy(HealthCheckOfflineRecheck(scope = this, healthChecks = listOf(healthCheck)))
        policy.markReachable(SourceType.GRIMMORY)

        policy.markFromFailure(SourceType.GRIMMORY, ConnectException("Network is unreachable"))
        advanceUntilIdle()

        assertEquals(SourceConnectivityState.Offline, policy.reachabilityFor(SourceType.GRIMMORY))
        assertEquals(OFFLINE_RECHECK_DELAY_MILLIS, testScheduler.currentTime)
    }

    @Test
    fun `a server that answers the second look with an error is not offline`() = runTest {
        val healthCheck = ScriptedHealthCheck(IllegalStateException("Unauthorized"))
        val policy = RemoteSourceConnectivityPolicy(HealthCheckOfflineRecheck(scope = this, healthChecks = listOf(healthCheck)))
        policy.markReachable(SourceType.GRIMMORY)

        policy.markFromFailure(SourceType.GRIMMORY, SocketTimeoutException("timeout"))
        advanceUntilIdle()

        assertEquals(SourceConnectivityState.Online, policy.reachabilityFor(SourceType.GRIMMORY))
    }

    @Test
    fun `a probe that loses the connection once checks again before showing the server offline`() = runTest {
        val healthCheck = ScriptedHealthCheck(SocketTimeoutException("timeout"), RemoteSourceHealthCheckResult.Online)
        val policy = RemoteSourceConnectivityPolicy()
        val coordinator = RemoteSourceConnectivityStartupCoordinator(listOf(healthCheck), policy)

        val result = coordinator.retrySource(SourceType.GRIMMORY)

        assertEquals(RemoteSourceConnectivityProbeResult.Online, result)
        assertEquals(SourceConnectivityState.Online, policy.reachabilityFor(SourceType.GRIMMORY))
        assertEquals(2, healthCheck.callCount)
    }

    @Test
    fun `a probe that can't reach the server twice shows it offline`() = runTest {
        val healthCheck = ScriptedHealthCheck(SocketTimeoutException("timeout"), SocketTimeoutException("timeout"))
        val policy = RemoteSourceConnectivityPolicy().apply { markReachable(SourceType.GRIMMORY) }
        val coordinator = RemoteSourceConnectivityStartupCoordinator(listOf(healthCheck), policy)

        val result = coordinator.retrySource(SourceType.GRIMMORY)

        assertTrue(result is RemoteSourceConnectivityProbeResult.Offline)
        assertEquals(SourceConnectivityState.Offline, policy.reachabilityFor(SourceType.GRIMMORY))
        assertEquals(2, healthCheck.callCount)
    }

    @Test
    fun `retrying a server already offline answers after one check`() = runTest {
        val healthCheck = ScriptedHealthCheck(SocketTimeoutException("timeout"))
        val policy = RemoteSourceConnectivityPolicy().apply { markUnavailable(SourceType.GRIMMORY) }
        val coordinator = RemoteSourceConnectivityStartupCoordinator(listOf(healthCheck), policy)

        coordinator.retrySource(SourceType.GRIMMORY)

        assertEquals(SourceConnectivityState.Offline, policy.reachabilityFor(SourceType.GRIMMORY))
        assertEquals(1, healthCheck.callCount)
        assertEquals(0L, testScheduler.currentTime)
    }
}

/** Holds each recheck until the test answers it. */
private class PendingOfflineCheck : RemoteSourceOfflineCheck {
    val pending = mutableListOf<Pair<SourceType, (Boolean) -> Unit>>()

    override fun recheck(
        sourceType: SourceType,
        onResult: (stillUnreachable: Boolean) -> Unit,
    ) {
        pending += sourceType to onResult
    }

    fun answer(stillUnreachable: Boolean) {
        pending.removeAt(0).second(stillUnreachable)
    }
}

/** Answers each check with the next of [answers] (a result or an error to throw); the last one repeats. */
private class ScriptedHealthCheck(
    vararg answers: Any,
    override val sourceType: SourceType = SourceType.GRIMMORY,
) : RemoteSourceHealthCheck {
    private val answers = answers.toList()
    var callCount = 0
        private set

    override suspend fun check(): RemoteSourceHealthCheckResult {
        val answer = answers[minOf(callCount, answers.lastIndex)]
        callCount += 1
        if (answer is Throwable) throw answer
        return answer as RemoteSourceHealthCheckResult
    }
}
