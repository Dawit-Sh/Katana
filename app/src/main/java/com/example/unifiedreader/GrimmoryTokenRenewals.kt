package com.example.unifiedreader

import com.example.unifiedreader.data.grimmory.GrimmoryApi
import com.example.unifiedreader.data.grimmory.GrimmorySession
import com.example.unifiedreader.data.grimmory.GrimmorySignedOutException
import com.example.unifiedreader.data.grimmory.KtorGrimmoryApi
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Every Grimmory sign-in renewal in the app goes through here. Grimmory replaces the refresh token at each
 * renewal and turns the old one down from then on; it also turns the new one down for about its first minute,
 * and five turned-down renewals lock this device out of renewing for 15 minutes. So:
 * - one renewal at a time;
 * - a token already renewed here is answered with what it was renewed to, without asking the server again
 *   (a sync, an upload and a cover can all find the access token expired at once);
 * - the new tokens are saved the moment they arrive, so a sync or upload that fails after renewing (the
 *   Wi-Fi dropping) can't lose them, which would leave only the dead old ones and sign the device out;
 * - a token the server turned down isn't sent again: only signing in with the password helps then.
 */
@Singleton
class GrimmoryTokenRenewals @Inject constructor(
    private val credentialStore: GrimmoryCredentialStore,
) {
    private val mutex = Mutex()
    private val renewedTo = BoundedMap<String, GrimmorySession>()
    private val refused = BoundedMap<String, Unit>()

    suspend fun renew(
        refreshToken: String,
        renewWithServer: suspend (refreshToken: String) -> GrimmorySession,
    ): GrimmorySession = mutex.withLock {
        latestRenewalOf(refreshToken)?.let { renewed -> return@withLock renewed }
        if (refreshToken in refused) throw GrimmorySignedOutException()
        val renewed = try {
            renewWithServer(refreshToken)
        } catch (signedOut: GrimmorySignedOutException) {
            refused[refreshToken] = Unit
            throw signedOut
        }
        renewedTo[refreshToken] = renewed
        saveIfCurrent(refreshToken, renewed)
        renewed
    }

    // Follows a chain of renewals (A to B, then B to C) to its end.
    private fun latestRenewalOf(refreshToken: String): GrimmorySession? {
        var latest = renewedTo[refreshToken]
        var steps = 0
        while (latest != null && steps < renewedTo.size) {
            latest = renewedTo[latest.refreshToken] ?: break
            steps += 1
        }
        return latest
    }

    // Only over the saved sign-in it renews: a new sign-in not saved yet saves its own tokens when it ends.
    private suspend fun saveIfCurrent(refreshToken: String, renewed: GrimmorySession) {
        val stored = credentialStore.sessionFlow.value ?: return
        if (stored.refreshToken != refreshToken) return
        credentialStore.save(stored.copy(accessToken = renewed.accessToken, refreshToken = renewed.refreshToken))
    }

    private class BoundedMap<K, V> : LinkedHashMap<K, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > MAX_REMEMBERED_TOKENS
    }
}

/** A Grimmory client whose renewals go through [renewals]; every other request goes straight to [delegate]. */
internal class RenewingGrimmoryApi(
    private val delegate: GrimmoryApi,
    private val renewals: GrimmoryTokenRenewals,
) : GrimmoryApi by delegate {
    override suspend fun refreshSession(refreshToken: String): GrimmorySession =
        renewals.renew(refreshToken, delegate::refreshSession)
}

/** The app's Grimmory clients, one per server address, sharing the one [GrimmoryTokenRenewals]. */
class RenewingGrimmoryApiFactory @Inject constructor(
    private val httpClient: HttpClient,
    private val renewals: GrimmoryTokenRenewals,
) : GrimmoryApiFactory {
    override fun create(baseUrl: String): GrimmoryApi =
        RenewingGrimmoryApi(KtorGrimmoryApi(httpClient = httpClient, baseUrl = baseUrl), renewals)
}

private const val MAX_REMEMBERED_TOKENS = 16
