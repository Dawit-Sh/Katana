package com.example.unifiedreader

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.example.unifiedreader.core.model.runSuspendCatching
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Katana Desk's answer to hello. Only Katana Desk answers `"app": "katana-desk"` with its own id. */
internal data class KatanaDeskHello(val deskId: String, val name: String, val protocol: Int, val pairing: Boolean)

/** [body] read as Katana Desk's hello; null for any other answer (another program on the port, a web page). */
internal fun parseKatanaDeskHello(body: String): KatanaDeskHello? = runCatching {
    val json = JSONObject(body)
    val deskId = json.optString("deskId").trim()
    val protocol = json.optInt("protocol", 0)
    if (json.optString("app") != KATANA_DESK_APP || deskId.isEmpty() || protocol < 1) {
        null
    } else {
        KatanaDeskHello(
            deskId = deskId,
            name = json.optString("name").trim().ifEmpty { KATANA_DESK_NAME },
            protocol = protocol,
            pairing = json.optBoolean("pairing", false),
        )
    }
}.getOrNull()

/** The four numbers of [address]'s IPv4 host ("192.168.1.20:47615"), or null for a name or IPv6. */
internal fun ipv4Of(address: String): IntArray? {
    val host = address.trim().removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')
    val numbers = host.split('.').map { it.toIntOrNull() ?: -1 }
    return numbers.takeIf { list -> list.size == IPV4_PARTS && list.all { it in 0..BYTE_MAX } }?.toIntArray()
}

/** Whether [address] is on [own]'s /24 network (where a desk this device can reach sits). */
internal fun onOwnNetwork(address: String, own: IntArray?): Boolean {
    val ip = ipv4Of(address) ?: return false
    return own != null && own.size == IPV4_PARTS && (0 until IPV4_PARTS - 1).all { ip[it] == own[it] }
}

/**
 * The desks found so far, one row per desk (by its id): a desk seen at two addresses (its Wi-Fi and a virtual
 * adapter's, or DNS-SD and the network look) keeps the one on this device's own network.
 */
internal class KatanaDeskFinds(private val own: IntArray?) {
    private val byDesk = LinkedHashMap<String, KatanaDeskFound>()

    /** Adds [found]; true when the list changed. */
    fun add(found: KatanaDeskFound): Boolean {
        val key = found.deskId.ifEmpty { found.address }
        val old = byDesk[key]
        val keep = when {
            old == null -> found
            onOwnNetwork(found.address, own) && !onOwnNetwork(old.address, own) -> found
            else -> old.copy(name = found.name, pairing = found.pairing)
        }
        if (keep == old) return false
        byDesk[key] = keep
        return true
    }

    val list: List<KatanaDeskFound> get() = byDesk.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

/**
 * Finds Katana Desk on this Wi-Fi, and only Katana Desk: DNS-SD (`_katana-desk._tcp`, which a router may not pass
 * on) and every address of this device's /24 network on Katana Desk's port, as [GrimmoryWifiFinder] does for
 * Grimmory. Every answer is checked with hello, so another computer or device with the port open never shows.
 */
class KatanaDeskFinder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Looks until cancelled. [onUpdate] hears the desks so far, and whether the first look over the network is still
     * going (after it, DNS-SD goes on listening); it may be called on any thread.
     */
    suspend fun look(onUpdate: (desks: List<KatanaDeskFound>, looking: Boolean) -> Unit) = coroutineScope {
        val own = withContext(Dispatchers.IO) { context.wifiIpv4Address() }
        val finds = KatanaDeskFinds(own)
        val lock = Mutex()
        var looking = true
        val report: suspend (KatanaDeskFound?) -> Unit = { found ->
            val changed = lock.withLock { found != null && finds.add(found) }
            if (changed) onUpdate(lock.withLock { finds.list }, looking)
        }
        onUpdate(emptyList(), true)
        val answers = Channel<String>(Channel.UNLIMITED)
        launch { nsdAddresses(answers) }
        launch {
            for (address in answers) launch { report(withContext(Dispatchers.IO) { hello(address, NSD_CONNECT_TIMEOUT_MILLIS) }) }
        }
        val started = System.currentTimeMillis()
        val limiter = Semaphore(FIND_PARALLEL_PROBES)
        own?.let(::sameNetworkHosts).orEmpty().map { host ->
            async(Dispatchers.IO) { limiter.withPermit { report(hello("$host:$KATANA_DESK_PORT", SCAN_CONNECT_TIMEOUT_MILLIS)) } }
        }.awaitAll()
        // a moment for DNS-SD when there is no network to look over (no Wi-Fi address, a very fast scan)
        delay((LOOK_AT_LEAST_MILLIS - (System.currentTimeMillis() - started)).coerceAtLeast(0))
        looking = false
        onUpdate(lock.withLock { finds.list }, false)
    }

    /** The desk answering hello at [address] (`host:port`), or null. */
    private fun hello(address: String, connectTimeout: Int): KatanaDeskFound? = runCatching {
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull() ?: KATANA_DESK_PORT
        // a closed port or no machine fails here, fast, before any HTTP
        Socket().use { socket -> socket.connect(InetSocketAddress(host, port), connectTimeout) }
        val connection = URL("http://$host:$port$KATANA_DESK_HELLO_PATH").openConnection() as HttpURLConnection
        val body = try {
            connection.connectTimeout = connectTimeout
            connection.readTimeout = HELLO_READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(HELLO_READ_LIMIT)
                String(chars, 0, reader.read(chars).coerceAtLeast(0))
            }
        } finally {
            connection.disconnect()
        }
        parseKatanaDeskHello(body)?.let { hello ->
            KatanaDeskFound(name = hello.name, address = "$host:$port", deskId = hello.deskId, pairing = hello.pairing)
        }
    }.getOrNull()

    /** Sends each address DNS-SD finds for Katana Desk to [out], until cancelled. */
    private suspend fun nsdAddresses(out: Channel<String>) {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        val services = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                services.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        if (!startDiscovery(nsd, listener)) return
        try {
            // one resolve at a time: Android before 14 refuses a second while one runs
            for (info in services) resolve(nsd, info).forEach { address -> out.trySend(address) }
        } finally {
            stopDiscovery(nsd, listener)
        }
    }

    // DNS-SD can refuse (no multicast on this network, a listener already busy): then only the network look runs.
    private fun startDiscovery(nsd: NsdManager, listener: NsdManager.DiscoveryListener): Boolean =
        runCatching { nsd.discoverServices(KATANA_DESK_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess

    private fun stopDiscovery(nsd: NsdManager, listener: NsdManager.DiscoveryListener) {
        runCatching { nsd.stopServiceDiscovery(listener) }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): List<String> = suspendCancellableCoroutine { done ->
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(service: NsdServiceInfo) {
                val hosts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    service.hostAddresses
                } else {
                    listOfNotNull(service.host)
                }
                val port = service.port.takeIf { it > 0 } ?: KATANA_DESK_PORT
                val addresses = hosts.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.map { "$it:$port" }
                if (done.isActive) done.resume(addresses)
            }

            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                if (done.isActive) done.resume(emptyList())
            }
        }
        runCatching { nsd.resolveService(info, listener) }.onFailure { if (done.isActive) done.resume(emptyList()) }
    }
}

/** This device's IPv4 address on its Wi-Fi or cable network (never mobile data), as four numbers. */
internal fun Context.wifiIpv4Address(): IntArray? {
    val connectivity = getSystemService(android.net.ConnectivityManager::class.java) ?: return null
    val network = connectivity.activeNetwork
    val local = network?.let(connectivity::getNetworkCapabilities)?.let { capabilities ->
        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    } == true
    return network.takeIf { local }
        ?.let(connectivity::getLinkProperties)
        ?.linkAddresses
        ?.map { it.address }
        ?.filterIsInstance<Inet4Address>()
        ?.firstOrNull { !it.isLoopbackAddress }
        ?.address
        ?.map { byte -> byte.toInt() and BYTE_MAX }
        ?.toIntArray()
}

internal const val KATANA_DESK_APP = "katana-desk"
internal const val KATANA_DESK_HELLO_PATH = "/katana-sync/v1/hello"
private const val IPV4_PARTS = 4
private const val BYTE_MAX = 0xFF
private const val FIND_PARALLEL_PROBES = 48
private const val SCAN_CONNECT_TIMEOUT_MILLIS = 400
private const val NSD_CONNECT_TIMEOUT_MILLIS = 2_000
private const val HELLO_READ_TIMEOUT_MILLIS = 2_000
private const val HELLO_READ_LIMIT = 2048
private const val LOOK_AT_LEAST_MILLIS = 2_500L
