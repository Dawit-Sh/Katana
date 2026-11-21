package com.example.unifiedreader

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.ui.components.AppDialog
import com.example.unifiedreader.ui.components.AppDialogConfirmButton
import com.example.unifiedreader.ui.components.AppDialogDismissButton
import com.example.unifiedreader.ui.components.AppTextButton
import com.example.unifiedreader.ui.components.AppTextField
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Pairing with Katana Desk, and how its sync stands, for the Local library's "•••". */
@HiltViewModel
class KatanaDeskViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sync: KatanaDeskSync,
    connection: KatanaDeskConnection,
) : ViewModel() {
    val paired = connection.paired
    val links = connection.links
    val lastSyncedAt = connection.lastSyncedAt
    val syncing = sync.syncing
    val problem = sync.problem

    var found by mutableStateOf<List<KatanaDeskFound>>(emptyList())
        private set
    var pairing by mutableStateOf(false)
        private set
    var pairProblem by mutableStateOf<String?>(null)
        private set

    private var discovery: KatanaDeskDiscovery? = null

    fun startLooking() {
        if (discovery != null) return
        discovery = KatanaDeskDiscovery(context) { desks -> found = desks }.also(KatanaDeskDiscovery::start)
    }

    fun stopLooking() {
        discovery?.stop()
        discovery = null
    }

    fun pair(address: String, code: String) {
        if (pairing) return
        pairing = true
        pairProblem = null
        viewModelScope.launch {
            runSuspendCatching { sync.pair(address, code) }.onFailure { failure ->
                pairProblem = (failure as? KatanaDeskRefused)?.message
                    ?: "Nothing answered at ${address.normalizedDeskAddress()}. Is Sync on this Wi-Fi on in Katana Desk?"
            }
            pairing = false
        }
    }

    fun syncNow() {
        viewModelScope.launch { sync.syncNow() }
    }

    fun forget() = sync.forget()

    override fun onCleared() = stopLooking()
}

/**
 * Katana Desk, from the Local library's "•••": before pairing, the desks found on this Wi-Fi (or an address
 * typed in) and the code the desk shows; after, which desk, how many books are on it, the last sync, Sync now
 * and Forget.
 */
@Composable
internal fun KatanaDeskDialog(onDismiss: () -> Unit, viewModel: KatanaDeskViewModel = hiltViewModel()) {
    val paired by viewModel.paired.collectAsState()
    val links by viewModel.links.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val problem by viewModel.problem.collectAsState()
    val syncedAt by viewModel.lastSyncedAt.collectAsState()
    val desk = paired
    if (desk == null) {
        DisposableEffect(viewModel) {
            viewModel.startLooking()
            onDispose(viewModel::stopLooking)
        }
        PairDialog(
            state = PairState(found = viewModel.found, pairing = viewModel.pairing, problem = viewModel.pairProblem),
            onPair = viewModel::pair,
            onDismiss = onDismiss,
        )
    } else {
        PairedDialog(
            desk = desk,
            status = PairedStatus(books = links.size, syncing = syncing, problem = problem, syncedAt = syncedAt),
            onSyncNow = viewModel::syncNow,
            onForget = viewModel::forget,
            onDismiss = onDismiss,
        )
    }
}

private data class PairState(val found: List<KatanaDeskFound>, val pairing: Boolean, val problem: String?)

private data class PairedStatus(val books: Int, val syncing: Boolean, val problem: String?, val syncedAt: Long)

@Composable
private fun PairDialog(state: PairState, onPair: (String, String) -> Unit, onDismiss: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val canPair = address.isNotBlank() && code.count(Char::isDigit) == CODE_DIGITS && !state.pairing
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Katana Desk") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Sync the books on this device with the same books on your PC: where you are, highlights " +
                        "and bookmarks. In Katana Desk, turn on Sync on this Wi-Fi in Settings and pair a device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.found.isEmpty()) {
                    Text(text = "Looking on this Wi-Fi…", style = MaterialTheme.typography.bodyMedium)
                }
                state.found.forEach { desk ->
                    Text(
                        text = "${desk.name} · ${desk.address}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (desk.address == address) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth().clickable { address = desk.address },
                    )
                }
                AppTextField(
                    value = address,
                    onValueChange = { address = it.trim() },
                    label = { Text("Address") },
                    placeholder = { Text("192.168.1.20:$KATANA_DESK_PORT") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                AppTextField(
                    value = code,
                    onValueChange = { typed -> code = typed.filter(Char::isDigit).take(CODE_DIGITS) },
                    label = { Text("Code on the PC") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.problem?.let { problem ->
                    Text(text = problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = { AppDialogDismissButton(text = "Cancel", onClick = onDismiss) },
        confirmButton = {
            AppDialogConfirmButton(
                text = if (state.pairing) "Pairing…" else "Pair",
                enabled = canPair,
                onClick = { onPair(address, code) },
            )
        },
    )
}

@Composable
private fun PairedDialog(
    desk: KatanaDeskPaired,
    status: PairedStatus,
    onSyncNow: () -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Katana Desk") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Paired with ${desk.name} (${desk.address}).", style = MaterialTheme.typography.bodyLarge)
                Text(text = booksOnDeskText(status.books), style = MaterialTheme.typography.bodyMedium)
                val failed = status.problem != null && !status.syncing
                Text(
                    text = statusText(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppTextButton(onClick = onForget) { Text(text = "Forget this PC", fontWeight = FontWeight.SemiBold) }
            }
        },
        dismissButton = { AppDialogDismissButton(text = "Sync now", enabled = !status.syncing, onClick = onSyncNow) },
        confirmButton = { AppDialogConfirmButton(text = "Done", onClick = onDismiss) },
    )
}

private fun statusText(status: PairedStatus): String = when {
    status.syncing -> "Syncing…"
    status.problem != null -> status.problem
    status.syncedAt > 0 -> "Synced " + DateUtils.getRelativeTimeSpanString(status.syncedAt).toString().lowercase()
    else -> "Not synced yet."
}

internal fun booksOnDeskText(count: Int): String = when (count) {
    0 -> "None of the books here are on it yet. Add the same file to Katana Desk and it links by itself."
    1 -> "1 book here is on it too."
    else -> "$count books here are on it too."
}

private const val CODE_DIGITS = 6

/** Finds Katana Desk on this Wi-Fi with Android's DNS-SD; [onFound] hears the desks found so far, on the main thread. */
internal class KatanaDeskDiscovery(context: Context, private val onFound: (List<KatanaDeskFound>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val desks = LinkedHashMap<String, KatanaDeskFound>()
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) = resolve(info)

        override fun onServiceLost(info: NsdServiceInfo) {
            synchronized(desks) { desks.remove(info.serviceName) }
            post()
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    fun start() {
        runCatching { nsd?.discoverServices(KATANA_DESK_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stop() {
        runCatching { nsd?.stopServiceDiscovery(listener) }
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        runCatching {
            nsd?.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        val host = service.host?.takeIf { it is Inet4Address }?.hostAddress ?: return
                        synchronized(desks) { desks[service.serviceName] = KatanaDeskFound(service.serviceName, "$host:${service.port}") }
                        post()
                    }

                    override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) = Unit
                },
            )
        }
    }

    private fun post() {
        val found = synchronized(desks) { desks.values.toList() }
        main.post { onFound(found) }
    }
}
