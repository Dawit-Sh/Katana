package com.example.unifiedreader.feature.reader.comics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.annotations.PageBookmarkCopy
import com.example.unifiedreader.feature.reader.core.ReaderBarButton
import com.example.unifiedreader.feature.reader.core.ReaderChromeDefaults
import com.example.unifiedreader.feature.reader.core.ReaderChromeFrame
import com.example.unifiedreader.feature.reader.core.ReaderDock
import com.example.unifiedreader.feature.reader.core.ReaderDockProgress
import com.example.unifiedreader.feature.reader.core.ReaderTopBar
import com.example.unifiedreader.feature.reader.core.ReaderWorkCoordinator
import com.example.unifiedreader.ui.components.AppIcons

/** The comics reader's panels; one is open at a time. */
internal enum class ComicsReaderPanel { None, Pages, Settings }

/** The issue's pages and how the reader sees them now, for the bars and panels. */
internal class ComicsChromePages(
    val pages: List<ComicPageSpec>,
    val currentPageIndexes: Set<Int>,
    val progress: Float,
    /** The spreads the page strip groups pages into, which in scroll mode is always one page. */
    val stripSpreadMode: ComicSpreadMode,
    val spreadPlanState: ComicSpreadPlanState,
)

/** Where the bars and panels get pages from. */
internal class ComicsChromeSources(
    val pageSource: ComicPageSource,
    val readerWorkCoordinator: ReaderWorkCoordinator?,
    val stripLoadingEnabled: Boolean,
)

/**
 * The comics reader's bars and panels: the issue over where it sits and the settings button at
 * the top; in the dock the page strip over the Pages button, the percentage, the slider and the
 * page. Pages (every page as a grid) opens at the start edge, the settings as a card under their
 * button, both between the bars.
 */
@Composable
internal fun ComicsReaderChrome(
    item: ReadableItem,
    chromePages: ComicsChromePages,
    sources: ComicsChromeSources,
    readerState: ComicsReaderState,
    pageNumbersVisible: Boolean,
    overlayVisible: Boolean,
    onBack: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onProgressSelected: (Float) -> Unit,
    onPageNumbersVisibleChanged: (Boolean) -> Unit,
    // Bookmarks the page on screen, or takes its bookmark off; null when the book can't keep them.
    bookmark: ComicBookmarkButton? = null,
) {
    val titles = remember(item) { comicReaderTitles(item) }
    var openPanel by rememberSaveable(item.id) { mutableStateOf(ComicsReaderPanel.None) }
    LaunchedEffect(overlayVisible) {
        if (!overlayVisible) openPanel = ComicsReaderPanel.None
    }
    fun toggle(panel: ComicsReaderPanel) {
        openPanel = if (openPanel == panel) ComicsReaderPanel.None else panel
    }
    ReaderChromeFrame(
        panelOpen = openPanel != ComicsReaderPanel.None,
        onDismissPanel = { openPanel = ComicsReaderPanel.None },
        topBar = {
            ReaderTopBar(title = titles.title, subtitle = titles.subtitle, onBack = onBack) {
                ComicsTopBarButtons(
                    bookmark = bookmark,
                    settingsOpen = openPanel == ComicsReaderPanel.Settings,
                    onSettingsClick = { toggle(ComicsReaderPanel.Settings) },
                )
            }
        },
        dock = {
            ComicsReaderDock(
                item = item,
                chromePages = chromePages,
                sources = sources,
                readerState = readerState,
                pagesPanelOpen = openPanel == ComicsReaderPanel.Pages,
                onPagesClick = { toggle(ComicsReaderPanel.Pages) },
                onPageSelected = onPageSelected,
                onProgressSelected = onProgressSelected,
            )
        },
    ) { slot ->
        ComicPagesPanel(
            item = item,
            issueLabel = titles.title,
            pages = chromePages.pages,
            currentPageIndexes = chromePages.currentPageIndexes,
            progress = chromePages.progress,
            pageSource = sources.pageSource,
            readerWorkCoordinator = sources.readerWorkCoordinator,
            // Opened on purpose, so its thumbnails load straight away.
            previewLoadingEnabled = true,
            isOpen = openPanel == ComicsReaderPanel.Pages,
            slot = slot,
            onDismissRequest = { openPanel = ComicsReaderPanel.None },
            onPageSelected = { page ->
                openPanel = ComicsReaderPanel.None
                onPageSelected(page)
            },
        )
        ComicsReaderPreferencesSheet(
            isOpen = openPanel == ComicsReaderPanel.Settings,
            slot = slot,
            readerState = readerState,
            pageNumbersVisible = pageNumbersVisible,
            onDismissRequest = { openPanel = ComicsReaderPanel.None },
            onPageNumbersVisibleChanged = onPageNumbersVisibleChanged,
        )
    }
}

/** The dock: the page strip over the Pages button, the percentage, the slider and the page. */
@Composable
private fun ComicsReaderDock(
    item: ReadableItem,
    chromePages: ComicsChromePages,
    sources: ComicsChromeSources,
    readerState: ComicsReaderState,
    pagesPanelOpen: Boolean,
    onPagesClick: () -> Unit,
    onPageSelected: (Int) -> Unit,
    onProgressSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val thumbnailHeight = ReaderChromeDefaults.stripThumbnailHeight(
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
    )
    ReaderDock(
        modifier = modifier,
        strip = {
            ComicPagePreviewStrip(
                item = item,
                pages = chromePages.pages,
                currentPage = readerState.currentPage,
                readingDirection = readerState.readingDirection,
                spreadMode = chromePages.stripSpreadMode,
                spreadPlanState = chromePages.spreadPlanState,
                pageSource = sources.pageSource,
                thumbnailHeight = thumbnailHeight,
                readerWorkCoordinator = sources.readerWorkCoordinator,
                previewLoadingEnabled = sources.stripLoadingEnabled,
                onPageSelected = onPageSelected,
            )
        },
    ) {
        ReaderBarButton(
            icon = AppIcons.GridView,
            contentDescription = "Pages",
            active = pagesPanelOpen,
            onClick = onPagesClick,
        )
        ReaderDockProgress(
            progress = chromePages.progress,
            percentLabel = comicPercentLabel(chromePages.progress),
            positionLabel = comicDockPositionLabel(chromePages.currentPageIndexes, chromePages.pages.size),
            readingDirection = comicProgressBarDirection(
                readerMode = readerState.readerMode,
                readingDirection = readerState.readingDirection,
                scrollAxis = readerState.scrollAxis,
            ),
            onProgressSelected = onProgressSelected,
        )
    }
}

/** The top bar's bookmark button: filled while the page on screen is bookmarked. */
internal class ComicBookmarkButton(
    val isPageBookmarked: Boolean,
    val onClick: () -> Unit,
)

/** The top bar's buttons: bookmark (when the book keeps bookmarks) and the reading settings. */
@Composable
private fun ComicsTopBarButtons(
    bookmark: ComicBookmarkButton?,
    settingsOpen: Boolean,
    onSettingsClick: () -> Unit,
) {
    bookmark?.let { button ->
        ReaderBarButton(
            icon = if (button.isPageBookmarked) AppIcons.BookmarkFilled else AppIcons.Bookmark,
            contentDescription = if (button.isPageBookmarked) PageBookmarkCopy.RemoveBookmark else PageBookmarkCopy.Bookmark,
            active = button.isPageBookmarked,
            onClick = button.onClick,
        )
    }
    ReaderBarButton(
        icon = AppIcons.Tune,
        contentDescription = "Reading settings",
        active = settingsOpen,
        onClick = onSettingsClick,
    )
}
