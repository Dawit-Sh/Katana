package com.example.unifiedreader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.unifiedreader.ui.components.AppCircularProgress
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppLinearProgress
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.LocalAppToastState
import com.example.unifiedreader.ui.components.appGutter
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens
import java.util.Locale
import kotlinx.coroutines.delay

/** A sync shows its bar once it has gone on this long (most take a fraction of a second), or at once for a book. */
internal const val KATANA_DESK_BAR_AFTER_MILLIS = 700L

/**
 * The Katana Desk sheet (Settings › Sources and the Local library's ⋮ open it), and the note when a sync is done
 * (his OK on the round's page, item 5): "Synced with KATANA-PC · The Hole is on this device, at p. 7".
 */
@Composable
internal fun KatanaDeskHost(viewModel: KatanaDeskViewModel = hiltViewModel()) {
    val open by viewModel.sheetOpen.collectAsState()
    if (open) {
        val paired by viewModel.paired.collectAsState()
        val form by viewModel.pairing.form.collectAsState()
        val links by viewModel.links.collectAsState()
        val syncing by viewModel.syncing.collectAsState()
        val problem by viewModel.problem.collectAsState()
        val syncedAt by viewModel.lastSyncedAt.collectAsState()
        val lastResult by viewModel.lastResult.collectAsState()
        val activity by viewModel.activity.collectAsState()
        KatanaDeskSheet(
            look = KatanaDeskSheetLook(
                paired = paired,
                form = form,
                books = links.size,
                syncing = syncing,
                problem = problem,
                syncedAt = syncedAt,
                brought = lastResult?.brought,
                getting = activity?.getting,
            ),
            pairing = viewModel.pairing,
            onSyncNow = viewModel::syncNow,
            onForget = viewModel::forget,
            onDismiss = viewModel::closeSheet,
        )
    }
    val toasts = LocalAppToastState.current
    val events = viewModel.events
    val placeOf: (String) -> String? = viewModel::placeOf
    LaunchedEffect(events, toasts) {
        events.collect { event ->
            if (event is KatanaDeskSyncEvent.Ended) katanaDeskDoneNote(event, placeOf)?.let { note -> toasts?.show(note) }
        }
    }
}

/**
 * The note when a sync is done: always when the PC's book came over; for a sync long enough to have shown its bar,
 * that it's done (or why it didn't finish); nothing for the quick check-ins every couple of minutes.
 */
internal fun katanaDeskDoneNote(event: KatanaDeskSyncEvent.Ended, placeOf: (String) -> String?): AppToastMessage? {
    val arrived = event.result.brought as? KatanaDeskBringing.Arrived
    val long = event.atEpochMillis - event.sinceEpochMillis >= KATANA_DESK_BAR_AFTER_MILLIS
    val problem = event.result.problem
    return when {
        arrived != null -> {
            val at = placeOf(arrived.itemId)?.let { ", at $it" }.orEmpty()
            val text = "Synced with ${event.deskName} · ${arrived.title} is on this device$at"
            AppToastMessage(text = text, icon = AppToastIcon.Download)
        }
        long && problem != null -> AppToastMessage(
            text = "Didn't finish syncing with ${event.deskName}",
            supportingText = problem,
            icon = AppToastIcon.Error,
        )
        long -> AppToastMessage(text = "Synced with ${event.deskName}", icon = AppToastIcon.Done)
        else -> null
    }
}

internal fun pairedStatusText(syncing: Boolean, problem: String?, syncedAt: Long, now: Long = System.currentTimeMillis()): String = when {
    syncing -> "Syncing…"
    problem != null -> problem
    syncedAt > 0 && now - syncedAt < MINUTE_MILLIS -> "Synced just now."
    syncedAt > 0 -> "Synced ${syncedWhen(syncedAt)}."
    else -> "Not synced yet."
}

internal fun booksOnDeskText(count: Int): String = when (count) {
    0 -> "None of the books here are on it yet. Add the same file to Katana Desk and it links by itself."
    1 -> "1 book here is on it too."
    else -> "$count books here are on it too."
}

/** What became of the book being read on the PC, in words; null when there's nothing to say. */
internal fun broughtText(brought: KatanaDeskBringing): String? = when (brought) {
    KatanaDeskBringing.NothingRead -> null
    is KatanaDeskBringing.Arrived -> "${brought.title} came from the PC, into Local. It opens where you are there."
    is KatanaDeskBringing.AlreadyHere -> "${brought.title}, the book open on the PC, is here too: it syncs."
    is KatanaDeskBringing.OnGrimmory -> "${brought.title}, the book open on the PC, is on Grimmory here: it syncs there."
    is KatanaDeskBringing.NotSynced -> "${brought.title} is a ${brought.format} book: those don't sync with the PC yet."
    is KatanaDeskBringing.Failed -> if (brought.triesAgain) {
        "${brought.title ?: "The PC's book"} didn't come yet: ${brought.problem} It's tried again at the next sync."
    } else {
        "${brought.title ?: "The PC's book"} didn't come from the PC: ${brought.problem}"
    }
}

/** The sync with the PC while its bar shows: one that has gone on for a moment, or a book coming over; else null. */
@Composable
internal fun rememberKatanaDeskBar(viewModel: KatanaDeskViewModel = hiltViewModel()): KatanaDeskActivity? {
    val activity by viewModel.activity.collectAsState()
    var shown by remember { mutableStateOf(false) }
    val since = activity?.sinceEpochMillis
    val coming = activity?.getting != null
    LaunchedEffect(since, coming) {
        shown = false
        if (since == null) return@LaunchedEffect
        if (!coming) delay(KATANA_DESK_BAR_AFTER_MILLIS)
        shown = true
    }
    return activity.takeIf { shown }
}

/**
 * The slim bar under the library's top bar while a sync with the PC runs: a spinner, "Syncing with KATANA-PC",
 * and, while the PC's book comes over, "Getting The Hole · 3.1 of 7.8 MB" with its progress.
 */
@Composable
internal fun KatanaDeskSyncBar(activity: KatanaDeskActivity, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(UnifiedReaderThemeTokens.presentation.controlCornerRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = appGutter())
            .clip(shape)
            .background(lerp(UnifiedReaderThemeTokens.colors.surfaceRaised, accent, BAR_TINT))
            .padding(horizontal = AppLayout.menuInset, vertical = AppLayout.headingGap),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppCircularProgress(modifier = Modifier.size(16.dp), color = accent, strokeWidth = 2.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Syncing with ${activity.deskName}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            activity.getting?.let { getting ->
                Text(
                    text = gettingText(getting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                val fraction = getting.fraction
                if (fraction != null) {
                    AppLinearProgress(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    AppLinearProgress(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** "Getting The Hole · 3.1 of 7.8 MB" (KB under a megabyte; the size alone when the whole isn't known). */
internal fun gettingText(getting: KatanaDeskGetting): String {
    val total = getting.totalBytes
    val amount = if (total == null) sizeText(getting.bytes) else "${sizeNumber(getting.bytes, total)} of ${sizeText(total)}"
    return "Getting ${getting.title} · $amount"
}

private fun sizeText(bytes: Long): String = "${sizeNumber(bytes, bytes)} ${if (bytes < MEGABYTE) "KB" else "MB"}"

// [bytes] in the unit [scale] is shown in: KB under a megabyte, MB with one decimal from there.
private fun sizeNumber(bytes: Long, scale: Long): String =
    if (scale < MEGABYTE) {
        (bytes / KILOBYTE).coerceAtLeast(if (bytes > 0) 1 else 0).toString()
    } else {
        String.format(Locale.US, "%.1f", bytes.toDouble() / MEGABYTE)
    }

private const val BAR_TINT = 0.22f
private const val MINUTE_MILLIS = 60_000L
private const val KILOBYTE = 1024L
private const val MEGABYTE = 1024L * 1024L
