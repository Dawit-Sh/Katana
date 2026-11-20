package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.feature.reader.core.ReaderDocumentState
import com.example.unifiedreader.feature.reader.core.ReaderLocator
import com.example.unifiedreader.feature.reader.core.ReaderOpenFailure
import com.example.unifiedreader.feature.reader.core.ReaderOpenMode
import com.example.unifiedreader.feature.reader.core.ReaderOpenStatus
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import com.example.unifiedreader.feature.reader.core.openingStep
import com.example.unifiedreader.feature.reader.core.rememberReaderActiveSessionTracker
import com.example.unifiedreader.feature.reader.core.rememberReaderSessionLifecycle
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesMode
import com.example.unifiedreader.journal.JournalReadingSessionRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Where the page reader is in its book, the same in both ways of reading: the page ([pageIndex], from 0) is
 * the page shown when turning pages and the page across the middle of the screen when scrolling, so
 * switching between them keeps the place. [restored] once the saved place is applied; [userNavigated] once
 * the reader moves, from when on the place is saved.
 */
@Stable
internal class PdfReaderPlace {
    var pageIndex by mutableIntStateOf(0)
        private set

    var restored by mutableStateOf(false)
        private set

    var userNavigated by mutableStateOf(false)
        private set

    fun safePageIndex(pageCount: Int): Int = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    fun progress(pageCount: Int): Float = if (pageCount > 0) (safePageIndex(pageCount) + 1).toFloat() / pageCount else 0f

    /** A move the reader asked for; false when [target] is where the reader already is. */
    fun goTo(target: Int, pageCount: Int): Boolean {
        val page = target.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val moves = pageCount > 0 && page != pageIndex
        if (moves) {
            userNavigated = true
            pageIndex = page
        }
        return moves
    }

    /** The page the scrolling view shows now; [byReader] when the reader scrolled there. */
    fun follow(page: Int, byReader: Boolean) {
        if (byReader) userNavigated = true
        pageIndex = page
    }

    /** The reader finishing the book on its last page. */
    fun markNavigated() {
        userNavigated = true
    }

    fun restore(page: Int) {
        pageIndex = page
        restored = true
    }

    fun skipRestore() {
        restored = true
    }

    val shouldPersist: Boolean
        get() = restored && userNavigated
}

/**
 * The book's place, restored from the session store once it has loaded (unless the reader already moved)
 * and saved whenever the page changes after the reader moved; while scrolling, once the page rests a moment.
 */
@Composable
internal fun rememberPdfReaderPlace(
    item: ReadableItem,
    documentState: ReaderDocumentState,
    pageCount: Int,
    openMode: ReaderOpenMode,
    pagesMode: ReaderPagesMode?,
    sessionStore: ReaderSessionStore?,
): PdfReaderPlace {
    val place = remember(item.id, documentState.cacheKey, openMode) { PdfReaderPlace() }
    val sessionHydrated by sessionStore?.isHydratedFlow?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(true) }
    val savedProgressByItemId by sessionStore?.progressByItemIdFlow?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(emptyMap()) }
    val restoredProgress = savedProgressByItemId[item.id]
    LaunchedEffect(place, pageCount, sessionHydrated, restoredProgress) {
        if (place.restored || !sessionHydrated || pageCount <= 0) return@LaunchedEffect
        if (place.userNavigated) {
            place.skipRestore()
            return@LaunchedEffect
        }
        place.restore(
            initialPdfPageIndex(
                savedProgress = restoredProgress ?: sessionStore?.getProgress(item),
                pageCount = pageCount,
                openMode = openMode,
            ),
        )
    }
    LaunchedEffect(place, place.pageIndex, pageCount, place.shouldPersist) {
        if (pageCount <= 0 || !place.shouldPersist) return@LaunchedEffect
        // Scrolling passes many pages a second; the place is saved once it rests.
        if (pagesMode == ReaderPagesMode.Scroll) delay(PdfScrollProgressRestMillis)
        sessionStore?.savePdfProgress(item = item, pageIndex = place.pageIndex, pageCount = pageCount)
    }
    return place
}

/** Closing the book, and finishing it from its last page. */
internal class PdfReaderSessionActions(
    val close: () -> Unit,
    val finish: () -> Unit,
)

/**
 * The reading session around the book: the journal's session starts with the place restored, and leaving
 * (closing, the app going to the background, the screen going) records it and saves the place.
 */
@Composable
internal fun rememberPdfReaderSessionActions(
    item: ReadableItem,
    place: PdfReaderPlace,
    pageCount: Int,
    sessionStore: ReaderSessionStore?,
    journalReadingSessionRecorder: JournalReadingSessionRecorder?,
    scope: CoroutineScope,
    onBack: () -> Unit,
    onCompletionRequested: () -> Unit,
): PdfReaderSessionActions {
    val tracker = rememberReaderActiveSessionTracker(item = item, recorder = journalReadingSessionRecorder, scope = scope)
    val latestPageCount by rememberUpdatedState(pageCount)
    LaunchedEffect(pageCount, place.restored) {
        if (pageCount > 0 && place.restored) tracker?.start(page = place.pageIndex + 1, percent = place.progress(pageCount).toDouble())
    }
    val flushReadingSession = {
        if (latestPageCount > 0) {
            tracker?.flushAndReset(
                page = place.pageIndex + 1,
                percent = place.progress(latestPageCount).toDouble(),
                pageCount = latestPageCount,
            )
        }
    }
    val close = rememberReaderSessionLifecycle(
        // The next book in a series replaces this one in place: restart for it, with or without a journal.
        key = item.id to tracker,
        onStart = {
            if (latestPageCount > 0 && place.restored) {
                tracker?.start(page = place.pageIndex + 1, percent = place.progress(latestPageCount).toDouble())
            }
        },
        onLeave = {
            flushReadingSession()
            if (latestPageCount > 0 && place.shouldPersist) {
                sessionStore?.savePdfProgress(item = item, pageIndex = place.pageIndex, pageCount = latestPageCount)
            }
            sessionStore?.requestFlushPendingWrites()
        },
        onBack = onBack,
    )
    val latestOnCompletionRequested by rememberUpdatedState(onCompletionRequested)
    val finish = remember(item.id, place, sessionStore) {
        {
            place.markNavigated()
            sessionStore?.savePdfProgress(item = item, pageIndex = place.pageIndex, pageCount = latestPageCount, isFinished = true)
            sessionStore?.requestFlushPendingWrites()
            flushReadingSession()
            latestOnCompletionRequested()
        }
    }
    return remember(close, finish) { PdfReaderSessionActions(close = close, finish = finish) }
}

internal fun initialPdfPageIndex(
    savedProgress: ReaderProgressSnapshot?,
    pageCount: Int,
    openMode: ReaderOpenMode,
): Int {
    if (openMode == ReaderOpenMode.StartFromBeginning) return 0
    val savedPage = savedProgress
        ?.takeIf { progress -> progress.locatorType == LocatorType.PAGE && !progress.isFinished }
        ?.page
    return savedPage?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0
}

internal fun ReaderSessionStore.savePdfProgress(
    item: ReadableItem,
    pageIndex: Int,
    pageCount: Int,
    isFinished: Boolean = false,
) {
    val safePageCount = pageCount.coerceAtLeast(0)
    val safePageIndex = if (safePageCount > 0) pageIndex.coerceIn(0, safePageCount - 1) else 0
    // As in comics: the reader saves where it settles and again as the book closes. Re-saving the stored page
    // would only stamp it as read now, and that stamp beat newer progress from another device (the Fire put
    // its page 6 back over a page 2 read after it, 2026-11-20).
    if (getProgress(item)?.isPdfPlace(safePageIndex, isFinished) == true) {
        updateSession(item = item, pageCount = safePageCount)
        return
    }
    saveProgress(
        item = item,
        locator = ReaderLocator(
            locatorType = LocatorType.PAGE,
            page = safePageIndex,
            percent = if (safePageCount > 0) (safePageIndex + 1).toDouble() / safePageCount.toDouble() else null,
        ),
        isFinished = isFinished,
        syncState = if (item.sourceType == SourceType.LOCAL) SyncState.CLEAN else SyncState.DIRTY,
        pageCount = safePageCount,
    )
}

// The stored place is already this page, finished or not (a reset to unread is never the same place).
private fun ReaderProgressSnapshot.isPdfPlace(pageIndex: Int, finished: Boolean): Boolean =
    !isExplicitUnreadReset && locatorType == LocatorType.PAGE && page == pageIndex && isFinished == finished

/** Until the first page is drawn: the opening; a document or first page that can't be read: why. */
internal fun pdfOpenStatus(
    info: PdfDocumentInfoState,
    document: ReaderDocumentState,
    pageFailed: Boolean,
    firstPageShown: Boolean,
    item: ReadableItem,
): ReaderOpenStatus = when {
    info is PdfDocumentInfoState.Error -> ReaderOpenStatus.Failed(info.failure)
    firstPageShown -> ReaderOpenStatus.Open
    pageFailed -> ReaderOpenStatus.Failed(
        if (item.format == FormatType.DJVU) ReaderOpenFailure.DjvuUnreadable else ReaderOpenFailure.PdfUnreadable,
    )
    else -> ReaderOpenStatus.Opening(document.openingStep(item))
}

private const val PdfScrollProgressRestMillis = 400L
