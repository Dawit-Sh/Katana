package com.example.unifiedreader.feature.reader.epub

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.primaryAuthor
import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.AnnotationJumps
import com.example.unifiedreader.feature.annotations.HighlightCardOverlay
import com.example.unifiedreader.feature.annotations.HighlightNoteDialog
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.rememberMarkSteps
import com.example.unifiedreader.feature.dictionary.ReaderDictionaryOverlay
import com.example.unifiedreader.feature.reader.core.ImmersiveReaderOverlay
import com.example.unifiedreader.feature.reader.core.rememberReaderActiveSessionTracker
import com.example.unifiedreader.feature.reader.core.rememberReaderSessionLifecycle
import com.example.unifiedreader.feature.reader.core.ReaderChapterPosition
import com.example.unifiedreader.feature.reader.core.ReaderDocumentState
import com.example.unifiedreader.feature.reader.core.ReaderLocator
import com.example.unifiedreader.feature.reader.core.ReaderOpenFailure
import com.example.unifiedreader.feature.reader.core.ReaderOpenStatus
import com.example.unifiedreader.feature.reader.core.ReaderOpeningStep
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import com.example.unifiedreader.feature.reader.core.ReportReaderOpening
import com.example.unifiedreader.feature.reader.core.findContextAncestor
import com.example.unifiedreader.feature.reader.core.openingStep
import com.example.unifiedreader.feature.reader.core.readerSessionChapterPreferences
import com.example.unifiedreader.feature.reader.core.readerSessionShownPagePreferences
import com.example.unifiedreader.feature.reader.core.rememberReaderDocument
import com.example.unifiedreader.feature.reader.core.rememberReaderJumpBack
import com.example.unifiedreader.feature.reader.core.rememberReaderOverlayState
import com.example.unifiedreader.feature.reader.preferences.ReaderDisplayPreferences
import com.example.unifiedreader.journal.JournalReadingSessionRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import com.example.unifiedreader.feature.translation.ReaderTranslationOverlay
import com.example.unifiedreader.feature.translation.TranslationService
import com.example.unifiedreader.feature.translation.rememberTranslationSession
import org.readium.r2.shared.publication.Locator

@Composable
fun EpubReaderScreen(
    item: ReadableItem,
    sessionStore: ReaderSessionStore,
    journalReadingSessionRecorder: JournalReadingSessionRecorder? = null,
    documentUri: Uri? = null,
    documentUriProvider: (suspend (ReadableItem) -> Uri?)? = null,
    displayPreferences: ReaderDisplayPreferences = remember { ReaderDisplayPreferences() },
    onBack: () -> Unit,
    onCompletionRequested: () -> Unit = {},
    translation: TranslationService? = null,
    annotationStore: ReaderAnnotationStore? = null,
    annotationJumps: AnnotationJumps? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findContextAncestor<FragmentActivity>() }
    val isLandscape = remember(
        configuration.orientation,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
    ) {
        when (configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> true
            Configuration.ORIENTATION_PORTRAIT -> false
            else -> configuration.screenWidthDp > configuration.screenHeightDp
        }
    }
    val appBackgroundIsLight = MaterialTheme.colorScheme.background.luminance() >= 0.5f
    val viewportSpacing = currentEpubViewportSpacing(isLandscape)
    // Readium shows a spread only on a page area 960 dp wide or more: a phone gets one page even in landscape.
    val spreadFits = epubShowsSpread(currentEpubPageAreaWidthDp())
    val landscapeSpread = isLandscape && spreadFits
    if (activity == null) {
        ReportReaderOpening(ReaderOpenStatus.Failed(ReaderOpenFailure.Unknown))
        return
    }
    val comfort = rememberEpubReaderComfort(displayPreferences.comfort)
    EpubReaderComfortEffects(comfort, activity)

    val hydrated by sessionStore.isHydratedFlow.collectAsStateWithLifecycle()
    val progressByItemId by sessionStore.progressByItemIdFlow.collectAsStateWithLifecycle()
    val sessionByItemId by sessionStore.sessionByItemIdFlow.collectAsStateWithLifecycle()
    val progressSnapshot = progressByItemId[item.id]
    val sessionSnapshot = sessionByItemId[item.id]
    val pageColorPick by displayPreferences.pageColor.pickFlow.collectAsStateWithLifecycle()
    val newBookLook by displayPreferences.bookLook.lookFlow.collectAsStateWithLifecycle()
    if (!hydrated || !pageColorPick.isLoaded || !newBookLook.isLoaded) {
        ReportReaderOpening(ReaderOpenStatus.Opening(ReaderOpeningStep.Opening))
        return
    }
    var initialRestoreLocked by rememberSaveable(item.id) { mutableStateOf(false) }
    // Whether the page has shown the book in this visit. Unlike initialRestoreLocked it isn't saved, so a
    // book reopened right after reading in it doesn't take a jump (from My words or Bookmarks) before its
    // page is up, which the saved place then overrode.
    var bookShownThisVisit by remember(item.id) { mutableStateOf(false) }
    // Locations reported before the page is touched or navigated are the book opening and settling
    // where it was. Saving them would stamp it as read now, over newer progress from another device.
    var readerTouched by remember(item.id) { mutableStateOf(false) }
    var positionMovedSinceOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    var restoreProgressSnapshot by remember(item.id) {
        mutableStateOf(progressSnapshot)
    }
    var restoreSessionSnapshot by remember(item.id) {
        mutableStateOf(sessionSnapshot)
    }
    LaunchedEffect(progressSnapshot, sessionSnapshot, initialRestoreLocked) {
        if (!initialRestoreLocked) {
            restoreProgressSnapshot = progressSnapshot
            restoreSessionSnapshot = sessionSnapshot
        }
    }
    val serializedInitialLocator = restoreProgressSnapshot?.toReadiumLocator()
    val restoredPreferences = restoreEpubPreferences(restoreSessionSnapshot, newBookLook.values)
    val defaultPageColor = defaultEpubPageColor(pageColorPick.name, appBackgroundIsLight)
    val restoredThemeSelection = remember(restoreSessionSnapshot, restoredPreferences, defaultPageColor) {
        resolveRestoredPageColor(
            restoredPreferences = restoredPreferences,
            rawPreferences = restoreSessionSnapshot?.preferences.orEmpty(),
            defaultColor = defaultPageColor,
        )
    }
    var typographyMode by rememberSaveable(item.id) { mutableStateOf(restoredPreferences.typographyMode) }
    var fontFamily by rememberSaveable(item.id) { mutableStateOf(restoredPreferences.fontFamily) }
    var fontSize by rememberSaveable(item.id) { mutableDoubleStateOf(restoredPreferences.fontSize) }
    var lineHeight by rememberSaveable(item.id) { mutableDoubleStateOf(restoredPreferences.lineHeight) }
    var pageMargins by rememberSaveable(item.id) { mutableDoubleStateOf(restoredPreferences.pageMargins) }
    var paragraphSpacing by rememberSaveable(item.id) { mutableDoubleStateOf(restoredPreferences.paragraphSpacing) }
    var paragraphIndent by rememberSaveable(item.id) { mutableDoubleStateOf(restoredPreferences.paragraphIndent) }
    var textAlign by rememberSaveable(item.id) { mutableStateOf(restoredPreferences.textAlign) }
    var hyphens by rememberSaveable(item.id) { mutableStateOf(restoredPreferences.hyphens) }
    var pageTurnStyle by rememberSaveable(item.id) { mutableStateOf(restoredPreferences.pageTurnStyle) }
    var theme by rememberSaveable(item.id) { mutableStateOf(restoredThemeSelection.color) }
    var layoutMode by rememberSaveable(item.id) {
        mutableStateOf(
            if (restoredPreferences.layoutModeOverride) {
                restoredPreferences.layoutMode
            } else {
                defaultEpubLayoutMode(landscapeSpread)
            },
        )
    }
    var pageColorFollowsDefault by rememberSaveable(item.id) { mutableStateOf(!restoredThemeSelection.isBookOwn) }
    var useOrientationLayoutDefault by rememberSaveable(item.id) {
        mutableStateOf(!restoredPreferences.layoutModeOverride)
    }
    var restoredSessionState by rememberSaveable(item.id) { mutableStateOf(false) }
    var pageCount by rememberSaveable(item.id) { mutableIntStateOf(restoreSessionSnapshot?.pageCount ?: 0) }
    var currentPage by rememberSaveable(item.id) {
        mutableIntStateOf(restoreProgressSnapshot?.page ?: serializedInitialLocator?.locations?.position ?: 0)
    }
    var currentProgress by rememberSaveable(item.id) {
        mutableDoubleStateOf(
            restoreProgressSnapshot?.percent
                ?: serializedInitialLocator?.locations?.totalProgression
                ?: serializedInitialLocator?.locations?.progression
                ?: 0.0,
        )
    }
    var currentHref by rememberSaveable(item.id) {
        mutableStateOf(serializedInitialLocator?.href?.removeFragment()?.toString())
    }
    var currentLocator by remember(item.id) {
        mutableStateOf(serializedInitialLocator)
    }
    // The chapter the reader is in, saved with the session so the library can name it.
    var currentChapter by remember(item.id) { mutableStateOf<ReaderChapterPosition?>(null) }
    var secondSpreadPage by remember(item.id) { mutableStateOf<Int?>(null) }
    // The navigator's page inside its file, of how many; 0 is a chapter's first page.
    var framePage by remember(item.id) { mutableStateOf(EpubResourcePage(pageIndex = -1, totalPages = null)) }
    val pageNumbersVisible by displayPreferences.pageNumbers.isVisibleFlow.collectAsStateWithLifecycle()
    var hasAutoRequestedCompletion by rememberSaveable(item.id) {
        mutableStateOf(false)
    }
    var seekRequestId by remember { mutableIntStateOf(0) }
    var navigationRequest by remember { mutableStateOf<EpubNavigationRequest?>(null) }
    var openPanel by rememberSaveable(item.id) { mutableStateOf<EpubReaderPanel?>(null) }
    val bookHighlightsFlow = remember(annotationStore, item.id) { annotationStore?.highlights(item.id) ?: flowOf(emptyList()) }
    val bookWordsFlow = remember(annotationStore, item.id) { annotationStore?.words(item.id) ?: flowOf(emptyList()) }
    val bookHighlights by bookHighlightsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val bookWords by bookWordsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val bookBookmarks by remember(annotationStore, item.id) { annotationStore?.bookmarks(item.id) ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var footnoteContent by remember(item.id) { mutableStateOf<EpubFootnoteContent?>(null) }
    val translationSession = translation?.let { service -> rememberTranslationSession(service) }
    val translationState = translation?.state?.collectAsStateWithLifecycle()?.value
    val translationShown = translationSession?.current?.collectAsStateWithLifecycle()?.value != null
    var noteReturn by remember(item.id) { mutableStateOf<EpubNoteReturn?>(null) }
    // After a jump from the slider, the contents, a search hit or a mark: "Back to page 8" for a moment.
    val jumpBack = rememberReaderJumpBack<Locator>(item.id) { locator ->
        readerTouched = true
        navigationRequest = EpubLocatorNavigationRequest(seekRequestId++, locator)
    }
    val leaveForJump = { currentLocator?.let { locator -> jumpBack.jumped(locator, currentPage) } }
    val readerPreferences = EpubReaderPreferences(
        typographyMode = typographyMode,
        fontFamily = fontFamily,
        fontSize = fontSize,
        lineHeight = lineHeight,
        pageMargins = pageMargins,
        paragraphSpacing = paragraphSpacing,
        paragraphIndent = paragraphIndent,
        textAlign = textAlign,
        hyphens = hyphens,
        theme = theme,
        themeOverride = !pageColorFollowsDefault,
        layoutMode = layoutMode,
        layoutModeOverride = !useOrientationLayoutDefault,
        pageTurnStyle = pageTurnStyle,
    )
    // What the page shows: a spread chosen where it can't fit is laid out, and numbered, as one page. The
    // choice itself is kept (and saved) as made.
    val shownPreferences = readerPreferences.copy(layoutMode = layoutMode.fitting(spreadFits))
    val overlayState = rememberReaderOverlayState(
        key = "epub-overlay-${item.id}",
        initiallyVisible = true,
    )
    val readingSessionTracker = rememberReaderActiveSessionTracker(
        item = item,
        recorder = journalReadingSessionRecorder,
        scope = lifecycleOwner.lifecycleScope,
    )
    // Try again asks for the file again.
    var openAttempt by remember(item.id) { mutableIntStateOf(0) }
    val documentState by rememberReaderDocument(item, documentUri, documentUriProvider, openAttempt)

    LaunchedEffect(hydrated, restoredPreferences, defaultPageColor, landscapeSpread, useOrientationLayoutDefault) {
        if (!hydrated || restoredSessionState) return@LaunchedEffect
        val restoredThemeUsesDefault = !restoredThemeSelection.isBookOwn
        val restoredLayoutUsesDefault = !restoredPreferences.layoutModeOverride
        typographyMode = restoredPreferences.typographyMode
        fontFamily = restoredPreferences.fontFamily
        fontSize = restoredPreferences.fontSize
        lineHeight = restoredPreferences.lineHeight
        pageMargins = restoredPreferences.pageMargins
        paragraphSpacing = restoredPreferences.paragraphSpacing
        paragraphIndent = restoredPreferences.paragraphIndent
        textAlign = restoredPreferences.textAlign
        hyphens = restoredPreferences.hyphens
        pageTurnStyle = restoredPreferences.pageTurnStyle
        pageColorFollowsDefault = restoredThemeUsesDefault
        useOrientationLayoutDefault = restoredLayoutUsesDefault
        theme = restoredThemeSelection.color
        layoutMode = if (restoredLayoutUsesDefault) {
            defaultEpubLayoutMode(landscapeSpread)
        } else {
            restoredPreferences.layoutMode
        }
        restoredSessionState = true
    }
    LaunchedEffect(hydrated, restoredSessionState, defaultPageColor, pageColorFollowsDefault) {
        if (!hydrated || !restoredSessionState || !pageColorFollowsDefault) return@LaunchedEffect
        theme = defaultPageColor
    }
    LaunchedEffect(hydrated, restoredSessionState, landscapeSpread, useOrientationLayoutDefault) {
        if (!hydrated || !restoredSessionState || !useOrientationLayoutDefault) return@LaunchedEffect
        layoutMode = defaultEpubLayoutMode(landscapeSpread)
    }
    LaunchedEffect(overlayState.isVisible) {
        if (!overlayState.isVisible) openPanel = null
    }
    // A bar button opens its panel in place of any other, and closes it when it is open.
    val togglePanel = { panel: EpubReaderPanel ->
        overlayState.show()
        openPanel = panel.takeUnless { openPanel == it }
    }
    LaunchedEffect(initialRestoreLocked, restoreProgressSnapshot, restoreSessionSnapshot, serializedInitialLocator) {
        if (initialRestoreLocked) return@LaunchedEffect
        pageCount = restoreSessionSnapshot?.pageCount ?: pageCount
        currentPage = restoreProgressSnapshot?.page ?: serializedInitialLocator?.locations?.position ?: currentPage
        currentProgress = restoreProgressSnapshot?.percent
            ?: serializedInitialLocator?.locations?.totalProgression
            ?: serializedInitialLocator?.locations?.progression
            ?: currentProgress
        currentHref = serializedInitialLocator?.href?.removeFragment()?.toString()
        currentLocator = serializedInitialLocator
    }
    val persistReaderState by rememberUpdatedState(
        newValue = {
            // At a note opened with Go to note, the place to keep is the page it was opened from.
            val savedLocator = noteReturn?.locator ?: currentLocator
            val savedPage = noteReturn?.page ?: currentPage
            val savedProgress = noteReturn?.progress ?: currentProgress
            if (hydrated &&
                restoredSessionState &&
                shouldFlushEpubReaderProgress(
                    navigatorShowedBook = initialRestoreLocked,
                    restoreProgressSnapshot = restoreProgressSnapshot,
                    currentLocator = savedLocator,
                    currentPage = savedPage,
                    currentProgress = savedProgress,
                )
            ) {
                if (positionMovedSinceOpen) sessionStore.saveProgress(
                    item = item,
                    locator = buildCurrentReaderLocator(
                        locator = savedLocator.takeUnless { navigationRequest != null },
                        page = savedPage.takeIf { it > 0 },
                        percent = savedProgress,
                    ),
                    isFinished = pageCount > 0 && savedPage >= pageCount,
                    syncState = SyncState.DIRTY,
                    pageCount = pageCount.takeIf { it > 0 },
                )
                sessionStore.updateSession(
                    item = item,
                    pageCount = pageCount.takeIf { it > 0 },
                    preferences = buildEpubPreferencesMap(readerPreferences) +
                        readerSessionChapterPreferences(currentChapter) +
                        // A place left as it came keeps its own share read: the page is the one shown there.
                        readerSessionShownPagePreferences(
                            page = savedPage,
                            savedPercent = if (positionMovedSinceOpen) savedProgress else restoreProgressSnapshot?.percent,
                        ),
                )
            }
        },
    )
    val flushSessionStoreWrites by rememberUpdatedState(
        newValue = {
            sessionStore.requestFlushPendingWrites()
        },
    )
    val persistFinishedProgress by rememberUpdatedState(
        newValue = {
            val terminalPage = pageCount.takeIf { it > 0 } ?: currentPage.takeIf { it > 0 }
            sessionStore.saveProgress(
                item = item,
                locator = buildCurrentReaderLocator(
                    locator = currentLocator.takeUnless { navigationRequest != null },
                    page = terminalPage,
                    percent = 1.0,
                ),
                isFinished = true,
                syncState = SyncState.DIRTY,
                pageCount = pageCount.takeIf { it > 0 },
            )
        },
    )
    val flushReadingSession by rememberUpdatedState(
        newValue = {
            readingSessionTracker?.flushAndReset(
                page = currentPage.takeIf { it > 0 },
                percent = currentProgress,
                pageCount = pageCount.takeIf { it > 0 },
            )
        },
    )
    val closeReader = rememberReaderSessionLifecycle(
        key = item.id,
        onStart = {
            readingSessionTracker?.start(
                page = currentPage.takeIf { it > 0 },
                percent = currentProgress,
            )
        },
        onLeave = {
            flushReadingSession()
            persistReaderState()
            flushSessionStoreWrites()
        },
        onBack = onBack,
    )

    val publicationState by produceState<EpubPublicationState>(
        initialValue = EpubPublicationState.Loading,
        key1 = item.id,
        key2 = documentState.cacheKey,
    ) {
        value = when (val document = documentState) {
            ReaderDocumentState.Loading -> EpubPublicationState.Loading
            is ReaderDocumentState.Failed -> EpubPublicationState.Error(document.failure)
            is ReaderDocumentState.Ready -> withContext(Dispatchers.IO) {
                openEpubPublication(context.applicationContext, item, document.uri)
            }
        }
    }

    when (val state = publicationState) {
        EpubPublicationState.Loading -> ReportReaderOpening(
            status = ReaderOpenStatus.Opening(documentState.openingStep(item)),
            colors = theme.colors,
        )

        is EpubPublicationState.Error -> ReportReaderOpening(
            status = ReaderOpenStatus.Failed(state.failure),
            colors = theme.colors,
            retry = { openAttempt++ },
        )

        is EpubPublicationState.Ready -> {
            val restoredLocator = remember(state.publication, serializedInitialLocator) {
                serializedInitialLocator?.let(state.publication::resolveEpubRestoredLocator)
            }
            val initialLocatorState by produceState<EpubInitialLocatorState>(
                initialValue = if (restoreProgressSnapshot.needsEpubProgressionLocator(restoredLocator)) {
                    EpubInitialLocatorState.Loading
                } else {
                    EpubInitialLocatorState.Ready(restoredLocator)
                },
                key1 = state.publication,
                key2 = restoreProgressSnapshot,
                key3 = restoredLocator,
            ) {
                value = EpubInitialLocatorState.Ready(
                    restoredLocator
                        ?: restoreProgressSnapshot?.toReadingOrderStartLocator(state.publication)
                        ?: withContext(Dispatchers.IO) {
                            restoreProgressSnapshot?.toProgressionFallbackLocator(state.publication)
                        },
                )
            }
            DisposableEffect(state.publication) {
                onDispose {
                    state.publication.close()
                }
            }
            val bookProgress by produceState(
                initialValue = EpubBookProgress(
                    pageCount = 0,
                    chapterMarks = emptyList(),
                    tocEntries = emptyList(),
                ),
                key1 = state.publication,
            ) {
                value = withContext(Dispatchers.IO) {
                    state.publication.buildEpubBookProgress()
                }
            }
            val annotationController = annotationStore?.let { store ->
                rememberEpubAnnotationController(
                    store = store,
                    book = remember(item.id, item.title) {
                        AnnotatedBook(itemId = item.id, title = item.title, author = item.bookMetadata?.primaryAuthor())
                    },
                    chapterTitleOf = { locator ->
                        bookProgress.tocEntries.currentEpubChapterTitle(
                            currentProgress = locator.locations.totalProgression ?: currentProgress,
                            currentHref = locator.href.toString(),
                        )
                    },
                )
            }
            // Old marks in a book whose chapters share one file were named after the last chapter: name them
            // again once the contents are known (an empty list would wipe good names, so it waits for them).
            LaunchedEffect(annotationStore, item.id, bookProgress.tocEntries) {
                val toc = bookProgress.tocEntries
                if (annotationStore == null || toc.isEmpty()) return@LaunchedEffect
                annotationStore.correctChapterNames(item.id) { href, progression ->
                    toc.currentEpubChapterTitle(currentProgress = progression ?: 0.0, currentHref = href)
                }
            }
            EpubHighlightPlacementEffect(
                publication = state.publication,
                itemId = item.id,
                store = annotationStore?.placement,
                chapterTitleOf = { locator ->
                    bookProgress.tocEntries.currentEpubChapterTitle(
                        currentProgress = locator.locations.totalProgression ?: 0.0,
                        currentHref = locator.href.toString(),
                    )
                },
            )
            val bookmarkView = EpubBookmarkView(currentLocator, framePage, currentPage, shownPreferences.layoutMode)
            val pageBookmarks = activity.rememberPageBookmarks(bookBookmarks, item.id, bookmarkView)
            // The bar's bookmark button and a tap in the page's top-end corner.
            val toggleBookmark: (() -> Unit)? = annotationController?.let { controller ->
                {
                    controller.bookmarks.toggle(pageBookmarks, currentLocator, currentPage, pageCount) {
                        activity.epubFirstVisibleText(item.id)
                    }
                }
            }
            // A place picked in the reader or a list (a search hit, a mark): go there, with the way back.
            val jumpTo = { locator: Locator ->
                leaveForJump()
                readerTouched = true
                navigationRequest = EpubLocatorNavigationRequest(seekRequestId++, locator)
            }
            val markSteps = rememberMarkSteps(item.id, bookHighlights, bookBookmarks)
            // A bookmark or highlight opened from the Bookmarks page: go there once the book shows.
            EpubPendingJumpEffect(annotationJumps, item.id, ready = bookShownThisVisit) { locator ->
                markSteps.openedAt(locator)
                jumpTo(locator)
            }
            val bookSearch = rememberEpubBookSearch(state.publication)
            val goToSearchMatch = { match: Locator? -> match?.let(jumpTo) }
            LaunchedEffect(bookProgress.pageCount) {
                if (bookProgress.pageCount > 0) {
                    pageCount = bookProgress.pageCount
                }
            }
            // The same chapter the contents panel marks; until the contents load it stays unknown.
            LaunchedEffect(bookProgress.tocEntries, currentHref, currentProgress) {
                val entries = bookProgress.tocEntries
                if (entries.isEmpty()) return@LaunchedEffect
                currentChapter = entries.epubReaderChapterPosition(currentProgress, currentHref)
            }

            LaunchedEffect(item.id, hydrated, restoredSessionState, readerPreferences, pageCount) {
                if (!hydrated || !restoredSessionState) return@LaunchedEffect
                sessionStore.updateSession(
                    item = item,
                    pageCount = pageCount.takeIf { it > 0 },
                    preferences = buildEpubPreferencesMap(readerPreferences),
                )
            }

            val resolvedInitialLocator = when (val locatorState = initialLocatorState) {
                EpubInitialLocatorState.Loading -> {
                    ReportReaderOpening(ReaderOpenStatus.Opening(ReaderOpeningStep.FindingPage), colors = theme.colors)
                    return
                }
                is EpubInitialLocatorState.Ready -> locatorState.locator
            }
            // The opening state stays until the navigator has laid out its first page.
            ReportReaderOpening(
                status = if (initialRestoreLocked) ReaderOpenStatus.Open else ReaderOpenStatus.Opening(ReaderOpeningStep.FindingPage),
                colors = theme.colors,
            )

            ImmersiveReaderOverlay(
                overlayState = overlayState,
                autoHideDelayMillis = 0L,
                glass = true,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(EpubReaderViewportTag),
                pageIndicator = if (pageNumbersVisible) {
                    {
                        EpubPageFrameCutoutSafe {
                            EpubPageFrameLabels(
                                publication = state.publication,
                                bookTitle = item.title,
                                tocEntries = bookProgress.tocEntries,
                                resourcePositions = bookProgress.resourcePositions,
                                location = EpubFrameLocation(
                                    currentHref,
                                    currentProgress,
                                    currentPage,
                                    pageCount,
                                    secondSpreadPage,
                                    framePage,
                                ),
                                layoutMode = shownPreferences.layoutMode,
                                pageColor = theme,
                                stripHeight = viewportSpacing.containerInset,
                                sideStrip = state.publication.epubSpreadSideStrip(shownPreferences),
                            )
                        }
                    }
                } else {
                    null
                },
                sheet = {
                    EpubBrightnessIndicator(comfort)
                    EpubFootnoteCard(
                        footnote = footnoteContent,
                        pageColor = theme,
                        type = rememberEpubFootnoteType(typographyMode, fontFamily, fontSize, lineHeight),
                        onDismissRequest = { footnoteContent = null },
                        onGoToNote = { link ->
                            footnoteContent = null
                            noteReturn = currentLocator?.let { EpubNoteReturn(it, currentPage, currentProgress, link.url().removeFragment().toString()) }
                            readerTouched = true
                            navigationRequest = EpubNoteNavigationRequest(id = seekRequestId++, link = link)
                        },
                    )
                    if (translation != null && translationSession != null) {
                        ReaderTranslationOverlay(session = translationSession, service = translation)
                    }
                    annotationController?.let { controller ->
                        HighlightCardOverlay(editor = controller.editor, highlights = bookHighlights)
                        HighlightNoteDialog(editor = controller.editor, highlights = bookHighlights)
                        ReaderDictionaryOverlay(
                            request = controller.dictionaryRequest,
                            onDismiss = { controller.dictionaryRequest = null },
                        )
                    }
                },
                content = {
                    ReadiumEpubNavigatorHost(
                        activity = activity,
                        item = item,
                        publication = state.publication,
                        initialLocator = resolvedInitialLocator,
                        preferences = shownPreferences,
                        onLocationChanged = { reportedLocator, resourcePage, isAtBookEnd ->
                            comfort.touched()
                            val locator = reportedLocator.withReadingOrderIndex(
                                state.publication.readingOrderIndexOf(reportedLocator),
                            )
                            val shouldPersistProgress = initialRestoreLocked
                            if (!initialRestoreLocked) {
                                initialRestoreLocked = true
                            }
                            if (!bookShownThisVisit) {
                                bookShownThisVisit = true
                            }
                            currentLocator = locator
                            currentHref = locator.href.removeFragment().toString()
                            val bookPageCount = bookProgress.pageCount
                                .takeIf { it > 0 }
                                ?: state.publication.readingOrder.size.takeIf { it > 0 }
                            if (bookPageCount != null) {
                                pageCount = bookPageCount
                            }
                            val reportedPage = locator.locations.position
                                ?: state.publication.readingOrderPositionFor(locator)
                                ?: resourcePage.pageIndex + 1
                            if (reportedPage > 0) {
                                currentPage = reportedPage
                            }
                            framePage = resourcePage
                            if (readerTouched) positionMovedSinceOpen = true
                            if (resourcePage.totalPages != null) {
                                secondSpreadPage = state.publication
                                    .epubSecondSpreadPage(locator, resourcePage, bookProgress.resourcePositions)
                            }
                            currentProgress = (
                                locator.locations.totalProgression
                                    ?: locator.locations.progression
                                    ?: bookPageCount?.let { pages ->
                                        reportedPage.toDouble() / pages.toDouble()
                                    }
                                    ?: currentProgress
                                ).coerceIn(0.0, 1.0)
                            // A note opened with Go to note is a visit: not progress, and not the book's end.
                            if (shouldPersistProgress && noteReturn == null) {
                                if (positionMovedSinceOpen) sessionStore.saveProgress(
                                    item = item,
                                    locator = buildReaderLocator(
                                        locator = locator,
                                        page = reportedPage.takeIf { it > 0 },
                                    ),
                                    isFinished = isAtBookEnd,
                                    syncState = SyncState.DIRTY,
                                    pageCount = bookPageCount ?: pageCount.takeIf { it > 0 },
                                )
                                if (isAtBookEnd && !hasAutoRequestedCompletion) {
                                    hasAutoRequestedCompletion = true
                                    persistFinishedProgress()
                                    onCompletionRequested()
                                }
                            }
                        },
                        onSurfaceTap = overlayState::toggle,
                        onEndNavigationRequested = {
                            persistFinishedProgress()
                            onCompletionRequested()
                        },
                        onFootnoteActivated = { footnote ->
                            overlayState.hide()
                            footnoteContent = footnote
                        },
                        translation = translationSession?.let { session ->
                            epubSelectionTranslation(session, translationState, translationShown, overlayState::hide)
                        },
                        navigationRequest = navigationRequest,
                        onNavigationRequestConsumed = {
                            navigationRequest = null
                        },
                        modifier = Modifier.fillMaxSize().onEveryTouchDown { readerTouched = true }.epubBrightnessEdgeSwipe(comfort),
                        annotations = annotationController?.let { controller ->
                            remember(controller, bookHighlights) {
                                EpubSelectionAnnotations(
                                    highlights = bookHighlights,
                                    onHighlight = controller::highlight,
                                    onSaveWord = controller::saveWord,
                                    onDictionary = controller::lookUp,
                                    onHighlightActivated = controller::open,
                                )
                            }
                        },
                        searchMatches = bookSearch.decorations,
                        volumeKeysTurnPages = comfort.comfort.volumeKeysTurnPages,
                        onBookmarkCornerTap = toggleBookmark,
                    )
                    EpubBookmarkRibbon(visible = pageBookmarks.isNotEmpty())
                    EpubWarmthTint(comfort)
                },
                overlay = {
                    Box(modifier = Modifier.fillMaxSize()) {
                        EpubReaderChrome(
                            title = item.title,
                            author = item.bookMetadata?.primaryAuthor(),
                            tocEntries = bookProgress.tocEntries,
                            currentHref = currentHref,
                            currentProgress = currentProgress,
                            currentPage = currentPage,
                            pageCount = pageCount,
                            chapterMarks = bookProgress.chapterMarks,
                            isTocOpen = openPanel == EpubReaderPanel.Contents,
                            isPreferencesOpen = openPanel == EpubReaderPanel.Settings,
                            onBack = closeReader,
                            onTocClick = { togglePanel(EpubReaderPanel.Contents) },
                            onPreferencesClick = { togglePanel(EpubReaderPanel.Settings) },
                            onDismissPanels = { openPanel = null },
                            isAnnotationsOpen = openPanel == EpubReaderPanel.Annotations,
                            onAnnotationsClick = annotationController?.let { { togglePanel(EpubReaderPanel.Annotations) } },
                            isPageBookmarked = pageBookmarks.isNotEmpty(),
                            onBookmarkClick = toggleBookmark,
                            isSearchOpen = openPanel == EpubReaderPanel.Search,
                            onSearchClick = { togglePanel(EpubReaderPanel.Search) },
                            dockStrip = if (bookSearch.isActive && openPanel != EpubReaderPanel.Search) {
                                {
                                    EpubSearchDockStrip(
                                        search = bookSearch,
                                        onOpenResults = { togglePanel(EpubReaderPanel.Search) },
                                        onStep = { forward -> goToSearchMatch(bookSearch.step(forward, currentProgress)) },
                                        onEnd = bookSearch::clear,
                                    )
                                }
                            } else {
                                markSteps.epubDockStrip(jumpTo)
                            },
                            onProgressSelected = { selectedProgress ->
                                leaveForJump()
                                currentProgress = selectedProgress.toDouble()
                                readerTouched = true
                                navigationRequest = EpubProgressSeekRequest(
                                    id = seekRequestId++,
                                    progress = selectedProgress,
                                )
                            },
                            modifier = Modifier.testTag(EpubReaderOverlayTag),
                        ) { slot ->
                            EpubTableOfContentsSheet(
                                entries = bookProgress.tocEntries,
                                bookTitle = item.title,
                                currentHref = currentHref,
                                currentProgress = currentProgress,
                                isOpen = openPanel == EpubReaderPanel.Contents,
                                slot = slot,
                                onDismissRequest = { openPanel = null },
                                onEntrySelected = { entry ->
                                    openPanel = null
                                    leaveForJump()
                                    readerTouched = true
                                    navigationRequest = EpubTocNavigationRequest(
                                        id = seekRequestId++,
                                        link = entry.link,
                                    )
                                },
                            )
                            EpubPreferencesSheet(
                                isOpen = openPanel == EpubReaderPanel.Settings,
                                typographyMode = typographyMode,
                                fontFamily = fontFamily,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                pageMargins = pageMargins,
                                paragraphSpacing = paragraphSpacing,
                                paragraphIndent = paragraphIndent,
                                textAlign = textAlign,
                                hyphens = hyphens,
                                theme = theme,
                                layoutMode = layoutMode,
                                useOrientationLayoutDefault = useOrientationLayoutDefault,
                                pageTurnStyle = pageTurnStyle,
                                pageNumbersVisible = pageNumbersVisible,
                                onDismissRequest = { openPanel = null },
                                onTypographyModeChanged = {
                                    typographyMode = it
                                },
                                onFontFamilyChanged = {
                                    fontFamily = it
                                },
                                onFontSizeChanged = {
                                    fontSize = it
                                },
                                onLineHeightChanged = {
                                    lineHeight = it
                                },
                                onPageMarginsChanged = {
                                    pageMargins = it
                                },
                                onParagraphSpacingChanged = {
                                    paragraphSpacing = it
                                },
                                onParagraphIndentChanged = {
                                    paragraphIndent = it
                                },
                                onTextAlignChanged = {
                                    textAlign = it
                                },
                                onHyphensChanged = {
                                    hyphens = it
                                },
                                onThemeChanged = {
                                    pageColorFollowsDefault = false
                                    theme = it
                                    displayPreferences.pageColor.setLastPicked(it.name)
                                },
                                onLayoutModeChanged = {
                                    useOrientationLayoutDefault = false
                                    layoutMode = it
                                },
                                onUseOrientationLayoutDefault = {
                                    useOrientationLayoutDefault = true
                                    layoutMode = defaultEpubLayoutMode(landscapeSpread)
                                },
                                onPageTurnStyleChanged = {
                                    pageTurnStyle = it
                                },
                                onPageNumbersVisibleChanged = displayPreferences.pageNumbers::setVisible,
                                slot = slot,
                                onUseForNewBooks = { displayPreferences.bookLook.save(readerPreferences.newBookLook()) },
                                lightRow = { EpubLightRow(comfort, onClick = { openPanel = EpubReaderPanel.Light }) },
                            )
                            EpubSearchPanel(
                                isOpen = openPanel == EpubReaderPanel.Search,
                                slot = slot,
                                search = bookSearch,
                                publication = state.publication,
                                tocEntries = bookProgress.tocEntries,
                                onDismissRequest = { openPanel = null },
                                onResultSelected = { index ->
                                    openPanel = null
                                    goToSearchMatch(bookSearch.select(index))
                                },
                                onStep = { forward -> goToSearchMatch(bookSearch.step(forward, currentProgress)) },
                            )
                            EpubLightCard(
                                isOpen = openPanel == EpubReaderPanel.Light,
                                slot = slot,
                                comfort = comfort,
                                onDismissRequest = { openPanel = null },
                            )
                            annotationController?.let { controller ->
                                EpubAnnotationsPanel(
                                    isOpen = openPanel == EpubReaderPanel.Annotations,
                                    slot = slot,
                                    highlights = bookHighlights,
                                    words = bookWords,
                                    bookmarks = bookBookmarks,
                                    controller = controller,
                                    onDismissRequest = { openPanel = null },
                                    onJump = { locator ->
                                        markSteps.openedAt(locator)
                                        jumpTo(locator)
                                    },
                                    here = currentProgress,
                                )
                            }
                        }
                    }
                },
            )
            EpubNoteReturnEffects(
                noteReturn = noteReturn,
                currentHref = currentHref,
                onReturn = {
                    noteReturn?.let { jump -> navigationRequest = EpubLocatorNavigationRequest(seekRequestId++, jump.locator) }
                    noteReturn = null
                },
                onLeftNote = { noteReturn = null },
            )
        }
    }
}

internal const val EpubReaderSurfaceTag = "epub-reader-surface"
internal const val EpubReaderOverlayTag = "epub-reader-overlay"
internal const val EpubReaderViewportTag = "epub-reader-viewport"

internal sealed interface EpubLabeledOption {
    val label: String
}

private sealed interface EpubInitialLocatorState {
    data object Loading : EpubInitialLocatorState

    data class Ready(
        val locator: Locator?,
    ) : EpubInitialLocatorState
}

private fun ReaderProgressSnapshot?.needsEpubProgressionLocator(
    serializedLocator: Locator?,
): Boolean = this != null &&
    serializedLocator == null &&
    percent != null

private fun buildCurrentReaderLocator(
    locator: Locator?,
    page: Int?,
    percent: Double,
): ReaderLocator = locator?.let { currentLocator ->
    buildReaderLocator(
        locator = currentLocator,
        page = page,
    )
} ?: ReaderLocator(
    locatorType = LocatorType.CFI,
    page = page,
    percent = percent.coerceIn(0.0, 1.0),
)

/** Auto: a spread in landscape where it fits ([landscapeSpread]), else one page. */
private fun defaultEpubLayoutMode(
    landscapeSpread: Boolean,
): EpubReaderLayoutMode = if (landscapeSpread) {
    EpubReaderLayoutMode.DoublePage
} else {
    EpubReaderLayoutMode.SinglePage
}
