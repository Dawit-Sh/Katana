package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.primaryAuthor
import com.example.unifiedreader.feature.reader.core.ImmersiveReaderOverlay
import com.example.unifiedreader.feature.reader.core.ReaderDocumentState
import com.example.unifiedreader.feature.reader.core.ReaderPageNumber
import com.example.unifiedreader.feature.reader.core.ReaderPageNumberPills
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import com.example.unifiedreader.feature.reader.core.readerOverlayTokens
import com.example.unifiedreader.feature.reader.core.rememberReaderOverlayState
import com.example.unifiedreader.feature.reader.preferences.ReaderPageNumberPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesMode
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesPreferencesStore
import kotlin.math.abs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

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
)

/** Everything the reader's page area and chrome need, gathered by [PdfReaderScreen]. */
internal class PdfReaderParts(
    val book: PdfReaderBook,
    val place: PdfReaderPlace,
    val pages: PdfPagesChoice,
    val actions: PdfReaderSessionActions,
    val opening: PdfOpeningState,
) {
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
    }
}

/** The chrome over the pages, in either way of reading. */
@Composable
private fun PdfReaderBars(
    parts: PdfReaderParts,
    navigation: PdfNavigation,
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
        onPageSelected = navigation.toPage,
    )
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
    )
}
