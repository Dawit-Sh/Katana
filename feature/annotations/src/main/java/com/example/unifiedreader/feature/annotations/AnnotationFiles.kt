package com.example.unifiedreader.feature.annotations

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saves a Markdown export where the reader picks (the system's Save to… screen). Call the result
 * with the export to make; [onSaved] hears whether the file was written (not called when the
 * reader backs out of the picker).
 */
@Composable
fun rememberMarkdownSaver(onSaved: (Boolean) -> Unit): (suspend () -> MarkdownExport) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnSaved by rememberUpdatedState(onSaved)
    var pending by remember { mutableStateOf<MarkdownExport?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MarkdownMime)) { uri ->
        val export = pending
        pending = null
        if (uri == null || export == null) return@rememberLauncherForActivityResult
        scope.launch { latestOnSaved(context.writeText(uri, export.text)) }
    }
    return remember(launcher, scope) {
        { make ->
            scope.launch {
                val export = make()
                pending = export
                launcher.launch(export.fileName)
            }
        }
    }
}

/** A web search for the word, in whatever the tablet searches with; a browser when nothing takes it. */
fun Context.lookUpWord(word: String) {
    val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, "define $word")
    try {
        startActivity(search)
    } catch (_: ActivityNotFoundException) {
        val page = Intent(Intent.ACTION_VIEW, ("https://duckduckgo.com/?q=" + Uri.encode("define $word")).toUri())
        try {
            startActivity(page)
        } catch (_: ActivityNotFoundException) {
            // Nothing on the tablet opens a web page; the word stays saved.
        }
    }
}

private suspend fun Context.writeText(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
    try {
        contentResolver.openOutputStream(uri, "wt")?.use { stream -> stream.write(text.toByteArray(Charsets.UTF_8)) } != null
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private const val MarkdownMime = "text/markdown"
