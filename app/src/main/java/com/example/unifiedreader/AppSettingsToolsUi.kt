package com.example.unifiedreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.feature.downloads.DownloadEntryStatus
import com.example.unifiedreader.feature.downloads.KeptBookActions
import com.example.unifiedreader.feature.downloads.KeptBooksSnapshot
import com.example.unifiedreader.feature.settings.BackupSettings
import com.example.unifiedreader.feature.settings.NewBookLookSettings
import com.example.unifiedreader.feature.settings.SettingsTools
import com.example.unifiedreader.feature.settings.StorageSettings
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage

@Composable
internal fun rememberKeptBooksForSheet(viewModel: AppViewModel): KeptBooksForSheet {
    val tools = LocalAppSettingsTools.current ?: return KeptBooksForSheet(KeptBooksSnapshot.Empty, null)
    // Measured when the sheet opens, and again as downloads finish.
    val finishedDownloads = viewModel.downloadsSnapshot.entries.count { it.status == DownloadEntryStatus.Completed }
    LaunchedEffect(tools, finishedDownloads) { tools.refresh(withCovers = false) }
    val snapshot by tools.keptBooks.collectAsStateWithLifecycle()
    val actions = remember(tools, viewModel) {
        KeptBookActions(onRemove = { itemIds -> tools.removeKeptBooks(itemIds, onCopiesRemoved(viewModel)) })
    }
    return KeptBooksForSheet(snapshot = snapshot, actions = actions)
}

/** Settings' look for new books, Storage, and Highlights and backup, from the tools; empty without them. */
@Composable
internal fun rememberSettingsTools(uiState: AppUiState, viewModel: AppViewModel): SettingsTools {
    val tools = LocalAppSettingsTools.current ?: return SettingsTools()
    val look by tools.newBookLook.collectAsStateWithLifecycle()
    val storage by tools.storage.collectAsStateWithLifecycle()
    // Measured when Settings opens, and again once covers have cleared or the Downloads sheet closes.
    val clearingCovers = listOf(uiState.kavitaConnection, uiState.grimmoryConnection, uiState.stumpConnection)
        .any { connection -> connection.coverPreload.isClearingCache }
    LaunchedEffect(tools, clearingCovers, viewModel.isDownloadsSheetVisible) {
        if (!clearingCovers && !viewModel.isDownloadsSheetVisible) tools.refresh(withCovers = true)
    }
    val backup = remember(tools) {
        BackupSettings(exportMarkdown = tools::exportMarkdown, backUp = tools::backUp, restore = tools::restore)
    }
    return SettingsTools(
        newBookLook = NewBookLookSettings(description = look, onReset = tools::resetNewBookLook),
        storage = StorageSettings(
            state = storage,
            onClearCovers = { ServerSources.forEach(viewModel::clearCachedCovers) },
            onRemoveFinishedBooks = { tools.removeFinishedBooks(onCopiesRemoved(viewModel)) },
            onManageBooks = viewModel::showDownloadsSheet,
        ),
        backup = backup,
    )
}

/** The library drops the removed books' marks, and a toast says how many left. */
private fun onCopiesRemoved(viewModel: AppViewModel): (Set<String>) -> Unit = { itemIds ->
    if (itemIds.isNotEmpty()) {
        viewModel.onLocalCopiesRemoved(itemIds)
        val books = if (itemIds.size == 1) "1 book" else "${itemIds.size} books"
        viewModel.toasts.show(AppToastMessage(text = "Removed $books from this tablet", icon = AppToastIcon.Removed))
    }
}

private val ServerSources = listOf(SourceType.GRIMMORY, SourceType.KAVITA, SourceType.STUMP)
