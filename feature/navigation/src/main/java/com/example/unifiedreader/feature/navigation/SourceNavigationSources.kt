package com.example.unifiedreader.feature.navigation

import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.deriveKavitaSeriesGrouping
import com.example.unifiedreader.core.model.deriveStumpSeriesGrouping

/** The servers a source can be: everything but the files on this device. */
val NavigationServerSources: List<SourceType> = SourceType.entries.filter { it != SourceType.LOCAL }

/**
 * The sources the rail and the drawer list, in [sourceOrder]: the servers in [connectedServers],
 * and Local, which always holds the files imported on this device.
 */
fun navigationSources(
    sourceOrder: List<SourceType>,
    connectedServers: Set<SourceType>,
): List<SourceType> = sourceOrder.filter { source -> source == SourceType.LOCAL || source in connectedServers }

/** "Add a server" shows while a server is not connected. */
fun canAddNavigationSource(connectedServers: Set<SourceType>): Boolean =
    NavigationServerSources.any { server -> server !in connectedServers }

/**
 * The servers that count as connected: a server's own session once it has loaded ([sessions]
 * maps it to true or false), and until then whether it was connected last time
 * ([lastConnectedSources]); with nothing recorded yet, as before this was kept, it counts.
 */
fun navigationConnectedServers(
    sessions: Map<SourceType, Boolean?>,
    lastConnectedSources: Set<SourceType>?,
): Set<SourceType> = NavigationServerSources.filterTo(linkedSetOf()) { server ->
    sessions[server] ?: (lastConnectedSources?.contains(server) ?: true)
}

/** The connected servers once every server's session has loaded, to record for the next launch; null before. */
fun knownConnectedServers(sessions: Map<SourceType, Boolean?>): Set<SourceType>? =
    if (NavigationServerSources.all { server -> sessions[server] != null }) {
        NavigationServerSources.filterTo(linkedSetOf()) { server -> sessions[server] == true }
    } else {
        null
    }

/**
 * How many books each loaded source holds, for the end of its row in the menu, as its library counts
 * them ("All books 6,218"): every book for Grimmory and Local, the series and loose issues for Kavita
 * and Stump. From the catalog the app already holds ([items]); a source still loading
 * ([loadedSources] lacks it) is left out, so a row never shows a count that is too low.
 */
fun navigationSourceCounts(items: List<ReadableItem>, loadedSources: Set<SourceType>): Map<SourceType, Int> {
    if (loadedSources.isEmpty()) return emptyMap()
    val bySource = items.groupBy(ReadableItem::sourceType)
    return loadedSources.associateWith { source ->
        val sourceItems = bySource[source].orEmpty()
        when (source) {
            SourceType.KAVITA -> deriveKavitaSeriesGrouping(sourceItems).rootItems.size
            SourceType.STUMP -> deriveStumpSeriesGrouping(sourceItems).rootItems.size
            SourceType.GRIMMORY, SourceType.LOCAL -> sourceItems.size
        }
    }
}
