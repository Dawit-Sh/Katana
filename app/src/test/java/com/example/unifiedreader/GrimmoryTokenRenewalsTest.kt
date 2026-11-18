package com.example.unifiedreader

import com.example.unifiedreader.core.model.Library
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.data.grimmory.GrimmoryApi
import com.example.unifiedreader.data.grimmory.GrimmoryBookCatalogEntry
import com.example.unifiedreader.data.grimmory.GrimmorySession
import com.example.unifiedreader.data.grimmory.GrimmorySignedOutException
import com.example.unifiedreader.data.grimmory.GrimmoryUnauthorizedException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GrimmoryTokenRenewalsTest {
    private val store = FakeGrimmoryCredentialStore(initialSession = storedSession("old-access", "old-refresh"))
    private val renewals = GrimmoryTokenRenewals(store)

    // Grimmory revokes the old refresh token as it renews: losing the new one signs the device out.
    @Test
    fun `a sync that fails after renewing the sign-in leaves the new tokens saved`() = runTest {
        val server = FakeRenewalServer(renewTo = listOf(GrimmorySession("new-access", "new-refresh"))).apply {
            unauthorizedAccessTokens += "old-access"
            bookListFailure = IOException("The Wi-Fi stopped passing traffic")
        }
        val service = GrimmoryCatalogSyncService(
            grimmoryApiFactory = GrimmoryApiFactory { RenewingGrimmoryApi(server, renewals) },
            remoteCatalogWriter = EmptyRemoteCatalogWriter,
        )

        try {
            service.sync(
                GrimmoryCatalogSyncRequest(
                    displayName = "Grimmory",
                    baseUrl = "http://grimmory.lan",
                    username = "reader",
                    accessToken = "old-access",
                    refreshToken = "old-refresh",
                ),
            )
            fail("The sync should fail when the book list doesn't arrive")
        } catch (expected: IOException) {
            assertEquals("The Wi-Fi stopped passing traffic", expected.message)
        }

        assertEquals("new-access", store.sessionFlow.value?.accessToken)
        assertEquals("new-refresh", store.sessionFlow.value?.refreshToken)
        assertEquals("http://grimmory.lan", store.sessionFlow.value?.baseUrl)
    }

    @Test
    fun `a token already renewed is answered with its renewal without asking the server`() = runTest {
        val server = FakeRenewalServer(renewTo = listOf(GrimmorySession("new-access", "new-refresh")))

        val first = renewals.renew("old-refresh", server::refreshSession)
        val second = renewals.renew("old-refresh", server::refreshSession)

        assertEquals(listOf("old-refresh"), server.renewedTokens)
        assertEquals(first, second)
    }

    @Test
    fun `renewals of one token at once ask the server once`() = runTest {
        val answer = CompletableDeferred<GrimmorySession>()
        val asked = mutableListOf<String>()
        val renew: suspend (String) -> GrimmorySession = { token ->
            asked += token
            answer.await()
        }

        val first = async { renewals.renew("old-refresh", renew) }
        val second = async { renewals.renew("old-refresh", renew) }
        yield()
        answer.complete(GrimmorySession("new-access", "new-refresh"))

        assertEquals("new-refresh", first.await().refreshToken)
        assertEquals("new-refresh", second.await().refreshToken)
        assertEquals(listOf("old-refresh"), asked)
    }

    @Test
    fun `the oldest token of a chain of renewals gets the newest tokens`() = runTest {
        val server = FakeRenewalServer(
            renewTo = listOf(GrimmorySession("mid-access", "mid-refresh"), GrimmorySession("new-access", "new-refresh")),
        )
        renewals.renew("old-refresh", server::refreshSession)
        renewals.renew("mid-refresh", server::refreshSession)

        val late = renewals.renew("old-refresh", server::refreshSession)

        assertEquals("new-refresh", late.refreshToken)
        assertEquals(listOf("old-refresh", "mid-refresh"), server.renewedTokens)
        assertEquals("new-refresh", store.sessionFlow.value?.refreshToken)
    }

    // Each turned-down renewal counts toward Grimmory's 15-minute lockout.
    @Test
    fun `a token the server turned down is not sent again`() = runTest {
        val asked = mutableListOf<String>()
        val refuse: suspend (String) -> GrimmorySession = { token ->
            asked += token
            throw GrimmorySignedOutException()
        }

        repeat(2) {
            try {
                renewals.renew("old-refresh", refuse)
                fail("A refused token signs the device out")
            } catch (expected: GrimmorySignedOutException) {
                assertTrue(expected.message.orEmpty().contains("Sign in again"))
            }
        }

        assertEquals(listOf("old-refresh"), asked)
        assertEquals("old-refresh", store.sessionFlow.value?.refreshToken)
    }

    @Test
    fun `a failed renewal can be tried again`() = runTest {
        var attempts = 0
        val renew: suspend (String) -> GrimmorySession = {
            attempts += 1
            if (attempts == 1) throw IOException("No route to host")
            GrimmorySession("new-access", "new-refresh")
        }

        try {
            renewals.renew("old-refresh", renew)
            fail("The first renewal fails on the network")
        } catch (_: IOException) {
        }
        val renewed = renewals.renew("old-refresh", renew)

        assertEquals("new-refresh", renewed.refreshToken)
        assertEquals("new-refresh", store.sessionFlow.value?.refreshToken)
    }

    @Test
    fun `renewing a sign-in that isn't the saved one leaves the saved one alone`() = runTest {
        val server = FakeRenewalServer(renewTo = listOf(GrimmorySession("login-access-2", "login-refresh-2")))

        renewals.renew("login-refresh", server::refreshSession)

        assertEquals(storedSession("old-access", "old-refresh"), store.sessionFlow.value)
    }

    @Test
    fun `the factory's clients share one set of renewals`() = runTest {
        val server = FakeRenewalServer(renewTo = listOf(GrimmorySession("new-access", "new-refresh")))
        val apis = listOf(RenewingGrimmoryApi(server, renewals), RenewingGrimmoryApi(server, renewals))

        apis.forEach { api -> api.refreshSession("old-refresh") }

        assertEquals(listOf("old-refresh"), server.renewedTokens)
    }
}

private fun storedSession(accessToken: String, refreshToken: String) = GrimmoryStoredSession(
    displayName = "Grimmory",
    baseUrl = "http://grimmory.lan",
    username = "reader",
    accessToken = accessToken,
    refreshToken = refreshToken,
)

private object EmptyRemoteCatalogWriter : RemoteCatalogWriter {
    override suspend fun getItemsForSourceType(
        sourceType: com.example.unifiedreader.core.model.SourceType,
    ): List<ReadableItem> = emptyList()

    override suspend fun replaceSourceCatalog(
        sourceType: com.example.unifiedreader.core.model.SourceType,
        libraries: List<com.example.unifiedreader.core.database.entity.LibraryEntity>,
        items: List<com.example.unifiedreader.core.database.entity.ReadableItemEntity>,
        syncGroups: List<com.example.unifiedreader.core.database.entity.RemoteCatalogSyncGroupEntity>,
    ) = Unit

    override suspend fun applySourceCatalogDelta(delta: RemoteCatalogDelta) = Unit
}

/** A Grimmory server that renews refresh tokens into [renewTo], in order. */
private class FakeRenewalServer(
    private val renewTo: List<GrimmorySession>,
) : GrimmoryApi {
    val renewedTokens = mutableListOf<String>()
    val unauthorizedAccessTokens = mutableSetOf<String>()
    var bookListFailure: Throwable? = null

    override suspend fun refreshSession(refreshToken: String): GrimmorySession {
        renewedTokens += refreshToken
        return renewTo[renewedTokens.lastIndex]
    }

    override suspend fun getLibraries(accessToken: String): List<Library> {
        if (accessToken in unauthorizedAccessTokens) throw GrimmoryUnauthorizedException()
        return emptyList()
    }

    override suspend fun getAllBookEntries(accessToken: String): List<GrimmoryBookCatalogEntry> {
        if (accessToken in unauthorizedAccessTokens) throw GrimmoryUnauthorizedException()
        bookListFailure?.let { throw it }
        return emptyList()
    }

    override suspend fun login(username: String, password: String): GrimmorySession = error("Not used")
    override suspend fun getBooks(accessToken: String, libraryId: String): List<ReadableItem> = error("Not used")
    override fun bookContentUrl(bookId: String, bookType: String?): String = error("Not used")
    override fun bookCoverUrl(bookId: String): String = error("Not used")
}
