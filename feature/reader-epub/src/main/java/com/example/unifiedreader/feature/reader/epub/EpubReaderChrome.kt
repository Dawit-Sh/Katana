package com.example.unifiedreader.feature.reader.epub

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.unifiedreader.feature.reader.core.ReaderBarButton
import com.example.unifiedreader.feature.reader.core.ReaderChromeFrame
import com.example.unifiedreader.feature.reader.core.ReaderDock
import com.example.unifiedreader.feature.reader.core.ReaderDockProgress
import com.example.unifiedreader.feature.reader.core.ReaderPanelSlot
import com.example.unifiedreader.feature.reader.core.ReaderTopBar
import com.example.unifiedreader.ui.components.AppIcons

/**
 * The EPUB reader's bars and panels: the title over the chapter and author, then Search, the
 * page's bookmark (filled while the page has one), the marks panel and Aa at the top end; the
 * contents button, the percentage, the slider with its chapter ticks and the page in the dock,
 * with [dockStrip] (a search's matches) over them. [panels] are the contents, search and settings
 * panels, placed between the bars.
 */
@Composable
internal fun EpubReaderChrome(
    title: String,
    author: String?,
    tocEntries: List<EpubTocEntry>,
    currentHref: String?,
    currentProgress: Double,
    currentPage: Int,
    pageCount: Int,
    chapterMarks: List<Float>,
    isTocOpen: Boolean,
    isPreferencesOpen: Boolean,
    onBack: () -> Unit,
    onTocClick: () -> Unit,
    onPreferencesClick: () -> Unit,
    onDismissPanels: () -> Unit,
    onProgressSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isAnnotationsOpen: Boolean = false,
    // The highlights and saved words panel; null when the book can't keep highlights.
    onAnnotationsClick: (() -> Unit)? = null,
    isPageBookmarked: Boolean = false,
    // Bookmarks the page on screen, or removes its bookmark; null when the book can't keep them.
    onBookmarkClick: (() -> Unit)? = null,
    isSearchOpen: Boolean = false,
    onSearchClick: (() -> Unit)? = null,
    dockStrip: (@Composable () -> Unit)? = null,
    panels: @Composable BoxScope.(ReaderPanelSlot) -> Unit,
) {
    val chapterTitle = remember(tocEntries, currentHref, currentProgress) {
        tocEntries.currentEpubChapterTitle(currentProgress = currentProgress, currentHref = currentHref)
    }
    ReaderChromeFrame(
        panelOpen = isTocOpen || isPreferencesOpen || isAnnotationsOpen || isSearchOpen,
        onDismissPanel = onDismissPanels,
        modifier = modifier,
        topBar = {
            ReaderTopBar(
                title = title,
                subtitle = epubReaderSubtitle(chapterTitle = chapterTitle, author = author, bookTitle = title),
                onBack = onBack,
            ) {
                onSearchClick?.let { onClick ->
                    ReaderBarButton(
                        icon = AppIcons.Search,
                        contentDescription = EpubSearchCopy.Button,
                        active = isSearchOpen,
                        onClick = onClick,
                    )
                }
                onBookmarkClick?.let { onClick ->
                    ReaderBarButton(
                        icon = if (isPageBookmarked) AppIcons.BookmarkFilled else AppIcons.Bookmark,
                        contentDescription = if (isPageBookmarked) EpubAnnotationCopy.RemoveBookmark else EpubAnnotationCopy.AddBookmark,
                        active = isPageBookmarked,
                        onClick = onClick,
                    )
                }
                onAnnotationsClick?.let { onClick ->
                    ReaderBarButton(
                        icon = AppIcons.CollectionsBookmark,
                        contentDescription = EpubAnnotationCopy.PanelButton,
                        active = isAnnotationsOpen,
                        onClick = onClick,
                    )
                }
                ReaderBarButton(
                    icon = AppIcons.FormatSize,
                    contentDescription = "Reader preferences",
                    active = isPreferencesOpen,
                    onClick = onPreferencesClick,
                )
            }
        },
        dock = {
            ReaderDock(strip = dockStrip) {
                ReaderBarButton(
                    icon = AppIcons.Toc,
                    contentDescription = "Table of contents",
                    active = isTocOpen,
                    onClick = onTocClick,
                )
                val dockProgress = epubDockProgress(currentProgress, position = currentPage, pageCount = pageCount)
                ReaderDockProgress(
                    progress = dockProgress.toFloat(),
                    percentLabel = buildEpubPercentLabel(dockProgress),
                    positionLabel = buildEpubDockPositionLabel(position = currentPage, pageCount = pageCount),
                    chapterMarks = chapterMarks,
                    onProgressSelected = onProgressSelected,
                )
            }
        },
        panels = panels,
    )
}

/** The top bar's second line: where you are, "Глава 3 · Анна Лесная", never the page. */
internal fun epubReaderSubtitle(
    chapterTitle: String?,
    author: String?,
    bookTitle: String? = null,
): String? = listOfNotNull(
    // A chapter named like the book (a one-chapter book) would read as the title twice.
    chapterTitle?.trim()?.takeIf { it.isNotEmpty() && !it.equals(bookTitle?.trim(), ignoreCase = true) },
    author?.trim()?.takeIf(String::isNotEmpty),
).joinToString(" · ").ifEmpty { null }

/** The panel open between the bars; one at a time. */
internal enum class EpubReaderPanel {
    Contents,
    Settings,
    Annotations,
    Search,
    Light,
}
