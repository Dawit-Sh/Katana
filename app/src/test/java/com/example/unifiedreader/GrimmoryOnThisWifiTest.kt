package com.example.unifiedreader

import com.example.unifiedreader.feature.settings.WifiServerSearchState
import com.example.unifiedreader.feature.settings.wifiSearchLine
import java.io.IOException
import java.net.ConnectException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrimmoryOnThisWifiTest {

    @Test
    fun `the device's own network is asked nearest first, without the device itself`() {
        val hosts = sameNetworkHosts(intArrayOf(192, 168, 100, 104))
        assertEquals(253, hosts.size)
        assertFalse("192.168.100.104" in hosts)
        assertEquals(listOf("192.168.100.103", "192.168.100.105"), hosts.take(2).sorted())
        assertTrue("192.168.100.100" in hosts.take(8))
        assertTrue(hosts.all { it.startsWith("192.168.100.") })
        assertFalse("192.168.100.0" in hosts || "192.168.100.255" in hosts)
    }

    @Test
    fun `an address that isn't four numbers asks nobody`() {
        assertTrue(sameNetworkHosts(intArrayOf(10, 0, 2)).isEmpty())
    }

    @Test
    fun `only grimmory's own health check counts`() {
        assertTrue(
            isGrimmoryHealthCheck(
                """{"data":{"status":"UP","message":"Application is running smoothly.","version":"v3.5.0"},"message":"Pong"}""",
            ),
        )
        assertFalse(isGrimmoryHealthCheck("""{"groups":["liveness","readiness"],"status":"UP"}"""))
        assertFalse(isGrimmoryHealthCheck("<html><title>Router</title></html>"))
    }

    @Test
    fun `a sign-in that finds no answer tries again, then succeeds`() = runTest {
        var tries = 0
        val answer = retryWhileUnreachable(waitsMillis = listOf(1L, 1L)) {
            tries += 1
            if (tries < 3) throw ConnectException("Connect timeout has expired")
            "signed in"
        }
        assertEquals("signed in", answer)
        assertEquals(3, tries)
    }

    @Test
    fun `a wrong password is not tried again`() = runTest {
        var tries = 0
        val failure = runCatching {
            retryWhileUnreachable(waitsMillis = listOf(1L, 1L)) {
                tries += 1
                throw IllegalStateException("Invalid credentials")
            }
        }.exceptionOrNull()
        assertEquals("Invalid credentials", failure?.message)
        assertEquals(1, tries)
    }

    @Test
    fun `no answer after every try gives the last failure`() = runTest {
        var tries = 0
        val failure = runCatching {
            retryWhileUnreachable(waitsMillis = listOf(1L, 1L)) {
                tries += 1
                throw IOException("Failed to connect")
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(3, tries)
    }

    @Test
    fun `no answer says what to check, not the network library's words`() {
        val message = unreachableServerMessage("Unable to connect to Grimmory", "http://192.168.100.100:6060")
        assertTrue(message.startsWith("Unable to connect to Grimmory: no answer from http://192.168.100.100:6060."))
        assertTrue("same Wi-Fi" in message)
        assertFalse("timeout" in message.lowercase())
    }

    @Test
    fun `the connect page says what the search found`() {
        assertNull(wifiSearchLine(WifiServerSearchState.Idle, "Grimmory"))
        assertEquals("Looking for Grimmory on this Wi-Fi…", wifiSearchLine(WifiServerSearchState.Looking, "Grimmory"))
        val found = "http://192.168.100.100:6060"
        assertEquals(
            "Found Grimmory on this Wi-Fi and filled in its address.",
            wifiSearchLine(WifiServerSearchState.Found, "Grimmory", foundUrl = found, typedUrl = found),
        )
        // Changed by hand: the line no longer claims the address is the one found.
        val typed = "http://192.168.1.5:6060"
        assertNull(wifiSearchLine(WifiServerSearchState.Found, "Grimmory", foundUrl = found, typedUrl = typed))
        assertNull(wifiSearchLine(WifiServerSearchState.NotFound, "Grimmory", typedUrl = typed))
    }
}
