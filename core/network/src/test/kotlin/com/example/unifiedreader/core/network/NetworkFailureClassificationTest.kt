package com.example.unifiedreader.core.network

import io.ktor.client.network.sockets.ConnectTimeoutException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import io.ktor.client.request.get
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NetworkFailureClassificationTest {
    @Test
    fun `lost or missing connections are connection failures`() {
        listOf(
            // What the Pixel showed on 2026-11-19 when the home Wi-Fi stalled.
            ConnectTimeoutException(
                "Connect timeout has expired [url=http://192.168.100.100:6060/api/v1/books/70/content, connect_timeout=10000 ms]",
            ),
            SocketTimeoutException("timeout"),
            ConnectException("Connection refused"),
            NoRouteToHostException("No route to host"),
            UnknownHostException("Unable to resolve host"),
            SocketException("Connection reset"),
            SocketException("Software caused connection abort"),
            ProtocolException("unexpected end of stream"),
            IncompleteDownloadException(expectedBytes = 100, receivedBytes = 40),
            IOException("unexpected end of stream on http://192.168.100.100:6060/...", java.io.EOFException()),
        ).forEach { failure ->
            assertTrue("$failure", failure.isConnectionFailure())
        }
    }

    @Test
    fun `a connection failure is found under the errors that wrap it`() {
        assertTrue(IllegalStateException("Unable to download", SocketTimeoutException("timeout")).isConnectionFailure())
        assertTrue(RuntimeException(IOException(ConnectException("refused"))).isConnectionFailure())
    }

    @Test
    fun `server answers and local file errors are not connection failures`() {
        listOf(
            IllegalStateException("No file of type EPUB found for book"),
            IllegalStateException("Unauthorized"),
            IOException("write failed: ENOSPC (No space left on device)"),
            FileNotFoundException("/data/user/0/app/files/grimmory-documents/book.epub (Permission denied)"),
            ProtocolException("Too many follow-up requests: 21"),
        ).forEach { failure ->
            assertFalse("$failure", failure.isConnectionFailure())
        }
    }

    @Test
    fun `a full disk is retryable for the old check but not a lost connection`() {
        val diskFull = IOException("write failed: ENOSPC (No space left on device)")

        assertTrue(diskFull.isRetryableNetworkFailure())
        assertFalse(diskFull.isConnectionFailure())
    }

    @Test
    fun `a server that refuses the connection gives a connection failure`() = runBlocking {
        val port = ServerSocket(0).use { socket -> socket.localPort }
        val client = buildHttpClient(connectTimeoutMillis = 2_000, socketTimeoutMillis = 2_000)
        try {
            client.get("http://127.0.0.1:$port/api/v1/books/70/content")
            fail("The request should fail: nothing listens on port $port.")
        } catch (failure: IOException) {
            assertTrue("$failure", failure.isConnectionFailure())
        } finally {
            client.close()
        }
    }
}
