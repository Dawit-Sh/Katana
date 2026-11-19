package com.example.unifiedreader

import com.example.unifiedreader.core.common.ApplicationScope
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.runSuspendCatching
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's [RemoteSourceOfflineCheck]: a moment after a request found no connection, the server's own health
 * check runs once more, the one the retry timer runs (for Grimmory: its libraries with the saved sign-in,
 * renewed only when the server says it expired). Nothing on screen changes while it runs, and the server is
 * shown offline only when this check can't reach it either.
 */
@Singleton
class HealthCheckOfflineRecheck @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val healthChecks: List<@JvmSuppressWildcards RemoteSourceHealthCheck>,
) : RemoteSourceOfflineCheck {
    override fun recheck(
        sourceType: SourceType,
        onResult: (stillUnreachable: Boolean) -> Unit,
    ) {
        val healthCheck = healthChecks.firstOrNull { check -> check.sourceType == sourceType }
        if (healthCheck == null) {
            onResult(true)
            return
        }
        scope.launch {
            var stillUnreachable = false
            try {
                // A dropped packet or a Wi-Fi stall of a second or two passes in this time.
                delay(OFFLINE_RECHECK_DELAY_MILLIS)
                stillUnreachable = healthCheck.findsNoConnection()
            } finally {
                onResult(stillUnreachable)
            }
        }
    }
}

/**
 * Runs the health check once: true when it can't reach the server (a lost connection), false when the server
 * answered, even with an error (a sign-in to renew is not an outage).
 */
internal suspend fun RemoteSourceHealthCheck.findsNoConnection(): Boolean =
    runSuspendCatching { check() }.exceptionOrNull()?.isRemoteServerAddressRetryableFailure() == true

/** How long after a failed request the server is checked again before it's shown offline. */
internal const val OFFLINE_RECHECK_DELAY_MILLIS = 1_500L

/** The check behind [RemoteSourceConnectivityPolicy]'s offline marks; kept out of [AppModule], which should not grow. */
@Module
@InstallIn(SingletonComponent::class)
object AppConnectivityModule {
    @Provides
    @Singleton
    fun provideRemoteSourceOfflineCheck(
        recheck: HealthCheckOfflineRecheck,
    ): RemoteSourceOfflineCheck = recheck
}
