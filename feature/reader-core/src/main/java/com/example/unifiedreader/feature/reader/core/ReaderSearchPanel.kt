package com.example.unifiedreader.feature.reader.core

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
import com.example.unifiedreader.ui.components.AppButtonSpinner
import com.example.unifiedreader.ui.components.AppDivider
import com.example.unifiedreader.ui.components.AppFloatingPanelPlacement
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppSearchField

/** A search in a book as its panel and dock strip show it; each reader searches its own way. */
interface ReaderBookSearch {
    val query: String
    val status: ReaderSearchStatus
    val currentIndex: Int?
    val resultCount: Int

    /** The match at [index]: its snippet, and its page (null when the book has no pages to count). */
    fun row(index: Int): ReaderSearchRow?

    fun onQueryChange(newQuery: String)

    /** The keyboard's search key: no waiting for the pause. */
    fun searchNow()
}

/** One line of the results. */
class ReaderSearchRow(val snippet: ReaderSearchSnippet, val page: Int?)

/** What the search panel and strip say. */
object ReaderSearchCopy {
    const val Placeholder = "Search this book"
    const val Close = "Close search"
    const val Previous = "Previous result"
    const val Next = "Next result"
    const val End = "End search"
}

/** The amber a page tints its matches with, and the snippets their words. */
val ReaderSearchTint: Color = Color(red = 232, green = 163, blue = 61)

/**
 * Search in the book, an end-edge panel between the bars: the field and Close, how many matches in how many
 * chapters (or pages), the matches under their chapters (a snippet with the words in bold, and the page), and at the
 * foot where the current match is with Previous and Next. A tap on a match goes there.
 */
@Composable
fun ReaderSearchPanel(
    isOpen: Boolean,
    slot: ReaderPanelSlot,
    search: ReaderBookSearch,
    groups: List<ReaderSearchGroup>,
    onDismissRequest: () -> Unit,
    onResultSelected: (Int) -> Unit,
    onStep: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    parts: ReaderSearchParts = ReaderSearchParts.Chapters,
    testTag: String? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    ReaderPanel(
        visible = isOpen,
        placement = AppFloatingPanelPlacement.End,
        width = ReaderChromeDefaults.pagesWidth,
        slot = slot,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        testTag = testTag,
    ) {
        SearchFieldRow(search = search, autoFocus = isOpen && search.query.isEmpty(), onClose = onDismissRequest)
        readerSearchSummary(search.status, search.resultCount, groups.size, parts)?.let { summary ->
            SearchSummaryRow(summary = summary, searching = search.status == ReaderSearchStatus.Searching)
        }
        SearchResultList(
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
        if (search.resultCount > 0) {
            AppDivider()
            SearchStepRow(
                position = readerSearchPosition(search.currentIndex, search.resultCount),
                onStep = onStep,
                modifier = Modifier.padding(start = AppLayout.inset, end = AppLayout.headingGap, top = AppLayout.headingGap)
                    .padding(bottom = AppLayout.headingGap),
            )
        }
    }
}

/** The field, which searches as you type, and Close. */
@Composable
private fun SearchFieldRow(
    search: ReaderBookSearch,
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
            placeholder = ReaderSearchCopy.Placeholder,
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
                contentDescription = ReaderSearchCopy.Close,
                tint = readerOverlayTokens().colors.primaryText,
            )
        }
    }
}

/** "12 results in 3 chapters", with a spinner while the search goes on. */
@Composable
private fun SearchSummaryRow(summary: String, searching: Boolean) {
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
private fun SearchResultList(
    isOpen: Boolean,
    search: ReaderBookSearch,
    groups: List<ReaderSearchGroup>,
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
                listState.scrollToItem((item + inGroup - RowsAboveCurrent).coerceAtLeast(0))
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
            item(key = "group-$groupIndex") { SearchGroupHeading(title = group.title, count = group.indices.size) }
            items(items = group.indices, key = { index -> "result-$index" }) { index ->
                val result = search.row(index) ?: return@items
                SearchResultRow(
                    result = result,
                    selected = index == search.currentIndex,
                    onClick = { onResultSelected(index) },
                )
            }
        }
    }
}

@Composable
private fun SearchGroupHeading(title: String, count: Int) {
    val tokens = readerOverlayTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SearchRowInset, end = SearchRowInset, top = AppLayout.headingGap),
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
private fun SearchResultRow(
    result: ReaderSearchRow,
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
            .padding(SearchRowInset),
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        Text(
            text = searchSnippetText(result.snippet),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            maxLines = SnippetLines,
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
private fun SearchStepRow(
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
        ReaderBarButton(icon = AppIcons.NavigateBefore, contentDescription = ReaderSearchCopy.Previous, onClick = { onStep(false) })
        ReaderBarButton(icon = AppIcons.NavigateNext, contentDescription = ReaderSearchCopy.Next, onClick = { onStep(true) })
    }
}

/**
 * Over the dock row while a search has matches and its panel is closed: the words searched for (a tap
 * opens the list again), where the current match is, Previous, Next and End search.
 */
@Composable
fun ReaderSearchDockStrip(
    search: ReaderBookSearch,
    onOpenResults: () -> Unit,
    onStep: (forward: Boolean) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val tokens = readerOverlayTokens()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppLayout.headingGap)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(tokens.shapes.button)
                .clickable(onClick = onOpenResults)
                .padding(SearchRowInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        ) {
            Icon(
                imageVector = AppIcons.Search,
                contentDescription = null,
                tint = tokens.colors.secondaryText,
                modifier = Modifier.size(SearchStripIconSize),
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
                    text = readerSearchPosition(search.currentIndex, search.resultCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.secondaryText,
                    maxLines = 1,
                )
            }
        }
        ReaderBarButton(icon = AppIcons.NavigateBefore, contentDescription = ReaderSearchCopy.Previous, onClick = { onStep(false) })
        ReaderBarButton(icon = AppIcons.NavigateNext, contentDescription = ReaderSearchCopy.Next, onClick = { onStep(true) })
        ReaderBarButton(icon = AppIcons.Close, contentDescription = ReaderSearchCopy.End, onClick = onEnd)
    }
}

/** The snippet with the match in bold on the amber the page tints it with. */
@Composable
private fun searchSnippetText(snippet: ReaderSearchSnippet): AnnotatedString {
    val matchBackground = ReaderSearchTint.copy(alpha = SnippetMatchAlpha)
    return remember(snippet, matchBackground) {
        buildAnnotatedString {
            append(snippet.before)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = matchBackground)) { append(snippet.match) }
            append(snippet.after)
        }
    }
}

private val SearchRowInset = 10.dp
private val SearchStripIconSize = 20.dp
private const val SnippetMatchAlpha = 0.35f
private const val SnippetLines = 3

/** Matches kept above the current one when the list opens, so it reads in context. */
private const val RowsAboveCurrent = 1
