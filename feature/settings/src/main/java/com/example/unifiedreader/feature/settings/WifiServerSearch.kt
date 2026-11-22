package com.example.unifiedreader.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Where the Connect page's look for the server on this Wi-Fi stands. */
enum class WifiServerSearchState { Idle, Looking, Found, NotFound }

/** The Connect page's look for the server on this Wi-Fi, and the line it shows under the lead. */
class WifiServerSearch internal constructor(
    private val state: WifiServerSearchState,
    private val foundUrl: String?,
    private val typedUrl: String,
) {
    fun line(serverName: String): String? = wifiSearchLine(state, serverName, foundUrl, typedUrl)
}

/**
 * The line under the Connect page's lead for [state]; null shows nothing. "Found" shows only while the
 * address it filled in ([foundUrl]) is still the one in the field ([typedUrl]).
 */
fun wifiSearchLine(
    state: WifiServerSearchState,
    serverName: String,
    foundUrl: String? = null,
    typedUrl: String = foundUrl.orEmpty(),
): String? = when (state) {
    WifiServerSearchState.Idle -> null
    WifiServerSearchState.Looking -> "Looking for $serverName on this Wi-Fi…"
    WifiServerSearchState.Found ->
        "Found $serverName on this Wi-Fi and filled in its address.".takeIf { typedUrl.trim() == foundUrl }
    WifiServerSearchState.NotFound ->
        "$serverName wasn't found on this Wi-Fi by itself. Type its address below.".takeIf { typedUrl.isBlank() }
}

/**
 * Looks for the server on this Wi-Fi once, when the Connect page opens with an empty Server URL, and fills
 * the address in when it answers and the field is still empty (nothing typed meanwhile is overwritten).
 */
@Composable
internal fun rememberWifiServerSearch(entry: SourceSettingsEntry): WifiServerSearch {
    val find = entry.actions.findOnWifi
    var state by rememberSaveable(entry.sourceType) { mutableStateOf(WifiServerSearchState.Idle) }
    var foundUrl by rememberSaveable(entry.sourceType) { mutableStateOf<String?>(null) }
    val baseUrl by rememberUpdatedState(entry.connection.baseUrl)
    val fill by rememberUpdatedState(entry.actions.onBaseUrlChanged)
    val needed = find != null && !entry.connection.hasStoredSession
    LaunchedEffect(entry.sourceType, needed) {
        if (!needed || state != WifiServerSearchState.Idle || baseUrl.isNotBlank()) return@LaunchedEffect
        state = WifiServerSearchState.Looking
        val found = find?.invoke()
        state = when {
            found == null -> WifiServerSearchState.NotFound
            baseUrl.isBlank() -> {
                foundUrl = found
                fill(found)
                WifiServerSearchState.Found
            }
            // Typed meanwhile: what was typed stays, and the page says nothing more.
            else -> WifiServerSearchState.Idle
        }
    }
    return WifiServerSearch(state = state, foundUrl = foundUrl, typedUrl = entry.connection.baseUrl)
}
