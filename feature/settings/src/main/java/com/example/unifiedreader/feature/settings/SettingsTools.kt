package com.example.unifiedreader.feature.settings

import com.example.unifiedreader.core.model.formatStorageSize
import com.example.unifiedreader.feature.reader.preferences.ReaderBookLookDescription

/**
 * The Katana groups of the settings hub that the app fills: the look for new books, Storage, and
 * Highlights and backup. A null part leaves its row or group out.
 */
data class SettingsTools(
    val newBookLook: NewBookLookSettings? = null,
    val storage: StorageSettings? = null,
    val backup: BackupSettings? = null,
)

/** Settings › Reading › Default look for new books: what "Use these for new books" saved. */
data class NewBookLookSettings(
    val description: ReaderBookLookDescription,
    val onReset: () -> Unit,
)

/** What Katana keeps on the tablet, in bytes; [isLoaded] false until it has been measured. */
data class StorageSettingsState(
    val downloadedBookBytes: Long = 0L,
    val keptBookBytes: Long = 0L,
    val coverBytes: Long = 0L,
    val freeBytes: Long = 0L,
    /** Books on the tablet you have finished, which Remove finished books takes away. */
    val finishedBookCount: Int = 0,
    val isLoaded: Boolean = false,
) {
    val totalBytes: Long
        get() = downloadedBookBytes + keptBookBytes + coverBytes
}

data class StorageSettings(
    val state: StorageSettingsState,
    val onClearCovers: () -> Unit,
    val onRemoveFinishedBooks: () -> Unit,
    /** Opens the Downloads sheet, where each book on the tablet can go; null leaves the button out. */
    val onManageBooks: (() -> Unit)? = null,
)

/** A text file the reader saves where they pick: [fileName] suggested, [mimeType] for the picker. */
data class SettingsFileExport(
    val fileName: String,
    val mimeType: String,
    val text: String,
)

/** Settings › Highlights and backup. Each call does its work off the main thread. */
data class BackupSettings(
    val exportMarkdown: suspend () -> SettingsFileExport,
    val backUp: suspend () -> SettingsFileExport,
    /** Adds what a backup's text holds; returns what the toast says. */
    val restore: suspend (String) -> String,
)

/** The Storage group's first line: "Katana uses 1.5 GB". */
internal fun storageHeadline(state: StorageSettingsState): String =
    if (state.isLoaded) "Katana uses ${formatStorageSize(state.totalBytes)}" else "Measuring"

/** "of 9.8 GB free" beside the headline. */
internal fun storageFreeLabel(state: StorageSettingsState): String? =
    state.freeBytes.takeIf { state.isLoaded && it > 0L }?.let { "${formatStorageSize(it)} free" }

/** One coloured part of the storage bar, with its legend: "Downloads 1.2 GB". */
internal data class StoragePart(
    val label: String,
    val bytes: Long,
)

internal fun storageParts(state: StorageSettingsState): List<StoragePart> = listOf(
    StoragePart("Downloads", state.downloadedBookBytes),
    StoragePart("Kept from opening", state.keptBookBytes),
    StoragePart("Covers", state.coverBytes),
)

/** The look row's line on the hub: the look, or what happens without one. */
internal fun newBookLookSummary(description: ReaderBookLookDescription): String =
    if (description.isSaved) description.summary else "Not set: books open with the reader's own look"
