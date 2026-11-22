package com.example.unifiedreader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.unifiedreader.core.model.runSuspendCatching
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Finds Grimmory on this device's Wi-Fi, so a new install (or one whose storage was cleared) fills in the
 * server's address by itself: every address of the device's own /24 network is asked, on Grimmory's port,
 * for Grimmory's health check, which answers without a sign-in. A few seconds at most; nothing found, the
 * field stays empty and the person types the address as before.
 */
class GrimmoryWifiFinder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun find(): String? = withContext(Dispatchers.IO) {
        val own = deviceWifiAddress() ?: return@withContext null
        val limiter = Semaphore(FIND_PARALLEL_PROBES)
        coroutineScope {
            sameNetworkHosts(own).map { host ->
                async { limiter.withPermit { host.takeIf { answersAsGrimmory(it) } } }
            }.awaitAll().filterNotNull().firstOrNull()?.let { host -> "http://$host:$GRIMMORY_PORT" }
        }
    }

    // The device's IPv4 address on its Wi-Fi or cable network (never mobile data), as four numbers.
    private fun deviceWifiAddress(): IntArray? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork
        val local = network?.let(connectivity::getNetworkCapabilities)?.let { capabilities ->
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
        return network.takeIf { local }
            ?.let(connectivity::getLinkProperties)
            ?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.address
            ?.map { byte -> byte.toInt() and BYTE_MASK }
            ?.toIntArray()
    }

    private suspend fun answersAsGrimmory(host: String): Boolean = runSuspendCatching {
        val portOpen = Socket().use { socket ->
            socket.connect(InetSocketAddress(host, GRIMMORY_PORT), FIND_CONNECT_TIMEOUT_MILLIS)
            true
        }
        portOpen && isGrimmoryHealthCheck(readHealthCheck(host))
    }.getOrDefault(false)

    private fun readHealthCheck(host: String): String {
        val connection = URL("http://$host:$GRIMMORY_PORT$GRIMMORY_HEALTH_CHECK_PATH").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = FIND_CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = FIND_READ_TIMEOUT_MILLIS
            connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(HEALTH_CHECK_READ_LIMIT)
                String(chars, 0, reader.read(chars).coerceAtLeast(0))
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** The other addresses of [own]'s /24 network, 1 to 254, nearest first (a home server sits near the device). */
internal fun sameNetworkHosts(own: IntArray): List<String> {
    if (own.size != IPV4_PARTS) return emptyList()
    val prefix = own.take(IPV4_PARTS - 1).joinToString(separator = ".", postfix = ".")
    val ownHost = own.last()
    return (FIRST_HOST..LAST_HOST)
        .filter { it != ownHost }
        .sortedBy { kotlin.math.abs(it - ownHost) }
        .map { prefix + it }
}

/** Whether [body] is Grimmory's health check answer ({"data":{"status":"UP",...},"message":"Pong"}). */
internal fun isGrimmoryHealthCheck(body: String): Boolean =
    body.contains("\"Pong\"") && body.contains("\"status\":\"UP\"")

/**
 * Runs [block] (a sign-in), and again after a short wait when it found no answer at all: a device's Wi-Fi
 * can take seconds to wake (the Fire 7 most of all), and a first sign-in shouldn't fail on that. Any other
 * failure (a wrong password, a wrong address) comes back at once.
 */
internal suspend fun <T> retryWhileUnreachable(
    waitsMillis: List<Long> = CONNECT_RETRY_WAITS_MILLIS,
    block: suspend () -> T,
): T {
    for (wait in waitsMillis) {
        val attempt = runSuspendCatching { block() }
        val failure = attempt.exceptionOrNull()
        if (failure == null || !failure.isRemoteServerAddressRetryableFailure()) return attempt.getOrThrow()
        delay(wait)
    }
    return block()
}

/** What a sign-in that found no server at [baseUrl] says, in place of the network library's own words. */
internal fun unreachableServerMessage(prefix: String, baseUrl: String): String =
    "$prefix: no answer from $baseUrl. Check the address, and that this device is on the same Wi-Fi as the server. " +
        "If a browser here can't open that address either, turn the Wi-Fi off and on, or restart the device."

private const val GRIMMORY_PORT = 6060
private const val GRIMMORY_HEALTH_CHECK_PATH = "/api/v1/healthcheck"
private const val FIND_PARALLEL_PROBES = 48
private const val FIND_CONNECT_TIMEOUT_MILLIS = 400
private const val FIND_READ_TIMEOUT_MILLIS = 1500
private const val HEALTH_CHECK_READ_LIMIT = 2048
private const val BYTE_MASK = 0xFF
private const val IPV4_PARTS = 4
private const val FIRST_HOST = 1
private const val LAST_HOST = 254
private val CONNECT_RETRY_WAITS_MILLIS = listOf(2_000L, 4_000L)
