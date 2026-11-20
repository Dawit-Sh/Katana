package com.example.unifiedreader.feature.reader.pdf

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unifiedreader.feature.annotations.BookMarksExport
import com.example.unifiedreader.feature.annotations.BookMarksPanelCopy
import com.example.unifiedreader.feature.annotations.PageBookmarkCopy
import com.example.unifiedreader.feature.annotations.PageBookmarks
import com.example.unifiedreader.feature.annotations.PageMarksPanelBody
import com.example.unifiedreader.feature.annotations.ReaderHighlight
import com.example.unifiedreader.feature.annotations.pageTitle
import com.example.unifiedreader.feature.reader.core.ReaderBarButton
import com.example.unifiedreader.feature.reader.core.ReaderChromeDefaults
import com.example.unifiedreader.feature.reader.core.ReaderChromeFrame
import com.example.unifiedreader.feature.reader.core.ReaderDock
import com.example.unifiedreader.feature.reader.core.ReaderDockProgress
import com.example.unifiedreader.feature.reader.core.ReaderPanel
import com.example.unifiedreader.feature.reader.core.ReaderPanelSlot
import com.example.unifiedreader.feature.reader.core.ReaderSearchDockStrip
import com.example.unifiedreader.feature.reader.core.ReaderSearchPanel
import com.example.unifiedreader.feature.reader.core.ReaderSearchParts
import com.example.unifiedreader.feature.reader.core.readerSearchGroups
import com.example.unifiedreader.feature.reader.core.ReaderDocumentState
import com.example.unifiedreader.feature.reader.core.ReaderTopBar
import com.example.unifiedreader.feature.reader.core.readerOverlayTokens
import com.example.unifiedreader.ui.components.AppCircularProgress
import com.example.unifiedreader.ui.components.AppFloatingPanelPlacement
import com.example.unifiedreader.ui.components.AppIcons
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The PDF reader on the shared frame: the title over the author with previous and next and the reading
 * settings at the top; the page strip over the percentage, the slider and the page in the dock. Its one
 * panel is the settings card (Pages, page number), under its button.
 */
@Composable
internal fun PdfReaderChrome(
    title: String,
    author: String?,
    pageRenderer: PageImageRenderer,
    documentState: ReaderDocumentState,
    pageCount: Int,
    currentPageIndex: Int,
    progress: Float,
    canNavigatePrevious: Boolean,
    canNavigateNext: Boolean,
    settings: PdfReaderSettings,
    overlayVisible: Boolean,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPageSelected: (Int) -> Unit,
    // Bookmarks the page on screen, or takes its bookmark off; null when the book can't keep them.
    bookmark: PdfBookmarkButton? = null,
    // The book's marks panel (bookmarks, highlights, export); null when the book can't keep marks.
    marksPanel: PdfMarksPanel? = null,
    // Search in the book; null for a book without text (DjVu).
    search: PdfSearchControls? = null,
) {
    var openPanel by rememberSaveable { mutableStateOf<PdfChromePanel?>(null) }
    LaunchedEffect(overlayVisible) {
        if (!overlayVisible) openPanel = null
    }
    fun toggle(panel: PdfChromePanel) {
        openPanel = if (openPanel == panel) null else panel
    }
    ReaderChromeFrame(
        panelOpen = openPanel != null,
        onDismissPanel = { openPanel = null },
        topBar = {
            ReaderTopBar(title = title, subtitle = author, onBack = onBack) {
                PdfTopBarButtons(
                    canNavigatePrevious = canNavigatePrevious,
                    canNavigateNext = canNavigateNext,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    openPanel = openPanel,
                    onToggle = ::toggle,
                    bookmark = bookmark,
                    hasMarks = marksPanel != null,
                    hasSearch = search != null,
                )
            }
        },
        dock = {
            PdfReaderDock(
                pageRenderer = pageRenderer,
                documentState = documentState,
                pageCount = pageCount,
                currentPageIndex = currentPageIndex,
                progress = progress,
                onPageSelected = onPageSelected,
                search = search?.takeIf { openPanel != PdfChromePanel.Search },
                onOpenSearch = { openPanel = PdfChromePanel.Search },
            )
        },
    ) { slot ->
        PdfReaderSettingsPanel(
            isOpen = openPanel == PdfChromePanel.Settings,
            slot = slot,
            settings = settings,
            onDismissRequest = { openPanel = null },
        )
        marksPanel?.let { panel ->
            PdfMarksPanelSheet(panel, openPanel == PdfChromePanel.Marks, slot, currentPageIndex, pageCount) { openPanel = null }
        }
        search?.let { controls ->
            PdfSearchPanel(controls, openPanel == PdfChromePanel.Search, slot, currentPageIndex) { openPanel = null }
        }
    }
}

/** The dock: the page strip over the percentage, the slider and the page. */
@Composable
private fun PdfReaderDock(
    pageRenderer: PageImageRenderer,
    documentState: ReaderDocumentState,
    pageCount: Int,
    currentPageIndex: Int,
    progress: Float,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // A search: while it has matches, they sit over the dock instead of the page strip.
    search: PdfSearchControls? = null,
    onOpenSearch: () -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val thumbnailHeight = ReaderChromeDefaults.stripThumbnailHeight(
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
    )
    ReaderDock(
        modifier = modifier,
        strip = if (search != null && search.search.isActive) {
            {
                ReaderSearchDockStrip(
                    search = search.search,
                    onOpenResults = onOpenSearch,
                    onStep = { forward -> search.step(forward, currentPageIndex) },
                    onEnd = search.search::clear,
                )
            }
        } else if (pageCount > 0) {
            {
                PdfPagePreviewStrip(
                    pageRenderer = pageRenderer,
                    documentState = documentState,
                    pageCount = pageCount,
                    currentPageIndex = currentPageIndex,
                    thumbnailHeight = thumbnailHeight,
                    onPageSelected = onPageSelected,
                )
            }
        } else {
            null
        },
    ) {
        ReaderDockProgress(
            progress = progress,
            percentLabel = "${(progress.coerceIn(0f, 1f) * 100f).roundToInt()}%",
            positionLabel = if (pageCount > 0) "${currentPageIndex + 1} / $pageCount" else "",
            onProgressSelected = { selected ->
                if (pageCount > 0) {
                    onPageSelected((selected * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1))
                }
            },
        )
    }
}

@Composable
private fun PdfPagePreviewStrip(
    pageRenderer: PageImageRenderer,
    documentState: ReaderDocumentState,
    pageCount: Int,
    currentPageIndex: Int,
    thumbnailHeight: Dp,
    onPageSelected: (Int) -> Unit,
) {
    if (pageCount <= 0) return
    val documentUri = (documentState as? ReaderDocumentState.Ready)?.uri ?: return
    val listState = rememberLazyListState()
    val safeCurrentPage = currentPageIndex.coerceIn(0, pageCount - 1)
    val pages = remember(pageCount) { List(pageCount) { it } }
    val previewCache = remember(documentState.cacheKey) {
        PdfPreviewBitmapCache(maxEntries = PDF_PREVIEW_CACHE_MAX_ENTRIES)
    }

    DisposableEffect(previewCache) {
        onDispose {
            previewCache.clear()
        }
    }

    LaunchedEffect(documentState.cacheKey, safeCurrentPage, pageCount) {
        listState.scrollToItem((safeCurrentPage - 2).coerceAtLeast(0))
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PdfPagePreviewStripTag),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items(
            items = pages,
            key = { pageIndex -> "pdf-page-$pageIndex" },
        ) { pageIndex ->
            PdfPagePreviewItem(
                pageRenderer = pageRenderer,
                documentUri = documentUri,
                previewCache = previewCache,
                pageIndex = pageIndex,
                selected = pageIndex == safeCurrentPage,
                thumbnailHeight = thumbnailHeight,
                onClick = { onPageSelected(pageIndex) },
            )
        }
    }
}

@Composable
private fun PdfPagePreviewItem(
    pageRenderer: PageImageRenderer,
    documentUri: Uri,
    previewCache: PdfPreviewBitmapCache,
    pageIndex: Int,
    selected: Boolean,
    thumbnailHeight: Dp,
    onClick: () -> Unit,
) {
    val tokens = readerOverlayTokens()
    val previewState by produceState<PdfPageRenderState>(
        initialValue = PdfPageRenderState.Loading,
        key1 = documentUri.toString(),
        key2 = pageIndex,
    ) {
        value = withContext(Dispatchers.IO) {
            previewCache.get(pageIndex)
                ?: pageRenderer(documentUri, pageIndex, PDF_PREVIEW_RENDER_LONG_EDGE_PX).also { renderedState ->
                    if (renderedState is PdfPageRenderState.Ready) {
                        previewCache.put(pageIndex, renderedState)
                    }
                }
        }
    }
    val itemWidth = thumbnailHeight * PdfPreviewWidthRatio
    Column(
        modifier = Modifier.width(itemWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(itemWidth)
                .height(thumbnailHeight)
                .clip(tokens.shapes.previewItem)
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = if (selected) tokens.colors.accent else Color.Transparent,
                    shape = tokens.shapes.previewItem,
                )
                .padding(if (selected) 2.dp else 0.dp)
                .background(tokens.colors.fallbackPage, tokens.shapes.previewItem)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = previewState) {
                PdfPageRenderState.Empty -> PdfPreviewFallback(pageIndex = pageIndex)
                PdfPageRenderState.Loading -> AppCircularProgress(
                    color = tokens.colors.accent,
                )
                is PdfPageRenderState.Error -> PdfPreviewFallback(pageIndex = pageIndex)
                is PdfPageRenderState.Ready -> {
                    Image(
                        bitmap = state.bitmap.asImageBitmap(),
                        contentDescription = state.contentDescription,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            text = "${pageIndex + 1}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontFeatureSettings = "tnum"),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) tokens.colors.primaryText else tokens.colors.secondaryText,
            maxLines = 1,
        )
    }
}

@Composable
private fun PdfPreviewFallback(pageIndex: Int) {
    val tokens = readerOverlayTokens()
    Text(
        text = "${pageIndex + 1}",
        style = MaterialTheme.typography.labelLarge,
        color = tokens.colors.tertiaryText,
        textAlign = TextAlign.Center,
    )
}

// A thumbnail's width against its height: about a page's shape.
private const val PdfPreviewWidthRatio = 0.73f
private const val PdfPagePreviewStripTag = "pdf-page-preview-strip"
private const val PDF_PREVIEW_CACHE_MAX_ENTRIES = 64
private const val PDF_PREVIEW_RENDER_LONG_EDGE_PX = 420

/** The top bar's buttons: previous and next, search, the bookmark and the marks (each when the book has them), settings. */
@Composable
private fun PdfTopBarButtons(
    canNavigatePrevious: Boolean,
    canNavigateNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    openPanel: PdfChromePanel?,
    onToggle: (PdfChromePanel) -> Unit,
    bookmark: PdfBookmarkButton?,
    hasMarks: Boolean,
    hasSearch: Boolean,
) {
    ReaderBarButton(
        icon = AppIcons.NavigateBefore,
        contentDescription = "Previous page",
        onClick = onPrevious,
        enabled = canNavigatePrevious,
    )
    ReaderBarButton(icon = AppIcons.NavigateNext, contentDescription = "Next page", onClick = onNext, enabled = canNavigateNext)
    if (hasSearch) {
        ReaderBarButton(
            icon = AppIcons.Search,
            contentDescription = PdfSearchCopy.Button,
            active = openPanel == PdfChromePanel.Search,
            onClick = { onToggle(PdfChromePanel.Search) },
        )
    }
    PdfMarkButtons(
        bookmark = bookmark,
        marksOpen = openPanel == PdfChromePanel.Marks,
        onMarksClick = if (hasMarks) ({ onToggle(PdfChromePanel.Marks) }) else null,
    )
    ReaderBarButton(
        icon = AppIcons.Tune,
        contentDescription = "Reading settings",
        active = openPanel == PdfChromePanel.Settings,
        onClick = { onToggle(PdfChromePanel.Settings) },
    )
}

/** The bookmark button and the marks panel's, each when the book can keep marks. */
@Composable
private fun PdfMarkButtons(
    bookmark: PdfBookmarkButton?,
    marksOpen: Boolean,
    onMarksClick: (() -> Unit)?,
) {
    bookmark?.let { button ->
        ReaderBarButton(
            icon = if (button.isPageBookmarked) AppIcons.BookmarkFilled else AppIcons.Bookmark,
            contentDescription = if (button.isPageBookmarked) PageBookmarkCopy.RemoveBookmark else PageBookmarkCopy.Bookmark,
            active = button.isPageBookmarked,
            onClick = button.onClick,
        )
    }
    onMarksClick?.let { onClick ->
        ReaderBarButton(
            icon = AppIcons.CollectionsBookmark,
            contentDescription = BookMarksPanelCopy.PanelButton,
            active = marksOpen,
            onClick = onClick,
        )
    }
}

/** The reader's panels; one is open at a time. */
internal enum class PdfChromePanel { Settings, Marks, Search }

/** Search in the book, and going to a match's page. */
internal class PdfSearchControls(
    val search: PdfBookSearch,
    val onJumpToPage: (Int) -> Unit,
) {
    fun select(index: Int) {
        search.select(index)?.let(onJumpToPage)
    }

    fun step(forward: Boolean, fromPage: Int) {
        search.step(forward, fromPage)?.let(onJumpToPage)
    }
}

internal object PdfSearchCopy {
    const val Button = "Search in the book"
}

/** The search panel at the end edge, as the EPUB reader's: matches under their pages. */
@Composable
private fun PdfSearchPanel(
    controls: PdfSearchControls,
    isOpen: Boolean,
    slot: ReaderPanelSlot,
    pageIndex: Int,
    onDismissRequest: () -> Unit,
) {
    val results = controls.search.results
    val groups = remember(results) { readerSearchGroups(results) { result -> pageTitle(result.pageIndex) } }
    ReaderSearchPanel(
        isOpen = isOpen,
        slot = slot,
        search = controls.search,
        groups = groups,
        onDismissRequest = onDismissRequest,
        onResultSelected = { index ->
            controls.select(index)
            onDismissRequest()
        },
        onStep = { forward -> controls.step(forward, pageIndex) },
        parts = ReaderSearchParts.Pages,
    )
}

/** The marks panel's contents: the page bookmarks, the highlights (none in a DjVu book), the export, and going to a page. */
internal class PdfMarksPanel(
    val bookmarks: PageBookmarks,
    val export: BookMarksExport,
    val highlights: List<ReaderHighlight>,
    val onJumpToPage: (Int) -> Unit,
)

/** The marks panel at the end edge, as the EPUB reader's: open at the marks around page [pageIndex]. */
@Composable
private fun PdfMarksPanelSheet(
    panel: PdfMarksPanel,
    isOpen: Boolean,
    slot: ReaderPanelSlot,
    pageIndex: Int,
    pageCount: Int,
    onDismissRequest: () -> Unit,
) {
    ReaderPanel(
        visible = isOpen,
        placement = AppFloatingPanelPlacement.End,
        width = ReaderChromeDefaults.contentsWidth,
        slot = slot,
        onDismissRequest = onDismissRequest,
    ) {
        PageMarksPanelBody(
            bookmarks = panel.bookmarks,
            export = panel.export,
            highlights = panel.highlights,
            emptyText = BookMarksPanelCopy.EmptyPages,
            pageIndex = pageIndex,
            pageCount = pageCount,
            onJumpToPage = panel.onJumpToPage,
            onDismissRequest = onDismissRequest,
        )
    }
}

/** The top bar's bookmark button: filled while the page on screen is bookmarked. */
internal class PdfBookmarkButton(
    val isPageBookmarked: Boolean,
    val onClick: () -> Unit,
)
