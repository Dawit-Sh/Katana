package com.example.unifiedreader.feature.reader.pdf

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.primaryAuthor
import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.HighlightEditor
import com.example.unifiedreader.feature.annotations.PageTextSpot
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.ReaderHighlight
import com.example.unifiedreader.feature.annotations.copyToClipboard
import com.example.unifiedreader.feature.annotations.pageHighlightPlace
import com.example.unifiedreader.feature.annotations.pageTextSpotOf
import com.example.unifiedreader.feature.annotations.rememberHighlightEditor
import com.example.unifiedreader.feature.reader.core.ReaderDocumentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A highlight on a PDF page, with the lines it covers (page fractions). */
@Immutable
internal class PdfHighlightMark(val highlight: ReaderHighlight, val spot: PageTextSpot) {
    /** Whether ([point]) of the page is on one of its lines, give or take [slop] (page fractions). */
    fun covers(point: Offset, slop: Float): Boolean = spot.rects.any { it.contains(point.x, point.y, slop) }
}

/**
 * A PDF's highlights and selected words, for the page views: which highlights each page has, the words being
 * selected (one selection in the book at a time), and what the selection's menu does. Places are fractions
 * of a page, so they hold at any zoom; the views turn them into the screen's pixels. The card on a highlight
 * and its note are the shared [editor]'s.
 */
@Stable
internal class PdfTextMarks(
    private val book: AnnotatedBook,
    private val text: PageTextSource,
    val editor: HighlightEditor,
    private val scope: CoroutineScope,
    private val highlightsState: () -> List<ReaderHighlight>,
    private val pageCount: () -> Int,
) {
    /** The book's highlights, in reading order. */
    val highlights: List<ReaderHighlight> get() = highlightsState()

    /** Search in the book, through the same text. */
    val search = PdfBookSearch(text = text, pageCount = pageCount, scope = scope)

    private val byPage by derivedStateOf {
        highlightsState()
            .mapNotNull { highlight -> pageTextSpotOf(highlight.place.locatorJson)?.let { PdfHighlightMark(highlight, it) } }
            .groupBy { mark -> mark.spot.pageIndex }
    }

    var selection by mutableStateOf<PdfTextSelection?>(null)
        private set

    /** A long press is waiting for its page's text. */
    private var pressing by mutableStateOf(false)
    private var press: Job? = null
    private var pressPage = -1
    private var pressFinger: Offset? = null
    private var pressReleased = false

    /** A finger is selecting: a long press waits for its page's text, or a finger moves the selection. */
    val selecting: Boolean get() = pressing || selection?.dragging != null

    fun highlightsOn(pageIndex: Int): List<PdfHighlightMark> = byPage[pageIndex].orEmpty()

    /**
     * A long press at [point] of page [pageIndex] (page fractions): once the page's text is read (at once when it
     * was read before), selects the word within [reach] page widths and calls [onSelected]. Nothing happens on a
     * page without text there.
     */
    fun press(pageIndex: Int, point: Offset, reach: Float, onSelected: () -> Unit) {
        clear()
        pressing = true
        pressPage = pageIndex
        pressFinger = null
        pressReleased = false
        press = scope.launch {
            try {
                val page = text.page(pageIndex) ?: return@launch
                var selected = PdfTextSelection.at(page, point, reach) ?: return@launch
                onSelected()
                pressFinger?.let { finger -> page.charNear(finger.x, finger.y)?.let { selected = selected.dragTo(it) } }
                selection = if (pressReleased) selected.withDrag(null) else selected
            } finally {
                pressing = false
            }
        }
    }

    /** The finger holding [handle] of the selection (or the long press's words) moved to [point] of its page. */
    fun drag(point: Offset) {
        val current = selection
        if (current?.dragging == null) {
            if (pressing) pressFinger = point
            return
        }
        val index = current.page.charNear(point.x, point.y) ?: return
        val next = current.dragTo(index)
        if (!next.sameWords(current)) selection = next
    }

    /** A finger takes hold of one end of the selection. */
    fun grab(handle: PdfSelectionDrag) {
        selection = selection?.withDrag(handle)
    }

    /** The finger lifted: the selection rests, and its menu shows. */
    fun release() {
        pressReleased = true
        selection?.takeIf { it.dragging != null }?.let { selection = it.withDrag(null) }
    }

    fun clear() {
        press?.cancel()
        press = null
        pressing = false
        selection = null
    }

    /** Leaves no selection on any page but [pageIndex] (the page turned away from takes its selection along). */
    fun keepOnly(pageIndex: Int) {
        val elsewhere = selection?.let { it.pageIndex != pageIndex } ?: (pressing && pressPage != pageIndex)
        if (elsewhere) clear()
    }

    /**
     * A tap at [point] of page [pageIndex]: clears a selection, or opens the card of the highlight there, at
     * [anchorOf] it (window pixels). False when the tap was for the page (turning it, the bars).
     */
    fun tap(pageIndex: Int, point: Offset, slop: Float, anchorOf: (PdfHighlightMark) -> Rect?): Boolean {
        if (selection != null || pressing) {
            clear()
            return true
        }
        // The latest made, where highlights overlap.
        val mark = highlightsOn(pageIndex).lastOrNull { it.covers(point, slop) }
        mark?.let { editor.open(it.highlight.id, anchorOf(it)) }
        return mark != null
    }

    /** Highlights the selected words, then opens their card at [anchor] (window pixels), or their note. */
    fun highlightSelection(anchor: Rect?, withNote: Boolean) {
        val current = selection ?: return
        val rects = current.rects
        if (rects.isEmpty()) return
        val spot = PageTextSpot(pageIndex = current.pageIndex, start = current.start, end = current.end, rects = rects)
        editor.add(
            book = book,
            place = pageHighlightPlace(spot, pageCount = pageCount(), pageCharCount = current.page.length),
            text = current.text,
            anchor = anchor,
            withNote = withNote,
        )
        clear()
    }

    fun copySelection(context: Context) {
        selection?.text?.takeIf(String::isNotBlank)?.let(context::copyToClipboard)
        clear()
    }
}

/**
 * The open PDF's or DjVu book's highlights and text, or null when it can't keep highlights: no store, or the file
 * not here yet. The text source (PDFium for a PDF, the hidden text layer for a DjVu book) opens with the book, reads
 * nothing until a long press or a search, and closes when the book does.
 */
@Composable
internal fun rememberPdfTextMarks(
    item: ReadableItem,
    documentState: ReaderDocumentState,
    store: ReaderAnnotationStore?,
    pageCount: Int,
): PdfTextMarks? {
    val uri = (documentState as? ReaderDocumentState.Ready)?.uri
    val format = item.format
    if (store == null || uri == null || format !in TextFormats) return null
    val context = LocalContext.current
    val editor = rememberHighlightEditor(store)
    val scope = rememberCoroutineScope()
    val latestPageCount by rememberUpdatedState(pageCount)
    val source: PageTextSource = remember(documentState.cacheKey) {
        if (format == FormatType.DJVU) {
            DjvuTextSource { page -> readDjvuPageText(context.applicationContext, item, uri, page) }
        } else {
            PdfTextSource(context, uri)
        }
    }
    DisposableEffect(source) { onDispose(source::close) }
    val highlights: State<List<ReaderHighlight>> =
        remember(store, item.id) { store.highlights(item.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(source, editor, item.id) {
        PdfTextMarks(
            book = AnnotatedBook(itemId = item.id, title = item.title, author = item.bookMetadata?.primaryAuthor()),
            text = source,
            editor = editor,
            scope = scope,
            highlightsState = { highlights.value },
            pageCount = { latestPageCount },
        )
    }
}

/** The page books whose words can be selected: a PDF's text, a DjVu book's hidden text layer. */
private val TextFormats = setOf(FormatType.PDF, FormatType.DJVU)
