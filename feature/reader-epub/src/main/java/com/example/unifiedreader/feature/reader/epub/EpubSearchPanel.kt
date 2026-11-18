package com.example.unifiedreader.feature.reader.epub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.feature.reader.core.ReaderBarButton
import com.example.unifiedreader.feature.reader.core.ReaderChromeDefaults
import com.example.unifiedreader.feature.reader.core.ReaderPanel
import com.example.unifiedreader.feature.reader.core.ReaderPanelSlot
import com.example.unifiedreader.feature.reader.core.readerOverlayTokens
import com.example.unifiedreader.ui.components.AppButtonSpinner
import com.example.unifiedreader.ui.components.AppDivider
import com.example.unifiedreader.ui.components.AppFloatingPanelPlacement
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppSearchField
import org.readium.r2.shared.publication.Publication

/**
 * Search in the book, an end-edge panel between the bars: the field and Close, how many matches in how
 * many chapters, the matches under their chapters (a snippet with the words in bold, and the page),
 * and at the foot where the current match is with Previous and Next. A tap on a match goes there.
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val results = search.results
    val groups = remember(results, tocEntries, publication) {
        epubSearchGroups(results) { result -> epubSearchChapterTitle(result, tocEntries, publication) }
    }
    ReaderPanel(
        visible = isOpen,
        placement = AppFloatingPanelPlacement.End,
        width = ReaderChromeDefaults.pagesWidth,
        slot = slot,
        onDismissRequest = onDismissRequest,
        testTag = EpubSearchPanelTag,
    ) {
        EpubSearchFieldRow(search = search, autoFocus = isOpen && search.query.isEmpty(), onClose = onDismissRequest)
        epubSearchSummary(search.status, results.size, groups.size)?.let { summary ->
            EpubSearchSummaryRow(summary = summary, searching = search.status == EpubSearchStatus.Searching)
        }
        EpubSearchResultList(
            isOpen = isOpen,
            search = search,
            groups = groups,
            onResultSelected = { index ->
                keyboard?.hide()
                focusManager.clearFocus()
                onResultSelected(index)
            },
            modifier = Modifier.weight(1f),
        )
        if (results.isNotEmpty()) {
            AppDivider()
            EpubSearchStepRow(
                position = epubSearchPosition(search.currentIndex, results.size),
                onStep = onStep,
                modifier = Modifier.padding(start = AppLayout.inset, end = AppLayout.headingGap, top = AppLayout.headingGap)
                    .padding(bottom = AppLayout.headingGap),
            )
        }
    }
}

/** The field, which searches as you type, and Close. */
@Composable
private fun EpubSearchFieldRow(
    search: EpubBookSearch,
    autoFocus: Boolean,
    onClose: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier.padding(start = AppLayout.menuInset, end = AppLayout.headingGap, top = AppLayout.menuInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        AppSearchField(
            query = search.query,
            onQueryChange = search::onQueryChange,
            placeholder = EpubSearchCopy.Placeholder,
            modifier = Modifier.weight(1f),
            autoFocus = autoFocus,
            onSearch = {
                search.searchNow()
                keyboard?.hide()
            },
        )
        AppIconButton(onClick = onClose) {
            Icon(
                imageVector = AppIcons.Close,
                contentDescription = EpubSearchCopy.Close,
                tint = readerOverlayTokens().colors.primaryText,
            )
        }
    }
}

/** "12 results in 3 chapters", with a spinner while the search goes on. */
@Composable
private fun EpubSearchSummaryRow(summary: String, searching: Boolean) {
    Row(
        modifier = Modifier.padding(horizontal = AppLayout.inset, vertical = AppLayout.headingGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.labelLarge,
            color = readerOverlayTokens().colors.secondaryText,
            modifier = Modifier.weight(1f),
        )
        if (searching) AppButtonSpinner()
    }
}

@Composable
private fun EpubSearchResultList(
    isOpen: Boolean,
    search: EpubBookSearch,
    groups: List<EpubSearchGroup>,
    onResultSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Back in the panel, the list opens at the current match.
    LaunchedEffect(isOpen, groups) {
        val current = search.currentIndex ?: return@LaunchedEffect
        if (!isOpen) return@LaunchedEffect
        var item = 0
        for (group in groups) {
            item++
            val inGroup = group.indices.indexOf(current)
            if (inGroup >= 0) {
                listState.scrollToItem((item + inGroup - EpubSearchRowsAboveCurrent).coerceAtLeast(0))
                return@LaunchedEffect
            }
            item += group.indices.size
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = AppLayout.headingGap),
    ) {
        groups.forEachIndexed { groupIndex, group ->
            item(key = "group-$groupIndex") { EpubSearchGroupHeading(title = group.title, count = group.indices.size) }
            items(items = group.indices, key = { index -> "result-$index" }) { index ->
                val result = search.results.getOrNull(index) ?: return@items
                EpubSearchResultRow(
                    result = result,
                    selected = index == search.currentIndex,
                    onClick = { onResultSelected(index) },
                )
            }
        }
    }
}

@Composable
private fun EpubSearchGroupHeading(title: String, count: Int) {
    val tokens = readerOverlayTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = EpubSearchRowInset, end = EpubSearchRowInset, top = AppLayout.headingGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = count.toString(), style = MaterialTheme.typography.labelMedium, color = tokens.colors.secondaryText)
    }
}

@Composable
private fun EpubSearchResultRow(
    result: EpubSearchResult,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = readerOverlayTokens()
    val textColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else tokens.colors.primaryText
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.button)
            .background(if (selected) tokens.colors.selectedContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(EpubSearchRowInset),
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        Text(
            text = epubSearchSnippetText(result.snippet),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        result.page?.let { page ->
            Text(
                text = "p. $page",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) textColor else tokens.colors.secondaryText,
                maxLines = 1,
            )
        }
    }
}

/** "3 of 12" with Previous and Next. */
@Composable
private fun EpubSearchStepRow(
    position: String,
    onStep: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = position,
            style = MaterialTheme.typography.labelLarge,
            color = readerOverlayTokens().colors.secondaryText,
            modifier = Modifier.weight(1f),
        )
        ReaderBarButton(icon = AppIcons.NavigateBefore, contentDescription = EpubSearchCopy.Previous, onClick = { onStep(false) })
        ReaderBarButton(icon = AppIcons.NavigateNext, contentDescription = EpubSearchCopy.Next, onClick = { onStep(true) })
    }
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
    val tokens = readerOverlayTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayout.headingGap)
            .testTag(EpubSearchStripTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(tokens.shapes.button)
                .clickable(onClick = onOpenResults)
                .padding(EpubSearchRowInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        ) {
            Icon(
                imageVector = AppIcons.Search,
                contentDescription = null,
                tint = tokens.colors.secondaryText,
                modifier = Modifier.size(EpubSearchStripIconSize),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "“${search.query.trim()}”",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = epubSearchPosition(search.currentIndex, search.results.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.secondaryText,
                    maxLines = 1,
                )
            }
        }
        ReaderBarButton(icon = AppIcons.NavigateBefore, contentDescription = EpubSearchCopy.Previous, onClick = { onStep(false) })
        ReaderBarButton(icon = AppIcons.NavigateNext, contentDescription = EpubSearchCopy.Next, onClick = { onStep(true) })
        ReaderBarButton(icon = AppIcons.Close, contentDescription = EpubSearchCopy.End, onClick = onEnd)
    }
}

/** The snippet with the match in bold on the amber the page tints it with. */
@Composable
private fun epubSearchSnippetText(snippet: EpubSearchSnippet): AnnotatedString {
    val matchBackground = Color(EpubSearchTint).copy(alpha = EpubSnippetMatchAlpha)
    return remember(snippet, matchBackground) {
        buildAnnotatedString {
            append(snippet.before)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = matchBackground)) { append(snippet.match) }
            append(snippet.after)
        }
    }
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
    const val Placeholder = "Search this book"
    const val Close = "Close search"
    const val Previous = "Previous result"
    const val Next = "Next result"
    const val End = "End search"
    const val Book = "In the book"
}

private val EpubSearchRowInset = 10.dp
private val EpubSearchStripIconSize = 20.dp
private const val EpubSnippetMatchAlpha = 0.35f

/** Matches kept above the current one when the list opens, so it reads in context. */
private const val EpubSearchRowsAboveCurrent = 1

internal const val EpubSearchPanelTag = "epub-search-panel"
internal const val EpubSearchStripTag = "epub-search-strip"
