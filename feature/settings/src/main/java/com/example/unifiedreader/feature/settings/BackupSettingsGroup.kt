package com.example.unifiedreader.feature.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppSettingsGroup
import com.example.unifiedreader.ui.components.AppSettingsRow
import com.example.unifiedreader.ui.components.AppSettingsRowDivider
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.LocalAppToastState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Highlights and backup: every highlight, note and saved word as Markdown, a backup
 * file, and Restore, which adds what a backup holds. Android's file picker chooses where each
 * file goes and which backup to read.
 */
@Composable
internal fun BackupSettingsGroup(settings: BackupSettings) {
    val context = LocalContext.current
    val toasts = LocalAppToastState.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SettingsFileExport?>(null) }
    fun toast(text: String, icon: AppToastIcon = AppToastIcon.Done) {
        toasts?.show(AppToastMessage(text = text, icon = icon))
    }
    val saveMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MarkdownMimeType)) { uri ->
        scope.launch { finishSave(context, uri, pending, ::toast).also { pending = null } }
    }
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupMimeType)) { uri ->
        scope.launch { finishSave(context, uri, pending, ::toast).also { pending = null } }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val message = runSuspendCatching {
                val text = withContext(Dispatchers.IO) { context.readText(uri) }
                settings.restore(text)
            }.getOrElse { BackupCopy.RestoreFailed }
            busy = false
            toast(message, if (message == BackupCopy.RestoreFailed) AppToastIcon.Error else AppToastIcon.Done)
        }
    }
    fun start(make: suspend () -> SettingsFileExport, launch: (String) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            runSuspendCatching { make() }
                .onSuccess { export ->
                    pending = export
                    launch(export.fileName)
                }
                .onFailure { toast(BackupCopy.NotMade, AppToastIcon.Error) }
            busy = false
        }
    }
    AppSettingsGroup(label = "Highlights and backup") {
        AppSettingsRow(
            title = "Export everything",
            icon = AppIcons.Description,
            supporting = "Every highlight, note and saved word as Markdown",
            enabled = !busy,
            onClick = { start(settings.exportMarkdown) { name -> saveMarkdown.launch(name) } },
        )
        AppSettingsRowDivider()
        AppSettingsRow(
            title = "Back up to a file",
            icon = AppIcons.Download,
            supporting = "Highlights, notes, saved words and bookmarks",
            enabled = !busy,
            onClick = { start(settings.backUp) { name -> saveBackup.launch(name) } },
        )
        AppSettingsRowDivider()
        AppSettingsRow(
            title = "Restore from a file",
            icon = AppIcons.History,
            supporting = "Adds what the backup has; nothing here is deleted",
            enabled = !busy,
            onClick = { if (!busy) openBackup.launch(arrayOf(BackupMimeType, "text/plain", "application/octet-stream")) },
        )
    }
}

private suspend fun finishSave(
    context: Context,
    uri: Uri?,
    export: SettingsFileExport?,
    toast: (String, AppToastIcon) -> Unit,
) {
    if (uri == null || export == null) return
    val saved = runSuspendCatching {
        withContext(Dispatchers.IO) { context.writeText(uri, export.text) }
    }.isSuccess
    if (saved) toast(BackupCopy.saved(export.fileName), AppToastIcon.Done) else toast(BackupCopy.NotSaved, AppToastIcon.Error)
}

private fun Context.writeText(uri: Uri, text: String) {
    val stream = requireNotNull(contentResolver.openOutputStream(uri, "wt")) { "No file to write" }
    stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(text) }
}

private fun Context.readText(uri: Uri): String {
    val stream = requireNotNull(contentResolver.openInputStream(uri)) { "No file to read" }
    return stream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
}

internal object BackupCopy {
    const val NotMade = "Couldn't gather your highlights"
    const val NotSaved = "Couldn't save the file"
    const val RestoreFailed = "That file isn't a Katana backup"

    fun saved(fileName: String): String = "Saved $fileName"
}

private const val MarkdownMimeType = "text/markdown"
internal const val BackupMimeType = "application/json"
