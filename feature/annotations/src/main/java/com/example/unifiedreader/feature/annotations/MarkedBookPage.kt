package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppButton
import com.example.unifiedreader.ui.components.AppChip
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.appGutter

/**
 * One book's marks: its cover, name and Continue, the filter chips, then its bookmarks,
 * highlights (with their notes) and saved words. A bookmark or a highlight opens the book there.
 */
@Composable
internal fun MarkedBookPage(
    state: BookmarksState,
    book: MarkedBook,
    actions: BookmarksActions,
    cover: MarkedBookCover,
    contentTopInset: Dp,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
    LaunchedEffect(scrolled) { state.scrolled = scrolled }
    val gutter = appGutter()
    val kind = state.bookKind
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            top = contentTopInset + AppLayout.contentTop,
            end = gutter,
            bottom = AppLayout.contentBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        item(key = "header") {
            MarkedBookHeader(
                book = book,
                cover = cover,
                progress = actions.progressLabel(book.book.itemId),
                onContinue = { actions.continueReading(book.book) },
            )
        }
        item(key = "chips") { MarkKindChips(book = book, selected = kind, onSelect = { state.bookKind = it }) }
        if (kind == MarkKind.All || kind == MarkKind.Bookmarks) bookmarkRows(book, actions)
        if (kind == MarkKind.All || kind == MarkKind.Highlights) highlightRows(book, actions)
        if (kind == MarkKind.All || kind == MarkKind.Words) wordRows(book, actions)
    }
}

@Composable
private fun MarkedBookHeader(
    book: MarkedBook,
    cover: MarkedBookCover,
    progress: String?,
    onContinue: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = AppLayout.headingGap),
        horizontalArrangement = Arrangement.spacedBy(AppLayout.gridGap),
    ) {
        cover(book.book, Modifier.width(HeaderCoverWidth))
        // As tall as the cover at least, Continue at its foot (a weight here fills up to that height).
        Column(
            modifier = Modifier.weight(1f).heightIn(min = HeaderCoverHeight),
            verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        ) {
            Text(
                text = book.book.title,
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = rememberQuoteFontFamily(),
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            book.book.author?.takeIf(String::isNotBlank)?.let { author ->
                Text(text = author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = BookmarksCopy.bookSummary(book),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            AppButton(onClick = onContinue) {
                Icon(imageVector = AppIcons.MenuBook, contentDescription = null, modifier = Modifier.size(SmallIcon))
                Spacer(Modifier.width(AppLayout.headingGap))
                Text(BookmarksCopy.continueAt(progress), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** All, then each kind the book has with its count. */
@Composable
private fun MarkKindChips(book: MarkedBook, selected: MarkKind, onSelect: (MarkKind) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = AppLayout.headingGap),
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        AppChip(selected = selected == MarkKind.All, onClick = { onSelect(MarkKind.All) }, label = { Text(BookmarksCopy.All) })
        listOf(
            MarkKind.Highlights to book.highlights.size,
            MarkKind.Bookmarks to book.bookmarks.size,
            MarkKind.Words to book.words.size,
        ).filter { (_, count) -> count > 0 }.forEach { (kind, count) ->
            AppChip(
                selected = selected == kind,
                onClick = { onSelect(kind) },
                label = { Text(kindLabel(kind)) },
                count = count,
            )
        }
    }
}

private fun LazyListScope.bookmarkRows(book: MarkedBook, actions: BookmarksActions) {
    if (book.bookmarks.isEmpty()) return
    item(key = "bookmarks-label") { SectionLabel(BookmarksCopy.Bookmarks) }
    items(book.bookmarks, key = { "b-${it.id}" }) { bookmark ->
        MarkRow(onClick = { actions.openAt(book.book, bookmark.place.locatorJson) }) {
            Icon(
                imageVector = AppIcons.BookmarkFilled,
                contentDescription = null,
                tint = MarkTint.Bookmark,
                modifier = Modifier.padding(top = RowIconNudge),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RowLineGap)) {
                Text(
                    text = bookmarkTitle(bookmark),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                bookmark.text?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = rememberQuoteFontFamily(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MetaLine(
                    listOfNotNull(chapterOf(bookmark.place.chapterTitle, book.book), BookmarksCopy.moment(bookmark.createdAtEpochMillis)),
                )
            }
            AppIconButton(onClick = { actions.deleteBookmark(bookmark.id) }) {
                Icon(imageVector = AppIcons.Delete, contentDescription = BookmarksCopy.Delete)
            }
        }
    }
}

private fun LazyListScope.highlightRows(book: MarkedBook, actions: BookmarksActions) {
    if (book.highlights.isEmpty()) return
    item(key = "highlights-label") { SectionLabel(BookmarksCopy.Highlights) }
    items(book.highlights, key = { "h-${it.id}" }) { highlight ->
        MarkRow(onClick = { actions.openAt(book.book, highlight.place.locatorJson) }) {
            ColourBar(Color(highlight.color.argb))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RowLineGap)) {
                Text(
                    text = highlight.text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = rememberQuoteFontFamily(),
                    maxLines = QuoteLines,
                    overflow = TextOverflow.Ellipsis,
                )
                highlight.note?.let { note -> NoteBox(note) }
                MetaLine(
                    listOfNotNull(
                        BookmarksCopy.style(highlight.style),
                        chapterOf(highlight.place.chapterTitle, book.book),
                        BookmarksCopy.moment(highlight.createdAtEpochMillis),
                    ),
                )
            }
        }
    }
}

private fun LazyListScope.wordRows(book: MarkedBook, actions: BookmarksActions) {
    if (book.words.isEmpty()) return
    item(key = "words-label") { SectionLabel(BookmarksCopy.SavedWords) }
    items(book.words, key = { "w-${it.id}" }) { word ->
        val context = LocalContext.current
        MarkRow(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RowLineGap)) {
                Text(text = word.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                word.sentence?.let { sentence ->
                    Text(
                        text = sentence,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = rememberQuoteFontFamily(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                word.note?.let { note -> NoteBox(note) }
            }
            AppIconButton(onClick = { context.lookUpWord(word.word) }) {
                Icon(imageVector = AppIcons.Search, contentDescription = "${BookmarksCopy.LookUp}: ${word.word}")
            }
            AppIconButton(onClick = { actions.deleteWord(word.id) }) {
                Icon(imageVector = AppIcons.Delete, contentDescription = BookmarksCopy.Delete)
            }
        }
    }
}

private const val QuoteLines = 6
private val HeaderCoverWidth = 112.dp
private val HeaderCoverHeight = 168.dp
internal val RowLineGap = 4.dp
private val RowIconNudge = 2.dp
