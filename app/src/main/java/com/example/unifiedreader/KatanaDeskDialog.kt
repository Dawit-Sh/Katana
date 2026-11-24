package com.example.unifiedreader

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.unifiedreader.ui.components.AppDialog
import com.example.unifiedreader.ui.components.AppDialogConfirmButton
import com.example.unifiedreader.ui.components.AppDialogDismissButton
import com.example.unifiedreader.ui.components.AppTextButton
import com.example.unifiedreader.ui.components.AppTextField

/**
 * Katana Desk, from the Local library's "•••": before pairing, the desk found on this Wi-Fi (or an address typed in
 * when none is) and the code the desk shows; after, which desk, how many books are on it, the last sync, Sync now
 * and Forget.
 */
@Composable
internal fun KatanaDeskDialog(onDismiss: () -> Unit, viewModel: KatanaDeskViewModel = hiltViewModel()) {
    val paired by viewModel.paired.collectAsState()
    val links by viewModel.links.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val problem by viewModel.problem.collectAsState()
    val syncedAt by viewModel.lastSyncedAt.collectAsState()
    val lastResult by viewModel.lastResult.collectAsState()
    val form by viewModel.form.collectAsState()
    val desk = paired
    if (desk == null) {
        DisposableEffect(viewModel) {
            viewModel.startLooking()
            onDispose(viewModel::stopLooking)
        }
        PairDialog(
            form = form,
            actions = PairActions(
                onPick = viewModel::pick,
                onTypeAddress = viewModel::typeAddress,
                onAddress = viewModel::onAddress,
                onCode = viewModel::onCode,
                onPair = viewModel::pair,
            ),
            onDismiss = onDismiss,
        )
    } else {
        PairedDialog(
            desk = desk,
            status = PairedStatus(
                books = links.size,
                syncing = syncing,
                problem = problem,
                syncedAt = syncedAt,
                brought = lastResult?.brought,
            ),
            onSyncNow = viewModel::syncNow,
            onForget = viewModel::forget,
            onDismiss = onDismiss,
        )
    }
}

private class PairActions(
    val onPick: (KatanaDeskFound) -> Unit,
    val onTypeAddress: () -> Unit,
    val onAddress: (String) -> Unit,
    val onCode: (String) -> Unit,
    val onPair: () -> Unit,
)

private data class PairedStatus(
    val books: Int,
    val syncing: Boolean,
    val problem: String?,
    val syncedAt: Long,
    val brought: KatanaDeskBringing?,
)

@Composable
private fun PairDialog(form: KatanaDeskPairForm, actions: PairActions, onDismiss: () -> Unit) {
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
                FoundDesks(form, actions)
                if (form.showsAddressField) {
                    AppTextField(
                        value = form.address,
                        onValueChange = actions.onAddress,
                        label = { Text("Address") },
                        placeholder = { Text("192.168.1.20:$KATANA_DESK_PORT") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AppTextField(
                    value = form.code,
                    onValueChange = actions.onCode,
                    label = { Text("Code on the PC") },
                    singleLine = true,
                    enabled = !form.pairing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                form.problem?.message?.let { problem ->
                    Text(text = problem, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = { AppDialogDismissButton(text = "Cancel", onClick = onDismiss) },
        confirmButton = {
            AppDialogConfirmButton(text = if (form.pairing) "Pairing…" else "Pair", enabled = form.canPair, onClick = actions.onPair)
        },
    )
}

@Composable
private fun FoundDesks(form: KatanaDeskPairForm, actions: PairActions) {
    when {
        form.found.isEmpty() && form.looking -> Text(text = "Looking on this Wi-Fi…", style = MaterialTheme.typography.bodyMedium)
        form.nothingFound -> Text(
            text = "No Katana Desk found on this Wi-Fi. Type the address its Settings show.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    form.found.forEach { desk ->
        Text(
            text = "${desk.name} · ${desk.address}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (desk.address == form.address && !form.typing) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth().clickable { actions.onPick(desk) },
        )
    }
    if (form.found.isNotEmpty() && !form.showsAddressField) {
        AppTextButton(onClick = actions.onTypeAddress) { Text(text = "Type an address instead") }
    }
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
                status.brought?.let(::broughtText)?.let { line -> Text(text = line, style = MaterialTheme.typography.bodyMedium) }
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
    // the system's words say "0 minutes ago" for the last minute
    status.syncedAt > 0 && System.currentTimeMillis() - status.syncedAt < DateUtils.MINUTE_IN_MILLIS -> "Synced just now."
    status.syncedAt > 0 -> "Synced " + DateUtils.getRelativeTimeSpanString(status.syncedAt).toString().lowercase()
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
