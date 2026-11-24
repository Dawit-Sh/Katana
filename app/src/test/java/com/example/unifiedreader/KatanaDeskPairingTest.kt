package com.example.unifiedreader

import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.network.buildHttpClient
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KatanaDeskPairingTest {
    @Test
    fun `only Katana Desk's own hello counts`() {
        val desk = parseKatanaDeskHello(
            """{"app":"katana-desk","protocol":1,"deskId":"d-1","name":"DADDYMARX","version":"0.3.1","pairing":true}""",
        )
        assertEquals(KatanaDeskHello(deskId = "d-1", name = "DADDYMARX", protocol = 1, pairing = true), desk)
        // an older desk says nothing of pairing
        assertEquals(false, parseKatanaDeskHello("""{"app":"katana-desk","protocol":1,"deskId":"d-1","name":"PC"}""")?.pairing)
        // anything else on the port
        assertNull(parseKatanaDeskHello("""{"app":"something-else","protocol":1,"deskId":"x"}"""))
        assertNull(parseKatanaDeskHello("""{"app":"katana-desk","protocol":1}"""))
        assertNull(parseKatanaDeskHello("""{"app":"katana-desk","deskId":"x"}"""))
        assertNull(parseKatanaDeskHello("<html><body>Router</body></html>"))
        assertNull(parseKatanaDeskHello(""))
    }

    @Test
    fun `a code that ran out says so and asks for a new one`() {
        val refused = pairRefusalOf(403, """{"status":403,"reason":"expired","message":"..."}""")!!
        assertEquals(KatanaDeskPairRefusal.CodeRanOut, refused.refusal)
        assertTrue(refused.message!!.startsWith("That code ran out."))
        assertTrue(refused.refusal.needsNewCode)
    }

    @Test
    fun `wrong code, no code showing and too many tries are told apart`() {
        assertEquals(KatanaDeskPairRefusal.WrongCode, pairRefusalOf(403, """{"reason":"wrong"}""")!!.refusal)
        assertEquals(KatanaDeskPairRefusal.NoCode, pairRefusalOf(403, """{"reason":"closed"}""")!!.refusal)
        assertEquals(KatanaDeskPairRefusal.TooManyTries, pairRefusalOf(429, """{"reason":"locked"}""")!!.refusal)
        assertFalse(KatanaDeskPairRefusal.TooManyTries.needsNewCode)
        // an older desk: a 403 without a reason is a code no good
        val old = pairRefusalOf(403, """{"status":403,"message":"That code is wrong or has run out"}""")!!
        assertEquals(KatanaDeskPairRefusal.WrongCode, old.refusal)
        assertEquals("That code isn't right, or it ran out. Check the code on the PC.", old.message)
        assertEquals(KatanaDeskPairRefusal.Other, pairRefusalOf(500, null)!!.refusal)
        assertNull(pairRefusalOf(200, """{"token":"t"}"""))
    }

    @Test
    fun `the book being read on the PC is read from the desk's answer`() {
        val reading = parseKatanaDeskReading(
            """{"book":{"id":7,"title":"Sync Test Novel","author":"A. Writer","format":"epub","bookType":"EPUB","size":22028,
               "md5":"1c406e37cf164d205680c949acecc9ed","fileName":"sync-test-novel.epub","grimmoryId":null,"open":true}}""",
        )!!
        assertEquals("7", reading.bookId)
        assertEquals(FormatType.EPUB, reading.format)
        assertEquals(22028L, reading.sizeBytes)
        assertEquals("sync-test-novel.epub", reading.fileName)
        assertNull(reading.grimmoryId)
        assertEquals("812", parseKatanaDeskReading("""{"book":{"id":3,"format":"pdf","grimmoryId":812}}""")!!.grimmoryId)
        assertEquals(FormatType.CBZ, parseKatanaDeskReading("""{"book":{"id":3,"format":"cbz"}}""")!!.format)
        assertNull(parseKatanaDeskReading("""{"book":{"id":3,"format":"mobi"}}""")!!.format?.takeIf { it in KATANA_DESK_FORMATS })
        assertNull(parseKatanaDeskReading("""{"book":null}"""))
        assertNull(parseKatanaDeskReading("not json"))
    }

    @Test
    fun `a desk that goes quiet mid-pairing ends the pairing with words, not a hang`() = runBlocking {
        // takes the connection and never answers, like a desk that went away mid-pairing
        val silent = ServerSocket(0)
        val held = CopyOnWriteArrayList<Socket>()
        thread(isDaemon = true) { runCatching { while (true) held += silent.accept() } }
        try {
            val client = KatanaDeskClient(buildHttpClient(), requestTimeoutMillis = 300)
            try {
                client.pair("127.0.0.1:${silent.localPort}", "123456", "device-1")
                fail("paired with nothing")
            } catch (refused: KatanaDeskRefused) {
                assertEquals(KatanaDeskPairRefusal.NoAnswer, refused.refusal)
            }
        } finally {
            held.forEach { it.close() }
            silent.close()
        }
    }

    @Test
    fun `nothing listening at the address is no answer too`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        try {
            KatanaDeskClient(buildHttpClient(), requestTimeoutMillis = 2_000).pair("127.0.0.1:$port", "123456", "device-1")
            fail("paired with nothing")
        } catch (refused: KatanaDeskRefused) {
            assertEquals(KatanaDeskPairRefusal.NoAnswer, refused.refusal)
        }
    }

    @Test
    fun `a ran out code from a desk comes back as such`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"app":"katana-desk","protocol":1,"deskId":"d-9","name":"PC"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"status":403,"reason":"expired","message":"That code ran out"}"""))
        server.start()
        try {
            val client = KatanaDeskClient(buildHttpClient())
            val address = "127.0.0.1:${server.port}"
            val found = client.hello(address)
            assertNotNull(found)
            assertEquals("d-9", found!!.deskId)
            try {
                client.pair(address, "123456", "device-1")
                fail("paired with a code that ran out")
            } catch (refused: KatanaDeskRefused) {
                assertEquals(KatanaDeskPairRefusal.CodeRanOut, refused.refusal)
            }
            assertEquals("/katana-sync/v1/hello", server.takeRequest().path)
            assertEquals("/katana-sync/v1/pair", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }
}
