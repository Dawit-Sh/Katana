package com.example.unifiedreader.feature.reader.comics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.primaryAuthor
import com.example.unifiedreader.feature.annotations.PageBookmarkJumpEffect
import com.example.unifiedreader.feature.annotations.PageBookmarkRibbon
import com.example.unifiedreader.feature.annotations.PageBookmarkSources
import com.example.unifiedreader.feature.annotations.rememberPageBookmarks
import com.example.unifiedreader.feature.reader.core.ImmersiveReaderOverlay
import com.example.unifiedreader.feature.reader.core.ReaderLeave
import com.example.unifiedreader.feature.reader.core.ReaderOpenMode
import com.example.unifiedreader.feature.reader.core.ReaderOpenStatus
import com.example.unifiedreader.feature.reader.core.ReaderOpeningStep
import com.example.unifiedreader.feature.reader.core.ReaderPageNumber
import com.example.unifiedreader.feature.reader.core.ReaderPageNumberPills
import com.example.unifiedreader.feature.reader.core.ReaderWorkCoordinator
import com.example.unifiedreader.feature.reader.core.ReportReaderOpening
import com.example.unifiedreader.feature.reader.core.rememberReaderActiveSessionTracker
import com.example.unifiedreader.feature.reader.core.rememberReaderJumpBack
import com.example.unifiedreader.feature.reader.core.rememberReaderOverlayState
import com.example.unifiedreader.feature.reader.core.rememberReaderSessionLifecycle
import com.example.unifiedreader.feature.reader.preferences.ComicReaderMode
import com.example.unifiedreader.feature.reader.preferences.InMemoryReaderComfortPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.InMemoryReaderPageNumberPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.ReaderComfortPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.ReaderPageNumberPreferencesStore
import com.example.unifiedreader.journal.JournalReadingSessionRecorder
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun ComicsReaderScreen(
    item: ReadableItem,
    pageSource: ComicPageSource,
    progressStore: ComicReaderProgressStore,
    preferencesStore: ComicReaderPreferencesStore,
    journalReadingSessionRecorder: JournalReadingSessionRecorder? = null,
    pageNumberPreferences: ReaderPageNumberPreferencesStore = remember { InMemoryReaderPageNumberPreferencesStore() },
    comfortPreferences: ReaderComfortPreferencesStore = remember { InMemoryReaderComfortPreferencesStore() },
    bookmarkSources: PageBookmarkSources? = null,
    onBack: () -> Unit,
    openMode: ReaderOpenMode = ReaderOpenMode.Default,
    readerWorkCoordinator: ReaderWorkCoordinator? = null,
    onNextItemRequested: () -> Unit = {},
) {
    // Try again asks the page source again.
    var openAttempt by remember(item.id) { mutableIntStateOf(0) }
    val pages = rememberObservedComicPages(
        item = item,
        pageSource = pageSource,
        attempt = openAttempt,
    )
    val progressHydrated by progressStore.isHydratedFlow.collectAsStateWithLifecycle()
    val preferencesHydrated by preferencesStore.isHydratedFlow.collectAsStateWithLifecycle()
    val savedPagesByItemId by progressStore.savedPagesFlow.collectAsStateWithLifecycle()
    val pageNumbersVisible by pageNumberPreferences.isVisibleFlow.collectAsStateWithLifecycle()
    val volumeKeysTurnPages = comfortPreferences.comfortFlow.collectAsStateWithLifecycle().value.volumeKeysTurnPages
    val pageBookmarks = rememberPageBookmarks(bookmarkSources, item.id, item.title, item.bookMetadata?.primaryAuthor())
    val restoredPreferences by preferencesStore.observePreferences(item).collectAsStateWithLifecycle(initialValue = null)

    if (pages.isEmpty()) {
        val pagesUnavailableDiagnostic = rememberComicPagesUnavailableDiagnostic(
            item = item,
            pageSource = pageSource,
            attempt = openAttempt,
        )
        ReportReaderOpening(
            status = pagesUnavailableDiagnostic.openStatus(item),
            colors = comicReaderPageColors(),
            retry = { openAttempt++ },
        )
        return
    }

    val initialPosition = remember(
        item.id,
        openMode,
        progressStore,
        preferencesStore,
        pages.size,
    ) {
        resolveComicReaderInitialPosition(
            openMode = openMode,
            savedPage = progressStore.loadPage(item),
            savedResumeAnchorPage = preferencesStore.loadResumeAnchorPage(item),
            savedResumeSegment = preferencesStore.loadResumeSegment(item),
            savedResumeScrollOffsetRatio = preferencesStore.loadResumeScrollOffsetRatio(item),
            lastPageIndex = pages.lastIndex,
        )
    }
    val readerState = rememberComicsReaderState(
        itemId = item.id,
        pageCount = pages.size,
        initialPage = initialPosition.page,
        initialReaderMode = remember(item.id, preferencesStore) {
            preferencesStore.loadReaderMode(item)
        },
        initialReadingDirection = remember(item.id, preferencesStore) {
            preferencesStore.loadReadingDirection(item)
        },
        initialPageFitMode = remember(item.id, preferencesStore) {
            preferencesStore.loadPageFitMode(item)
        },
        initialSpreadMode = remember(item.id, preferencesStore) {
            preferencesStore.loadSpreadMode(item)
        },
        initialPageTurnStyle = remember(item.id, preferencesStore) {
            preferencesStore.loadPageTurnStyle(item)
        },
        initialSplitWidePages = remember(item.id, preferencesStore) {
            preferencesStore.loadSplitWidePages(item)
        },
        initialResumeAnchorPage = initialPosition.resumeAnchorPage,
        initialResumeSegment = initialPosition.resumeSegment,
        initialResumeScrollOffsetRatio = initialPosition.resumeScrollOffsetRatio,
        initialScrollAxis = remember(item.id, preferencesStore) {
            preferencesStore.loadScrollAxis(item)
        },
    )
    val currentPageProgress = (readerState.currentPage + 1).toFloat() / pages.size.toFloat()
    val lifecycleOwner = LocalLifecycleOwner.current
    val readingSessionTracker = rememberReaderActiveSessionTracker(
        item = item,
        recorder = journalReadingSessionRecorder,
        scope = lifecycleOwner.lifecycleScope,
    )
    var progressRestoreCompleted by remember(item.id) { mutableStateOf(false) }
    var preferencesRestoreCompleted by remember(item.id) { mutableStateOf(false) }
    var loadedVisiblePageIndexes by remember(item.id, pages) { mutableStateOf<Set<Int>>(emptySet()) }
    // The opening state stays until the first page is drawn, or a few seconds at most (a page that
    // fails to load shows its own error in place).
    var firstPageShown by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        delay(ComicOpeningTimeoutMillis)
        firstPageShown = true
    }
    val currentPageLoaded = readerState.currentPage in loadedVisiblePageIndexes
    LaunchedEffect(currentPageLoaded) {
        if (currentPageLoaded) firstPageShown = true
    }
    ReportReaderOpening(
        status = if (firstPageShown) ReaderOpenStatus.Open else ReaderOpenStatus.Opening(ReaderOpeningStep.Opening),
        colors = comicReaderPageColors(),
    )
    var previewLoadingArmed by remember(item.id) { mutableStateOf(false) }
    var completionMonitoringReady by rememberSaveable(item.id) { mutableStateOf(false) }
    var hasAutoRequestedCompletion by rememberSaveable(item.id) { mutableStateOf(false) }
    val overlayState = rememberReaderOverlayState(
        key = "comic-overlay-${item.id}",
        initiallyVisible = true,
    )
    val latestProgressHydrated by rememberUpdatedState(progressHydrated)
    val latestPreferencesHydrated by rememberUpdatedState(preferencesHydrated)
    val latestProgressRestoreCompleted by rememberUpdatedState(progressRestoreCompleted)
    val latestPreferencesRestoreCompleted by rememberUpdatedState(preferencesRestoreCompleted)
    val persistProgress by rememberUpdatedState(
        newValue = {
            progressStore.savePage(
                item = item,
                pageIndex = readerState.currentPage,
                pageCount = pages.size,
                isFinished = isComicProgressFinished(
                    pageIndex = readerState.currentPage,
                    pageCount = pages.size,
                    segment = readerState.resumeSegment,
                    readingDirection = readerState.readingDirection,
                    readerMode = readerState.readerMode,
                    scrollOffsetRatio = readerState.resumeScrollOffsetRatio,
                ),
            )
        },
    )
    val persistCompletedProgress by rememberUpdatedState(
        newValue = {
            progressStore.savePage(
                item = item,
                pageIndex = completedComicPageIndex(pages.size),
                pageCount = pages.size,
                isFinished = true,
            )
        },
    )
    val persistResumePosition by rememberUpdatedState(
        newValue = {
            preferencesStore.saveResumeAnchorPage(
                item = item,
                pageIndex = readerState.resumeAnchorPage,
                progressPage = readerState.currentPage,
            )
            preferencesStore.saveResumeSegment(
                item,
                sanitizeComicResumeSegment(readerState.resumeSegment),
            )
            preferencesStore.saveResumeScrollOffsetRatio(
                item,
                if (readerState.readerMode == ComicReaderMode.VerticalScroll) {
                    sanitizeComicResumeScrollOffsetRatio(readerState.resumeScrollOffsetRatio)
                } else {
                    null
                },
            )
        },
    )
    val persistReaderPreferences by rememberUpdatedState(
        newValue = {
            traceComicReaderSection("ComicsReader.persistPreferences") {
                preferencesStore.saveReaderMode(item, readerState.readerMode)
                preferencesStore.saveReadingDirection(item, readerState.readingDirection)
                preferencesStore.savePageFitMode(item, readerState.pageFitMode)
                preferencesStore.saveSpreadMode(item, readerState.spreadMode)
                preferencesStore.savePageTurnStyle(item, readerState.pageTurnStyle)
                preferencesStore.saveScrollAxis(item, readerState.scrollAxis)
            }
        },
    )
    val flushReadingSession by rememberUpdatedState(
        newValue = {
            readingSessionTracker?.flushAndReset(
                page = readerState.currentPage + 1,
                percent = currentPageProgress.toDouble(),
                pageCount = pages.size,
            )
        },
    )
    val flushPendingProgressWrites by rememberUpdatedState(
        newValue = {
            traceComicReaderSection("ComicsReader.schedulePendingWriteFlush") {
                progressStore.requestFlushPendingWrites()
            }
        },
    )
    val onVisiblePagesLoaded by rememberUpdatedState(
        newValue = { pageIndexes: Collection<Int> ->
            val sanitizedIndexes = pageIndexes
                .filter { pageIndex -> pageIndex in pages.indices }
                .toSet()
            if (sanitizedIndexes.isNotEmpty() && !loadedVisiblePageIndexes.containsAll(sanitizedIndexes)) {
                loadedVisiblePageIndexes = loadedVisiblePageIndexes + sanitizedIndexes
            }
        },
    )
    val restoredPage = savedPagesByItemId[item.id]
    LaunchedEffect(item.id, progressHydrated, restoredPage, pages.size) {
        if (!progressHydrated) {
            progressRestoreCompleted = false
            return@LaunchedEffect
        }
        if (shouldRestoreComicReaderPosition(openMode)) {
            restoredPage?.coerceIn(0, pages.lastIndex)?.let(readerState::restorePageIfUnchanged)
        }
        progressRestoreCompleted = true
    }

    LaunchedEffect(item.id, preferencesHydrated, restoredPreferences) {
        if (!preferencesHydrated) {
            preferencesRestoreCompleted = false
            return@LaunchedEffect
        }
        restoredPreferences?.let { preferences ->
            readerState.restoreReaderModeIfUnchanged(preferences.readerMode)
            readerState.restoreReadingDirectionIfUnchanged(preferences.readingDirection)
            readerState.restorePageFitModeIfUnchanged(preferences.pageFitMode)
            readerState.restoreSpreadModeIfUnchanged(preferences.spreadMode)
            readerState.restorePageTurnStyleIfUnchanged(preferences.pageTurnStyle)
            readerState.restoreScrollAxisIfUnchanged(preferences.scrollAxis)
            if (shouldRestoreComicReaderPosition(openMode)) {
                readerState.restoreResumeAnchorIfUnchanged(preferences.resumeAnchorPage)
                readerState.restoreResumeSegmentIfUnchanged(preferences.resumeSegment)
                readerState.restoreResumeScrollOffsetRatioIfUnchanged(preferences.resumeScrollOffsetRatio)
            }
        }
        preferencesRestoreCompleted = true
    }

    LaunchedEffect(
        item.id,
        readerState.currentPage in loadedVisiblePageIndexes,
        previewLoadingArmed,
    ) {
        if (previewLoadingArmed || readerState.currentPage !in loadedVisiblePageIndexes) return@LaunchedEffect
        delay(InitialPreviewLoadingDelayMillis)
        previewLoadingArmed = true
    }

    LaunchedEffect(
        item.id,
        progressHydrated,
        progressRestoreCompleted,
        preferencesHydrated,
        preferencesRestoreCompleted,
        readerState.currentPage,
        readerState.resumeAnchorPage,
        readerState.resumeSegment,
        readerState.resumeScrollOffsetRatio,
        readerState.readingDirection,
        readerState.readerMode,
        pages.size,
    ) {
        if (!progressHydrated ||
            !progressRestoreCompleted ||
            !preferencesHydrated ||
            !preferencesRestoreCompleted
        ) {
            return@LaunchedEffect
        }
        delay(250)
        persistProgress()
        persistResumePosition()
        val isFinished = isComicProgressFinished(
            pageIndex = readerState.currentPage,
            pageCount = pages.size,
            segment = readerState.resumeSegment,
            readingDirection = readerState.readingDirection,
            readerMode = readerState.readerMode,
            scrollOffsetRatio = readerState.resumeScrollOffsetRatio,
        )
        if (completionMonitoringReady && isFinished && !hasAutoRequestedCompletion) {
            hasAutoRequestedCompletion = true
            onNextItemRequested()
        } else {
            completionMonitoringReady = true
        }
    }

    LaunchedEffect(
        item.id,
        preferencesHydrated,
        preferencesRestoreCompleted,
        progressRestoreCompleted,
    ) {
        if (!progressRestoreCompleted || !preferencesHydrated || !preferencesRestoreCompleted) return@LaunchedEffect
        persistProgress()
        persistResumePosition()
    }

    LaunchedEffect(
        item.id,
        preferencesHydrated,
        preferencesRestoreCompleted,
        readerState.readerMode,
        readerState.readingDirection,
        readerState.pageFitMode,
        readerState.spreadMode,
        readerState.pageTurnStyle,
        readerState.scrollAxis,
    ) {
        if (!preferencesHydrated || !preferencesRestoreCompleted) return@LaunchedEffect
        persistReaderPreferences()
    }

    val closeReader = rememberReaderSessionLifecycle(
        key = item.id,
        onStart = {
            readingSessionTracker?.start(
                page = readerState.currentPage + 1,
                percent = currentPageProgress.toDouble(),
            )
        },
        onLeave = { reason ->
            traceComicReaderSection(reason.comicsTraceName()) {
                flushReadingSession()
                if (latestProgressHydrated &&
                    latestProgressRestoreCompleted &&
                    latestPreferencesHydrated &&
                    latestPreferencesRestoreCompleted
                ) {
                    persistProgress()
                    persistResumePosition()
                }
                if (latestPreferencesHydrated && latestPreferencesRestoreCompleted) {
                    persistReaderPreferences()
                }
                flushPendingProgressWrites()
            }
        },
        onBack = onBack,
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(UnifiedReaderThemeTokens.colors.readerCanvas),
    ) {
        val spreadModeForPlan = when (readerState.readerMode) {
            ComicReaderMode.Paged -> readerState.spreadMode
            ComicReaderMode.VerticalScroll -> ComicSpreadMode.Single
        }
        val useDoublePageSpreadsForPlan = shouldUseDoublePageSpreads(
            spreadMode = spreadModeForPlan,
            maxWidthDp = maxWidth.value,
            maxHeightDp = maxHeight.value,
            pageCount = pages.size,
        )
        val spreadPlanState = rememberComicSpreadPlanState(
            item = item,
            pages = pages,
            pageSource = pageSource,
            currentPage = readerState.currentPage,
            readingDirection = readerState.readingDirection,
            spreadMode = spreadModeForPlan,
            useDoublePageSpreads = useDoublePageSpreadsForPlan,
            metadataLoadingEnabled = readerState.currentPage in loadedVisiblePageIndexes,
            readerWorkCoordinator = readerWorkCoordinator,
        )
        // Built here, not in the paged surface, so the page numbers and the top bar know the spread on screen.
        val pagedSpreads = if (readerState.readerMode == ComicReaderMode.Paged) {
            remember(pages, readerState.readingDirection, useDoublePageSpreadsForPlan, spreadPlanState.metadataByPageId) {
                buildComicPageSpreads(
                    pages = pages,
                    readingDirection = readerState.readingDirection,
                    useDoublePageSpreads = useDoublePageSpreadsForPlan,
                    pageMetadataById = spreadPlanState.metadataByPageId,
                )
            }
        } else {
            emptyList()
        }
        val currentSpread = pagedSpreads.getOrNull(
            pagedSpreads.indexOfPage(readerState.resumeAnchorPage, readerState.resumeSegment),
        )
        val containerSize = IntSize(constraints.maxWidth, constraints.maxHeight)
        // Moves the reader the way its controls ask: a page in paged mode, the top of a page while scrolling.
        val goToPage = { targetPage: Int ->
            when (readerState.readerMode) {
                ComicReaderMode.Paged -> readerState.onPageSettled(targetPage)
                ComicReaderMode.VerticalScroll -> readerState.onContinuousPositionSettled(
                    pageIndex = targetPage,
                    offsetRatio = 0f,
                )
            }
        }
        // After a jump from the slider, the page strip or the pages panel: "Back to page 8" for a moment.
        val jumpBack = rememberReaderJumpBack<Int>(item.id) { pageIndex -> goToPage(pageIndex) }
        val jumpTo = { targetPage: Int ->
            val from = readerState.currentPage
            if (abs(targetPage - from) > 1) jumpBack.jumped(from, from + 1)
            goToPage(targetPage)
        }
        // A bookmark opened from the Bookmarks page or the book's page.
        PageBookmarkJumpEffect(pageBookmarks, item.id, ready = progressRestoreCompleted && preferencesRestoreCompleted) { pageIndex ->
            goToPage(pageIndex.coerceIn(0, pages.lastIndex))
        }
        ImmersiveReaderOverlay(
            overlayState = overlayState,
            autoHideDelayMillis = 0L,
            modifier = Modifier.fillMaxSize(),
            pageIndicator = if (pageNumbersVisible) {
                {
                    ReaderPageNumberPills(
                        numbers = currentSpread?.pageNumbers(
                            pageCount = pages.size,
                            containerSize = containerSize,
                            pageFitMode = readerState.pageFitMode,
                        ) ?: listOf(ReaderPageNumber(comicPageNumberLabel(readerState.currentPage, pages.size))),
                    )
                }
            } else {
                null
            },
            content = {
                val surfaceStateKey = remember(pages) {
                    Triple(
                        pages.size,
                        pages.firstOrNull()?.id.orEmpty(),
                        pages.lastOrNull()?.id.orEmpty(),
                    )
                }
                // A scrolling strip that turns sideways (or back) starts again at the page in view.
                val scrollAxisKey = readerState.scrollAxis.takeIf { readerState.readerMode == ComicReaderMode.VerticalScroll }
                key(item.id, readerState.readerMode, scrollAxisKey, surfaceStateKey) {
                    when (readerState.readerMode) {
                        ComicReaderMode.Paged -> ComicsPagedReaderSurface(
                            item = item,
                            pages = pages,
                            spreads = pagedSpreads,
                            pageSource = pageSource,
                            readerState = readerState,
                            loadedVisiblePageIndexes = loadedVisiblePageIndexes,
                            onVisiblePagesLoaded = onVisiblePagesLoaded,
                            onToggleOverlay = overlayState::toggle,
                            onNavigatePastEnd = {
                                persistCompletedProgress()
                                onNextItemRequested()
                            },
                            volumeKeysTurnPages = volumeKeysTurnPages,
                        )

                        ComicReaderMode.VerticalScroll -> {
                            ComicStripVolumeKeys(enabled = volumeKeysTurnPages, readerState = readerState, pageCount = pages.size)
                            ComicsContinuousReaderSurface(
                                item = item,
                                pages = pages,
                                pageSource = pageSource,
                                readerState = readerState,
                                loadedVisiblePageIndexes = loadedVisiblePageIndexes,
                                metadataLoadingEnabled = readerState.currentPage in loadedVisiblePageIndexes,
                                onVisiblePagesLoaded = onVisiblePagesLoaded,
                                onToggleOverlay = overlayState::toggle,
                                onNavigatePastEnd = {
                                    persistCompletedProgress()
                                    onNextItemRequested()
                                },
                            )
                        }
                    }
                }
            },
            overlay = {
                ComicsReaderChrome(
                    item = item,
                    chromePages = ComicsChromePages(
                        pages = pages,
                        currentPageIndexes = currentSpread?.pageIndexes?.toSet() ?: setOf(readerState.currentPage),
                        progress = currentPageProgress,
                        stripSpreadMode = spreadModeForPlan,
                        spreadPlanState = spreadPlanState,
                    ),
                    sources = ComicsChromeSources(
                        pageSource = pageSource,
                        readerWorkCoordinator = readerWorkCoordinator,
                        stripLoadingEnabled = previewLoadingArmed && readerState.currentPage in loadedVisiblePageIndexes,
                    ),
                    readerState = readerState,
                    pageNumbersVisible = pageNumbersVisible,
                    overlayVisible = overlayState.isVisible,
                    onBack = closeReader,
                    onPageSelected = jumpTo,
                    onProgressSelected = { selectedProgress ->
                        jumpTo((selectedProgress * (pages.size - 1)).roundToInt().coerceIn(0, pages.lastIndex))
                    },
                    onPageNumbersVisibleChanged = pageNumberPreferences::setVisible,
                    bookmark = pageBookmarks?.let { bookmarks ->
                        val pageIndex = readerState.currentPage
                        ComicBookmarkButton(
                            isPageBookmarked = bookmarks.onPage(pageIndex).isNotEmpty(),
                            onClick = { bookmarks.toggle(pageIndex, pages.size) },
                        )
                    },
                    marks = pageBookmarks,
                    markStrip = pageBookmarks?.pageMarkDockStrip { page ->
                        if (pages.isNotEmpty()) jumpTo(page.coerceIn(0, pages.lastIndex))
                    },
                )
            },
        )
        PageBookmarkRibbon(visible = pageBookmarks?.onPage(readerState.currentPage)?.isNotEmpty() == true)
    }
}

private const val InitialPreviewLoadingDelayMillis = 2_500L
private const val ComicOpeningTimeoutMillis = 6_000L

private fun ReaderLeave.comicsTraceName(): String = when (this) {
    ReaderLeave.Close -> "ComicsReader.close"
    ReaderLeave.Stop -> "ComicsReader.lifecycleStop"
    ReaderLeave.Dispose -> "ComicsReader.dispose"
}
