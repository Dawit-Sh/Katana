package com.example.unifiedreader.feature.reader.epub

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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.feature.annotations.AnnotationMarkdown
import com.example.unifiedreader.feature.annotations.MarkSearch
import com.example.unifiedreader.feature.annotations.MarkSearchField
import com.example.unifiedreader.feature.annotations.MarkTint
import com.example.unifiedreader.feature.annotations.bookmarksHeading
import com.example.unifiedreader.feature.annotations.ReaderBookmark
import com.example.unifiedreader.feature.annotations.ReaderHighlight
import com.example.unifiedreader.feature.annotations.SavedWord
import com.example.unifiedreader.feature.annotations.lookUpWord
import com.example.unifiedreader.feature.annotations.rememberMarkdownSaver
import com.example.unifiedreader.feature.reader.core.ReaderChromeDefaults
import com.example.unifiedreader.feature.reader.core.ReaderPanel
import com.example.unifiedreader.feature.reader.core.ReaderPanelSlot
import com.example.unifiedreader.ui.components.AppFloatingPanelPlacement
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppPanelHeader
import com.example.unifiedreader.ui.components.AppTonalButton
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * This book's marks: its bookmarks and highlights (in reading order; a tap goes there) and saved
 * words (Look up, remove), with Export highlights (this book, Markdown) at the foot. A search over
 * them sits under the heading once there are any. Every book's export is on the Bookmarks page.
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
    bookmarks: List<ReaderBookmark> = emptyList(),
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
            subtitle = EpubAnnotationCopy.count(highlights.size, words.size, bookmarks.size),
            onClose = onDismissRequest,
        )
        var query by rememberSaveable { mutableStateOf("") }
        if (bookmarks.isNotEmpty() || highlights.isNotEmpty() || words.isNotEmpty()) {
            MarkSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = EpubAnnotationCopy.SearchMarks,
                modifier = Modifier.padding(horizontal = AppLayout.menuInset),
            )
        }
        AnnotationsList(
            bookmarks = remember(bookmarks, query) { bookmarks.filter { MarkSearch.matches(it, query) } },
            highlights = remember(highlights, query) { highlights.filter { MarkSearch.matches(it, query) } },
            words = remember(words, query) { words.filter { MarkSearch.matches(it, query) } },
            searching = query.isNotBlank(),
            onJump = { locatorJson ->
                locatorJson.toLocatorOrNull()?.let(onJump)
                onDismissRequest()
            },
            onDeleteBookmark = { bookmark -> controller.bookmarks.delete(bookmark.id) },
            onDeleteWord = { word -> controller.deleteWord(word.id) },
            modifier = Modifier.weight(1f),
        )
        ExportFooter(controller = controller)
    }
}

/** The book's bookmarks, its highlights in reading order, then its saved words. */
@Composable
private fun AnnotationsList(
    bookmarks: List<ReaderBookmark>,
    highlights: List<ReaderHighlight>,
    words: List<SavedWord>,
    searching: Boolean,
    onJump: (String) -> Unit,
    onDeleteBookmark: (ReaderBookmark) -> Unit,
    onDeleteWord: (SavedWord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = AppLayout.menuInset, vertical = AppLayout.headingGap),
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        if (bookmarks.isEmpty() && highlights.isEmpty() && words.isEmpty()) {
            item {
                Text(
                    text = if (searching) EpubAnnotationCopy.NothingFound else EpubAnnotationCopy.Empty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (bookmarks.isNotEmpty()) {
            item { PanelSectionTitle(bookmarksHeading(bookmarks.size)) }
            items(bookmarks, key = { "b-${it.id}" }) { bookmark ->
                BookmarkRow(
                    bookmark = bookmark,
                    onClick = { onJump(bookmark.place.locatorJson) },
                    onDelete = { onDeleteBookmark(bookmark) },
                )
            }
        }
        if (highlights.isNotEmpty()) {
            item { PanelSectionTitle(EpubAnnotationCopy.HighlightsSection) }
            items(highlights, key = { "h-${it.id}" }) { highlight ->
                HighlightRow(highlight = highlight, onClick = { onJump(highlight.place.locatorJson) })
            }
        }
        if (words.isNotEmpty()) {
            item { PanelSectionTitle(EpubAnnotationCopy.WordsSection) }
            items(words, key = { "w-${it.id}" }) { word ->
                WordRow(word = word, onLookUp = { context.lookUpWord(word.word) }, onDelete = { onDeleteWord(word) })
            }
        }
    }
}

/** Export highlights: this book's marks as Markdown, saved where the reader picks. */
@Composable
private fun ExportFooter(controller: EpubAnnotationController) {
    val save = rememberMarkdownSaver(onSaved = controller.export::saved)
    Box(modifier = Modifier.fillMaxWidth().padding(AppLayout.menuInset)) {
        AppTonalButton(onClick = { save(controller.export::book) }, modifier = Modifier.fillMaxWidth()) {
            Icon(imageVector = AppIcons.Download, contentDescription = null, modifier = Modifier.size(ButtonIcon))
            Text(text = EpubAnnotationCopy.ExportHighlights, modifier = Modifier.padding(start = AppLayout.headingGap))
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
private fun BookmarkRow(bookmark: ReaderBookmark, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.BookmarkFilled,
            contentDescription = null,
            tint = MarkTint.Bookmark,
            modifier = Modifier.size(ButtonIcon),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = AnnotationMarkdown.bookmarkPlace(bookmark),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            (bookmark.text ?: bookmark.place.chapterTitle)?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AppIconButton(onClick = onDelete) {
            Icon(imageVector = AppIcons.Delete, contentDescription = EpubAnnotationCopy.Delete)
        }
    }
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

private fun String.toLocatorOrNull(): Locator? = try {
    Locator.fromJSON(JSONObject(this))
} catch (_: JSONException) {
    null
}

private const val HighlightLines = 4
private const val NoteLines = 2
private val MarkWidth = 3.dp
private val MarkHeight = 40.dp
private val ButtonIcon = 18.dp
