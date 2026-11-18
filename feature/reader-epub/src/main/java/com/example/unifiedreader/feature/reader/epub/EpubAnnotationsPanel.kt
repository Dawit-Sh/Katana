package com.example.unifiedreader.feature.reader.epub

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.feature.annotations.ReaderHighlight
import com.example.unifiedreader.feature.annotations.SavedWord
import com.example.unifiedreader.feature.reader.core.ReaderChromeDefaults
import com.example.unifiedreader.feature.reader.core.ReaderPanel
import com.example.unifiedreader.feature.reader.core.ReaderPanelSlot
import com.example.unifiedreader.ui.components.AppFloatingPanelPlacement
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppPanelHeader
import com.example.unifiedreader.ui.components.AppTextButton
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * This book's highlights (in reading order; a tap goes there) and saved words (Look up, remove),
 * with the Markdown export of this book or of every book at the foot.
 */
@Composable
internal fun EpubAnnotationsPanel(
    isOpen: Boolean,
    slot: ReaderPanelSlot,
    highlights: List<ReaderHighlight>,
    words: List<SavedWord>,
    controller: EpubAnnotationController,
    onDismissRequest: () -> Unit,
    onJump: (Locator) -> Unit,
) {
    ReaderPanel(
        visible = isOpen,
        placement = AppFloatingPanelPlacement.End,
        width = ReaderChromeDefaults.contentsWidth,
        slot = slot,
        onDismissRequest = onDismissRequest,
    ) {
        AppPanelHeader(
            title = EpubAnnotationCopy.Panel,
            subtitle = EpubAnnotationCopy.count(highlights.size, words.size),
            onClose = onDismissRequest,
        )
        AnnotationsList(
            highlights = highlights,
            words = words,
            onHighlightClick = { highlight ->
                highlight.locatorOrNull()?.let(onJump)
                onDismissRequest()
            },
            onDeleteWord = { word -> controller.deleteWord(word.id) },
            modifier = Modifier.weight(1f),
        )
        ExportFooter(exports = controller.exports)
    }
}

/** The book's highlights in reading order, then its saved words. */
@Composable
private fun AnnotationsList(
    highlights: List<ReaderHighlight>,
    words: List<SavedWord>,
    onHighlightClick: (ReaderHighlight) -> Unit,
    onDeleteWord: (SavedWord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = AppLayout.menuInset, vertical = AppLayout.headingGap),
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        if (highlights.isEmpty() && words.isEmpty()) {
            item {
                Text(
                    text = EpubAnnotationCopy.Empty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (highlights.isNotEmpty()) {
            item { PanelSectionTitle(EpubAnnotationCopy.HighlightsSection) }
            items(highlights, key = { "h-${it.id}" }) { highlight ->
                HighlightRow(highlight = highlight, onClick = { onHighlightClick(highlight) })
            }
        }
        if (words.isNotEmpty()) {
            item { PanelSectionTitle(EpubAnnotationCopy.WordsSection) }
            items(words, key = { "w-${it.id}" }) { word ->
                WordRow(word = word, onLookUp = { context.lookUp(word.word) }, onDelete = { onDeleteWord(word) })
            }
        }
    }
}

/** Export: this book or every book, as Markdown, saved where the reader picks. */
@Composable
private fun ExportFooter(exports: EpubAnnotationExports) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<EpubMarkdownExport?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MarkdownMime)) { uri ->
        val export = pending
        pending = null
        if (uri == null || export == null) return@rememberLauncherForActivityResult
        scope.launch { exports.exported(context.writeText(uri, export.text)) }
    }
    fun startExport(make: suspend () -> EpubMarkdownExport) {
        scope.launch {
            val export = make()
            pending = export
            saveLauncher.launch(export.fileName)
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(AppLayout.menuInset)) {
        Text(
            text = EpubAnnotationCopy.Export,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap)) {
            AppTextButton(onClick = { startExport(exports::bookExport) }) { Text(EpubAnnotationCopy.ExportBook) }
            AppTextButton(onClick = { startExport(exports::libraryExport) }) { Text(EpubAnnotationCopy.ExportAll) }
        }
    }
}

@Composable
private fun PanelSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = AppLayout.headingGap),
    )
}

@Composable
private fun HighlightRow(highlight: ReaderHighlight, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        Box(
            modifier = Modifier
                .width(MarkWidth)
                .height(MarkHeight)
                .background(Color(highlight.color.argb)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlight.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = HighlightLines,
                overflow = TextOverflow.Ellipsis,
            )
            highlight.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = NoteLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            highlight.place.chapterTitle?.let { chapter ->
                Text(
                    text = chapter,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WordRow(word: SavedWord, onLookUp: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text(text = word.word, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            word.sentence?.let { sentence ->
                Text(
                    text = sentence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = NoteLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AppIconButton(onClick = onLookUp) {
            Icon(imageVector = AppIcons.Search, contentDescription = EpubAnnotationCopy.LookUp)
        }
        AppIconButton(onClick = onDelete) {
            Icon(imageVector = AppIcons.Delete, contentDescription = EpubAnnotationCopy.Delete)
        }
    }
}

private fun ReaderHighlight.locatorOrNull(): Locator? = try {
    Locator.fromJSON(JSONObject(place.locatorJson))
} catch (_: JSONException) {
    null
}

/** A web search for the word, in whatever the tablet searches with; a browser when nothing takes it. */
private fun Context.lookUp(word: String) {
    val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, "define $word")
    try {
        startActivity(search)
    } catch (_: ActivityNotFoundException) {
        val page = Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode("define $word")))
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
private const val HighlightLines = 4
private const val NoteLines = 2
private val MarkWidth = 3.dp
private val MarkHeight = 40.dp
