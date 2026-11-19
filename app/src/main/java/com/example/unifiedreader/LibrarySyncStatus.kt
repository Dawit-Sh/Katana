package com.example.unifiedreader

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.unifiedreader.core.model.DeviceClock
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.displayName
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppMenu
import com.example.unifiedreader.ui.components.AppMenuHeading
import com.example.unifiedreader.ui.components.AppMenuItem
import com.example.unifiedreader.ui.components.appBadgeDot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the library bar's cloud says about the server on screen: when its library last synced on
 * this device, how many of this device's changes (reading progress) still wait to reach it, and
 * whether it can be reached now.
 */
@Immutable
data class LibrarySyncStatus(
    val sourceName: String,
    val lastSyncedAtEpochMillis: Long?,
    val waitingCount: Int,
    val failedCount: Int,
    val offline: Boolean,
    val syncing: Boolean,
) {
    val needsAttention: Boolean get() = offline || waitingCount > 0 || failedCount > 0
}

/** The status for [source] from the stored sync times and what waits to upload; null for books on the tablet. */
internal fun librarySyncStatus(
    source: SourceType,
    syncTimes: Map<SourceType, Long>,
    pending: AppPendingRemoteSyncSummary,
    offline: Boolean,
    syncing: Boolean,
): LibrarySyncStatus? {
    if (source == SourceType.LOCAL) return null
    val group = pending.groups.firstOrNull { it.sourceType == source }
    return LibrarySyncStatus(
        sourceName = source.displayName(),
        lastSyncedAtEpochMillis = syncTimes[source],
        waitingCount = group?.queuedCount ?: 0,
        failedCount = group?.failedCount ?: 0,
        offline = offline,
        syncing = syncing,
    )
}

/** "Last synced today at 5:07 PM", "yesterday at 9:12 AM", "16 Nov at 9:40 PM", or never. */
internal fun lastSyncedLabel(atEpochMillis: Long?, now: Instant, zone: ZoneId): String {
    atEpochMillis ?: return LibrarySyncCopy.NeverSynced
    val at = Instant.ofEpochMilli(atEpochMillis).atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val time = DeviceClock.format(at)
    return when (at.toLocalDate()) {
        today -> "Last synced today at $time"
        today.minusDays(1) -> "Last synced yesterday at $time"
        else -> "Last synced ${at.format(if (at.year == today.year) DayFormat else DayYearFormat)} at $time"
    }
}

/** What still waits to go up, and whether it can now. */
internal fun waitingLabel(status: LibrarySyncStatus): String = when {
    status.syncing -> LibrarySyncCopy.Syncing
    status.failedCount > 0 -> changes(status.failedCount) + " couldn't sync; they'll be tried again"
    status.waitingCount > 0 && status.offline ->
        changes(status.waitingCount) + " waiting; they'll sync when ${status.sourceName} can be reached"
    status.waitingCount > 0 -> changes(status.waitingCount) + " waiting to sync"
    status.offline -> "${status.sourceName} can't be reached now; nothing is waiting"
    else -> LibrarySyncCopy.UpToDate
}

private fun changes(count: Int): String = if (count == 1) "1 change" else "$count changes"

/**
 * The cloud on the library bar: a dot when something waits or the server is out of reach. A tap
 * shows when the library last synced and what waits; Sync now syncs it (null: no sync from here).
 */
@Composable
internal fun LibrarySyncStatusButton(
    status: LibrarySyncStatus,
    onSyncNow: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    val lastSynced = lastSyncedLabel(status.lastSyncedAtEpochMillis, Instant.now(), ZoneId.systemDefault())
    val waiting = waitingLabel(status)
    Box {
        AppIconButton(onClick = { open = true }) {
            Icon(
                imageVector = AppIcons.Cloud,
                contentDescription = "${status.sourceName} sync: $lastSynced. $waiting",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.appBadgeDot(
                    visible = status.needsAttention,
                    color = if (status.failedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                ),
            )
        }
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            AppMenuHeading(text = status.sourceName)
            AppMenuItem(text = { Text(lastSynced) }, onClick = { open = false })
            AppMenuItem(text = { Text(waiting) }, onClick = { open = false })
            if (onSyncNow != null && !status.syncing) {
                AppMenuItem(
                    text = { Text(LibrarySyncCopy.SyncNow) },
                    onClick = {
                        open = false
                        onSyncNow()
                    },
                )
            }
        }
    }
}

internal object LibrarySyncCopy {
    const val NeverSynced = "Not synced yet on this device"
    const val UpToDate = "Everything is synced; nothing is waiting"
    const val Syncing = "Syncing now…"
    const val SyncNow = "Sync now"
}

private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DayYearFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

