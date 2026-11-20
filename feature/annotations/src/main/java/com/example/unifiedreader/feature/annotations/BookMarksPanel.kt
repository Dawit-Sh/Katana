package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppPanelHeader
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppToastState
import com.example.unifiedreader.ui.components.AppTonalButton

/** What every reader's marks panel says. */
object BookMarksPanelCopy {
    const val Panel = "Marks in this book"
    const val PanelButton = "Highlights, bookmarks and words"
    const val HighlightsSection = "Highlights"
    const val WordsSection = "Saved words"
    const val ExportHighlights = "Export highlights"
    const val SearchMarks = "Search this book's marks"
    const val NothingFound = "Nothing in this book's marks matches."
    const val LookUp = "Look up"
    const val Delete = "Delete"
    const val Exported = "Exported"
    const val ExportFailed = "Couldn't save the file"

    /** For a book read as page images with text (PDF). */
    const val EmptyPages = "Bookmark a page with the bookmark button, or press and hold words, then Highlight."

    /** For a book read as pictures only (comics). */
    const val EmptyPictures = "Bookmark a page with the bookmark button."

    fun count(highlights: Int, words: Int, bookmarks: Int = 0): String = listOf(
        MarkCounts.highlights(highlights),
        MarkCounts.bookmarks(bookmarks),
        MarkCounts.words(words),
    ).joinToString(" · ")
}

/** Export highlights: one book's marks as Markdown, and the toast once the file is written or not. */
@Stable
class BookMarksExport(
    private val exports: AnnotationExports,
    private val book: AnnotatedBook,
    private val toasts: () -> AppToastState?,
) {
    suspend fun book(): MarkdownExport = exports.book(book)

    fun saved(succeeded: Boolean) {
        val message = if (succeeded) {
            AppToastMessage(text = BookMarksPanelCopy.Exported, icon = AppToastIcon.Done)
        } else {
            AppToastMessage(text = BookMarksPanelCopy.ExportFailed, icon = AppToastIcon.Error)
        }
        toasts()?.show(message)
    }
}

/** What a reader's marks panel does with its rows: each reader goes to a place its own way. */
class BookMarksPanelActions(
    /** Goes to a mark's place (its stored locator); the panel closes after. */
    val onJump: (locatorJson: String) -> Unit,
    val onDeleteBookmark: (ReaderBookmark) -> Unit,
    val onNameBookmark: (bookmark: ReaderBookmark, title: String, note: String) -> Unit,
    val onDeleteWord: (SavedWord) -> Unit,
    val export: BookMarksExport,
)

/**
 * A book's marks, the inside of every reader's marks panel (the reader puts it in its own panel): the heading
 * with the counts, a search over them once there are any, then the bookmarks, the highlights under their chapters
 * (a PDF's under their pages) and the saved words, with Export highlights at the foot. A row goes to its place.
 * The list opens at the highlights around [here] (how far through the book the reader is, 0..1).
 */
@Composable
fun ColumnScope.BookMarksPanelBody(
    highlights: List<ReaderHighlight>,
    words: List<SavedWord>,
    bookmarks: List<ReaderBookmark>,
    actions: BookMarksPanelActions,
    emptyText: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    here: Double? = null,
    // The counts under the heading; a reader without some kinds of marks leaves them out.
    subtitle: String = BookMarksPanelCopy.count(highlights.size, words.size, bookmarks.size),
) {
    AppPanelHeader(
        title = BookMarksPanelCopy.Panel,
        subtitle = subtitle,
        onClose = onDismissRequest,
    )
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<ReaderBookmark?>(null) }
    editing?.let { bookmark ->
        BookmarkNameDialog(
            bookmark = bookmark,
            onDismiss = { editing = null },
            onSave = { title, note ->
                actions.onNameBookmark(bookmark, title, note)
                editing = null
            },
        )
    }
    if (bookmarks.isNotEmpty() || highlights.isNotEmpty() || words.isNotEmpty()) {
        MarkSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = BookMarksPanelCopy.SearchMarks,
            modifier = Modifier.padding(horizontal = AppLayout.menuInset),
        )
    }
    val rows = remember(bookmarks, highlights, words, query) {
        bookMarksRows(
            bookmarks = bookmarks.filter { MarkSearch.matches(it, query) },
            highlights = highlights.filter { MarkSearch.matches(it, query) },
            words = words.filter { MarkSearch.matches(it, query) },
        )
    }
    BookMarksList(
        rows = rows,
        emptyText = if (query.isNotBlank()) BookMarksPanelCopy.NothingFound else emptyText,
        startAt = remember(rows) { bookMarksStartRow(rows, here) },
        actions = actions,
        onJumped = onDismissRequest,
        onEditBookmark = { bookmark -> editing = bookmark },
        modifier = Modifier.weight(1f).then(modifier),
    )
    BookMarksExportFooter(export = actions.export)
}

/** One line of the marks list. */
internal sealed interface BookMarksRow {
    val key: String

    data class Title(val text: String) : BookMarksRow {
        override val key: String get() = "t-$text"
    }

    /** A chapter's (or a PDF page's) name over its highlights. */
    data class Chapter(val text: String, val index: Int) : BookMarksRow {
        override val key: String get() = "c-$index"
    }

    data class Bookmark(val bookmark: ReaderBookmark) : BookMarksRow {
        override val key: String get() = "b-${bookmark.id}"
    }

    data class Highlight(val highlight: ReaderHighlight) : BookMarksRow {
        override val key: String get() = "h-${highlight.id}"
    }

    data class Word(val word: SavedWord) : BookMarksRow {
        override val key: String get() = "w-${word.id}"
    }
}

/** The list's lines: bookmarks, then highlights in reading order under a heading each time the chapter changes, then words. */
internal fun bookMarksRows(
    bookmarks: List<ReaderBookmark>,
    highlights: List<ReaderHighlight>,
    words: List<SavedWord>,
): List<BookMarksRow> = buildList {
    if (bookmarks.isNotEmpty()) {
        add(BookMarksRow.Title(bookmarksHeading(bookmarks.size)))
        bookmarks.forEach { add(BookMarksRow.Bookmark(it)) }
    }
    if (highlights.isNotEmpty()) {
        add(BookMarksRow.Title(BookMarksPanelCopy.HighlightsSection))
        var chapter: String? = null
        highlights.inReadingOrder().forEach { highlight ->
            val title = highlight.place.chapterTitle?.oneLine()?.takeIf(String::isNotBlank)
            if (title != null && title != chapter) {
                add(BookMarksRow.Chapter(title, size))
                chapter = title
            }
            add(BookMarksRow.Highlight(highlight))
        }
    }
    if (words.isNotEmpty()) {
        add(BookMarksRow.Title(BookMarksPanelCopy.WordsSection))
        words.forEach { add(BookMarksRow.Word(it)) }
    }
}

/**
 * Where the list opens: at the first highlight at or after [here] (with its chapter's heading above it), else
 * the last highlight; without highlights, the first bookmark at or after [here]. The top without a place.
 */
internal fun bookMarksStartRow(rows: List<BookMarksRow>, here: Double?): Int {
    val highlights = rows.placesOf<BookMarksRow.Highlight> { it.highlight.place.progression }
    val bookmarks = rows.placesOf<BookMarksRow.Bookmark> { it.bookmark.place.progression }
    val places = highlights.ifEmpty { bookmarks }
    val target = here?.let { at ->
        places.firstOrNull { (_, progression) -> progression != null && progression >= at - HereSlack }?.first
            ?: places.lastOrNull()?.first
    } ?: 0
    // The chapter heading (or the section's title) right above it shows too.
    val above = rows.getOrNull(target - 1)
    return if (above is BookMarksRow.Title || above is BookMarksRow.Chapter) target - 1 else target
}

/** The rows of kind [T], each with how far through the book it is. */
private inline fun <reified T : BookMarksRow> List<BookMarksRow>.placesOf(progressionOf: (T) -> Double?): List<Pair<Int, Double?>> =
    mapIndexedNotNull { index, row -> (row as? T)?.let { index to progressionOf(it) } }

@Composable
private fun BookMarksList(
    rows: List<BookMarksRow>,
    emptyText: String,
    startAt: Int,
    actions: BookMarksPanelActions,
    onJumped: () -> Unit,
    onEditBookmark: (ReaderBookmark) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startAt)
    val jump = { locatorJson: String ->
        actions.onJump(locatorJson)
        onJumped()
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = AppLayout.menuInset, vertical = AppLayout.headingGap),
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        if (rows.isEmpty()) {
            item {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(rows, key = BookMarksRow::key) { row ->
            when (row) {
                is BookMarksRow.Title -> PanelSectionTitle(row.text)
                is BookMarksRow.Chapter -> ChapterTitle(row.text)
                is BookMarksRow.Bookmark -> BookmarkLine(
                    bookmark = row.bookmark,
                    onClick = { jump(row.bookmark.place.locatorJson) },
                    onDelete = { actions.onDeleteBookmark(row.bookmark) },
                    onEdit = { onEditBookmark(row.bookmark) },
                )
                is BookMarksRow.Highlight -> HighlightRow(highlight = row.highlight, onClick = { jump(row.highlight.place.locatorJson) })
                is BookMarksRow.Word -> WordRow(
                    word = row.word,
                    onLookUp = { context.lookUpWord(row.word.word) },
                    onDelete = { actions.onDeleteWord(row.word) },
                )
            }
        }
    }
}

/** Export highlights: this book's marks as Markdown, saved where the reader picks. */
@Composable
private fun BookMarksExportFooter(export: BookMarksExport) {
    val save = rememberMarkdownSaver(onSaved = export::saved)
    Box(modifier = Modifier.fillMaxWidth().padding(AppLayout.menuInset)) {
        AppTonalButton(onClick = { save(export::book) }, modifier = Modifier.fillMaxWidth()) {
            Icon(imageVector = AppIcons.Download, contentDescription = null, modifier = Modifier.size(ButtonIcon))
            Text(text = BookMarksPanelCopy.ExportHighlights, modifier = Modifier.padding(start = AppLayout.headingGap))
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
private fun ChapterTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
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
            Icon(imageVector = AppIcons.Search, contentDescription = BookMarksPanelCopy.LookUp)
        }
        AppIconButton(onClick = onDelete) {
            Icon(imageVector = AppIcons.Delete, contentDescription = BookMarksPanelCopy.Delete)
        }
    }
}

/** A mark a little before the reader's place still counts as here (the place is a page's start). */
private const val HereSlack = 0.0005
private const val HighlightLines = 4
private const val NoteLines = 2
private val MarkWidth = 3.dp
private val MarkHeight = 40.dp
private val ButtonIcon = 18.dp
