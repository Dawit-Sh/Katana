package com.example.unifiedreader.feature.reader.epub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.unifiedreader.feature.reader.core.ReaderPanelSlot
import com.example.unifiedreader.feature.reader.core.ReaderSearchCopy
import com.example.unifiedreader.feature.reader.core.ReaderSearchDockStrip
import com.example.unifiedreader.feature.reader.core.ReaderSearchPanel
import org.readium.r2.shared.publication.Publication

/**
 * Search in the book, an end-edge panel between the bars, as every reader's ([ReaderSearchPanel]): the field and
 * Close, how many matches in how many chapters, the matches under their chapters (a snippet with the words in bold,
 * and the page), and at the foot where the current match is with Previous and Next. A tap on a match goes there.
 */
@Composable
internal fun EpubSearchPanel(
    isOpen: Boolean,
    slot: ReaderPanelSlot,
    search: EpubBookSearch,
    publication: Publication,
    tocEntries: List<EpubTocEntry>,
    onDismissRequest: () -> Unit,
    onResultSelected: (Int) -> Unit,
    onStep: (forward: Boolean) -> Unit,
) {
    val results = search.results
    val groups = remember(results, tocEntries, publication) {
        epubSearchGroups(results) { result -> epubSearchChapterTitle(result, tocEntries, publication) }
    }
    ReaderSearchPanel(
        isOpen = isOpen,
        slot = slot,
        search = search,
        groups = groups,
        onDismissRequest = onDismissRequest,
        onResultSelected = onResultSelected,
        onStep = onStep,
        testTag = EpubSearchPanelTag,
    )
}

/**
 * Over the dock row while a search has matches and its panel is closed: the words searched for (a tap
 * opens the list again), where the current match is, Previous, Next and End search.
 */
@Composable
internal fun EpubSearchDockStrip(
    search: EpubBookSearch,
    onOpenResults: () -> Unit,
    onStep: (forward: Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    ReaderSearchDockStrip(search = search, onOpenResults = onOpenResults, onStep = onStep, onEnd = onEnd, testTag = EpubSearchStripTag)
}

/** The chapter a match is in, as the contents name it; the book's part (its file) when they don't. */
internal fun epubSearchChapterTitle(
    result: EpubSearchResult,
    tocEntries: List<EpubTocEntry>,
    publication: Publication,
): String = tocEntries.currentEpubChapterTitle(
    currentProgress = result.progression ?: 0.0,
    currentHref = result.locator.href.toString(),
) ?: result.locator.title?.trim()?.takeIf(String::isNotEmpty)
    ?: publication.readingOrderIndexOf(result.locator)?.let { index -> "Part ${index + 1}" }
    ?: EpubSearchCopy.Book

internal object EpubSearchCopy {
    const val Button = "Search in the book"
    const val Placeholder = ReaderSearchCopy.Placeholder
    const val Close = ReaderSearchCopy.Close
    const val Previous = ReaderSearchCopy.Previous
    const val Next = ReaderSearchCopy.Next
    const val End = ReaderSearchCopy.End
    const val Book = "In the book"
}

internal const val EpubSearchPanelTag = "epub-search-panel"
internal const val EpubSearchStripTag = "epub-search-strip"
