package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.primaryAuthor
import com.example.unifiedreader.feature.annotations.HighlightCardOverlay
import com.example.unifiedreader.feature.annotations.HighlightNoteDialog
import com.example.unifiedreader.feature.annotations.PageBookmarkJumpEffect
import com.example.unifiedreader.feature.annotations.PageBookmarkRibbon
import com.example.unifiedreader.feature.annotations.PageBookmarks
import com.example.unifiedreader.feature.reader.core.ImmersiveReaderOverlay
import com.example.unifiedreader.feature.reader.core.ReaderDocumentState
import com.example.unifiedreader.feature.reader.core.ReaderPageNumber
import com.example.unifiedreader.feature.reader.core.ReaderPageNumberPills
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import com.example.unifiedreader.feature.reader.core.ReaderTapSide
import com.example.unifiedreader.feature.reader.core.ReaderVolumeKeys
import com.example.unifiedreader.feature.reader.core.readerOverlayTokens
import com.example.unifiedreader.feature.reader.core.rememberReaderJumpBack
import com.example.unifiedreader.feature.reader.core.rememberReaderOverlayState
import com.example.unifiedreader.feature.reader.preferences.ReaderPageNumberPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesMode
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesPreferencesStore
import kotlin.math.abs

/**
 * The book's "Pages" choice ([mode], null until the session store and the last pick have loaded) and the
 * finer place a scrolling session left ([savedAnchor], read once, for opening there).
 */
@Stable
internal class PdfPagesChoice(
    private val item: ReadableItem,
    private val sessionStore: ReaderSessionStore?,
    private val pagesPreferences: ReaderPagesPreferencesStore,
) {
    var mode by mutableStateOf<ReaderPagesMode?>(null)
        internal set

    var savedAnchor: PdfScrollAnchor? = null
        private set

    internal fun resolve(lastPick: ReaderPagesMode?) {
        val session = sessionStore?.getSession(item)
        savedAnchor = decodePdfScrollAnchor(session?.preferences?.get(PdfScrollAnchorKey))
        mode = restorePdfPagesMode(session, lastPick)
    }

    /** The reader's pick: kept for this book, and for the PDF and DjVu books not set yet. */
    fun select(newMode: ReaderPagesMode) {
        if (newMode == mode) return
        mode = newMode
        savedAnchor = null
        sessionStore?.updateSession(item = item, preferences = mapOf(PdfPagesKey to newMode.name))
        pagesPreferences.setPageImagesPick(newMode)
    }

    fun saveAnchor(anchor: PdfScrollAnchor) {
        sessionStore?.updateSession(item = item, preferences = mapOf(PdfScrollAnchorKey to encodePdfScrollAnchor(anchor)))
    }
}

@Composable
internal fun rememberPdfPagesChoice(
    item: ReadableItem,
    sessionStore: ReaderSessionStore?,
    pagesPreferences: ReaderPagesPreferencesStore,
): PdfPagesChoice {
    // A rotation keeps the choice made in this visit.
    var kept by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
    val hydrated by sessionStore?.isHydratedFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(true) }
    val pick by pagesPreferences.pickFlow.collectAsStateWithLifecycle()
    fun PdfPagesChoice.resolveWhenLoaded() {
        if (mode != null || !hydrated || !pick.isLoaded) return
        resolve(pick.pageImages)
        ReaderPagesMode.entries.firstOrNull { it.name == kept }?.let { mode = it }
    }
    // Both have long loaded when a book opens, so the way of reading is known in the first frame.
    val choice = remember(item.id, sessionStore, pagesPreferences) {
        PdfPagesChoice(item, sessionStore, pagesPreferences).apply { resolveWhenLoaded() }
    }
    LaunchedEffect(choice, hydrated, pick.isLoaded) { choice.resolveWhenLoaded() }
    LaunchedEffect(choice.mode) { kept = choice.mode?.name ?: kept }
    return choice
}

/** How the reader moves through the book; the same calls in both ways of reading. */
internal class PdfNavigation(
    val toPage: (Int) -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
    val toggleOverlay: () -> Unit,
)

/** What the page area reports for the opening screen: the first page drawn, or the first page failing. */
@Stable
internal class PdfOpeningState {
    var firstPageShown by mutableStateOf(false)
    var pageFailed by mutableStateOf(false)

    fun opened(drawn: Boolean) {
        if (drawn) firstPageShown = true else pageFailed = true
    }
}

/** The book the reader shows: the item, its file, its page count and how a page of it is drawn. */
internal class PdfReaderBook(
    val item: ReadableItem,
    val documentState: ReaderDocumentState,
    val pageCount: Int,
    val pageRenderer: PageImageRenderer,
    /** The book's highlights and selectable text; null for a book that can't keep highlights. */
    val marks: PdfTextMarks? = null,
)

/** Everything the reader's page area and chrome need, gathered by [PdfReaderScreen]. */
internal class PdfReaderParts(
    val book: PdfReaderBook,
    val place: PdfReaderPlace,
    val pages: PdfPagesChoice,
    val actions: PdfReaderSessionActions,
    val opening: PdfOpeningState,
    /** The book's page bookmarks; null when the reader has none. */
    val bookmarks: PageBookmarks? = null,
) {
    val marks: PdfTextMarks?
        get() = book.marks

    val item: ReadableItem
        get() = book.item

    val pageCount: Int
        get() = book.pageCount
}

/**
 * The page area, turning pages ([turnPages], drawn by the entry) or scrolling, under the chrome. Turning
 * keeps today's page view untouched; the scrolling view is only composed while scrolling.
 */
@Composable
internal fun PdfReaderContent(
    parts: PdfReaderParts,
    pageNumberPreferences: ReaderPageNumberPreferencesStore,
    volumeKeysTurnPages: Boolean,
    turnPages: @Composable (PdfNavigation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = parts.item
    val pageCount = parts.pageCount
    val place = parts.place
    val overlayState = rememberReaderOverlayState(key = "pdf-overlay-${item.id}", initiallyVisible = true)
    val scrolling = parts.pages.mode == ReaderPagesMode.Scroll
    // A new scrolling view starts at the page in view; a jump made in an earlier one is not replayed.
    var jump by remember(item.id, scrolling) { mutableStateOf<PdfScrollJump?>(null) }
    var jumpId by remember(item.id) { mutableIntStateOf(0) }
    val navigation = remember(place, pageCount, scrolling, overlayState) {
        fun move(target: Int, animate: Boolean) {
            if (place.goTo(target, pageCount) && scrolling) jump = PdfScrollJump(id = ++jumpId, pageIndex = target, animate = animate)
        }
        PdfNavigation(
            toPage = { target -> move(target, animate = abs(target - place.pageIndex) == 1) },
            previous = { move(place.pageIndex - 1, animate = true) },
            next = {
                if (pageCount > 0 && place.pageIndex >= pageCount - 1) parts.actions.finish() else move(place.pageIndex + 1, animate = true)
            },
            toggleOverlay = overlayState::toggle,
        )
    }
    PdfVolumeKeys(enabled = volumeKeysTurnPages && pageCount > 0, navigation = navigation)
    // After a jump from the slider or the page strip: "Back to page 8" for a moment.
    val jumpTo = rememberPdfJumpTo(item.id, place, pageCount, navigation)
    val safePageIndex = place.safePageIndex(pageCount)
    val pageNumbersVisible by pageNumberPreferences.isVisibleFlow.collectAsStateWithLifecycle()
    Box(modifier = modifier.fillMaxSize().background(readerOverlayTokens().colors.fallbackPage)) {
        ImmersiveReaderOverlay(
            overlayState = overlayState,
            autoHideDelayMillis = 0L,
            modifier = Modifier.fillMaxSize(),
            pageIndicator = if (pageNumbersVisible && pageCount > 0) {
                { ReaderPageNumberPills(listOf(ReaderPageNumber("${safePageIndex + 1} / $pageCount"))) }
            } else {
                null
            },
            content = {
                when (parts.pages.mode) {
                    ReaderPagesMode.Turn -> turnPages(navigation)
                    ReaderPagesMode.Scroll -> PdfScrollPages(parts, jump, navigation)
                    null -> Unit
                }
            },
            overlay = {
                PdfReaderBars(
                    parts = parts,
                    navigation = navigation,
                    onJump = jumpTo,
                    overlayVisible = overlayState.isVisible,
                    settings = PdfReaderSettings(
                        pagesMode = parts.pages.mode ?: ReaderPagesMode.Turn,
                        pageNumbersVisible = pageNumbersVisible,
                        onPagesModeChanged = parts.pages::select,
                        onPageNumbersVisibleChanged = pageNumberPreferences::setVisible,
                    ),
                )
            },
        )
        PdfPageBookmarkLayer(parts = parts, navigation = navigation)
        parts.marks?.let { marks -> PdfHighlightCards(marks) }
    }
}

/** The chrome over the pages, in either way of reading. */
@Composable
private fun PdfReaderBars(
    parts: PdfReaderParts,
    navigation: PdfNavigation,
    onJump: (Int) -> Unit,
    overlayVisible: Boolean,
    settings: PdfReaderSettings,
) {
    val pageCount = parts.pageCount
    val place = parts.place
    PdfReaderChrome(
        title = parts.item.title,
        author = parts.item.bookMetadata?.primaryAuthor(),
        pageRenderer = parts.book.pageRenderer,
        documentState = parts.book.documentState,
        pageCount = pageCount,
        currentPageIndex = place.safePageIndex(pageCount),
        progress = place.progress(pageCount),
        canNavigatePrevious = pageCount > 0 && place.pageIndex > 0,
        canNavigateNext = pageCount > 0,
        settings = settings,
        overlayVisible = overlayVisible,
        onBack = parts.actions.close,
        onPrevious = navigation.previous,
        onNext = navigation.next,
        onPageSelected = onJump,
        bookmark = parts.bookmarks?.let { bookmarks ->
            val pageIndex = place.safePageIndex(pageCount)
            PdfBookmarkButton(
                isPageBookmarked = bookmarks.onPage(pageIndex).isNotEmpty(),
                onClick = { bookmarks.toggle(pageIndex, pageCount) },
            )
        },
        search = parts.marks?.let { marks -> PdfSearchControls(marks.search, onJump) },
        marksPanel = parts.bookmarks?.let { bookmarks ->
            bookmarks.export?.let { export ->
                PdfMarksPanel(
                    bookmarks = bookmarks,
                    export = export,
                    highlights = parts.marks?.highlights.orEmpty(),
                    onJumpToPage = onJump,
                )
            }
        },
    )
}

/** The card on a highlight and its note, over the pages and the bars. */
@Composable
private fun PdfHighlightCards(marks: PdfTextMarks) {
    HighlightCardOverlay(editor = marks.editor, highlights = marks.highlights)
    HighlightNoteDialog(editor = marks.editor, highlights = marks.highlights)
}

/** The ribbon on a bookmarked page, and a bookmark opened from the Bookmarks page or the book's page. */
@Composable
private fun BoxScope.PdfPageBookmarkLayer(parts: PdfReaderParts, navigation: PdfNavigation) {
    val bookmarks = parts.bookmarks ?: return
    val pageCount = parts.pageCount
    PageBookmarkJumpEffect(bookmarks, parts.item.id, ready = parts.place.restored && pageCount > 0) { pageIndex ->
        navigation.toPage(pageIndex.coerceIn(0, pageCount - 1))
    }
    PageBookmarkRibbon(visible = bookmarks.onPage(parts.place.safePageIndex(pageCount)).isNotEmpty())
}

/** The scrolling view, once the book's place is restored; it opens where it was, or at the page turned to. */
@Composable
private fun PdfScrollPages(
    parts: PdfReaderParts,
    jump: PdfScrollJump?,
    navigation: PdfNavigation,
) {
    val uri = (parts.book.documentState as? ReaderDocumentState.Ready)?.uri ?: return
    if (parts.pageCount <= 0 || !parts.place.restored) return
    val start = remember(parts.item.id, uri) {
        pdfScrollStart(parts.place.pageIndex, parts.pages.savedAnchor, parts.pageCount)
    }
    PdfScrollViewport(
        item = parts.item,
        documentUri = uri,
        pageCount = parts.pageCount,
        start = start,
        jump = jump,
        callbacks = remember(parts.place, parts.pages, navigation) {
            PdfScrollCallbacks(
                onPageInView = parts.place::follow,
                onAnchorSettled = { anchor -> if (parts.place.userNavigated) parts.pages.saveAnchor(anchor) },
                onToggleOverlay = navigation.toggleOverlay,
                onScrolledPastEnd = parts.actions.finish,
                onOpened = parts.opening::opened,
            )
        },
        modifier = Modifier.fillMaxSize(),
        marks = parts.marks,
    )
}

/** Settings › Reading › "Volume keys turn pages": down for the next page, up for the one before, turning or scrolling. */
@Composable
private fun PdfVolumeKeys(enabled: Boolean, navigation: PdfNavigation) {
    ReaderVolumeKeys(enabled = enabled) { side ->
        when (side) {
            ReaderTapSide.Left -> navigation.previous()
            ReaderTapSide.Right -> navigation.next()
            ReaderTapSide.Middle -> Unit
        }
    }
}

/** Moves to a page the slider or the page strip picked, offering "Back to page 8" after a jump of more than one page. */
@Composable
private fun rememberPdfJumpTo(
    itemId: String,
    place: PdfReaderPlace,
    pageCount: Int,
    navigation: PdfNavigation,
): (Int) -> Unit {
    val jumpBack = rememberReaderJumpBack<Int>(itemId) { pageIndex -> navigation.toPage(pageIndex) }
    return { target: Int ->
        if (abs(target - place.pageIndex) > 1) jumpBack.jumped(place.pageIndex, place.safePageIndex(pageCount) + 1)
        navigation.toPage(target)
    }
}
