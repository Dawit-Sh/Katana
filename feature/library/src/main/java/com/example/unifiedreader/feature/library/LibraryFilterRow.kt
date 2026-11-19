package com.example.unifiedreader.feature.library

import androidx.compose.animation.animateContentSize
import com.example.unifiedreader.ui.theme.AppMotion
import com.example.unifiedreader.ui.components.AppLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unifiedreader.core.model.LibraryFilterFacets
import com.example.unifiedreader.core.model.LibraryFilterPanel
import com.example.unifiedreader.core.model.LibraryFilterState
import com.example.unifiedreader.core.model.LibrarySortMode
import com.example.unifiedreader.ui.components.AppBadge
import com.example.unifiedreader.ui.components.AppChip
import com.example.unifiedreader.ui.components.AppSectionHeading
import com.example.unifiedreader.ui.components.AppChipDefaults
import com.example.unifiedreader.ui.components.AppIcons

/**
 * The row above the grid: on a source's home the [heading] ("All books 1,474"), then the Filters
 * chip with the number of applied values, one chip per applied value naming it (its × removes
 * just that value), and the sort chip at the end. With a heading the chips sit at the end with
 * the sort chip; the applied values scroll when they don't fit. Where the heading, Filters and sort
 * don't fit on one line (a large font size on a phone), the chips go under the heading, and the
 * sort chip under Filters if those two don't fit either (see [LibraryFilterRowLines]): nothing is
 * cut or pushed off.
 */
@Composable
internal fun LibraryFilterRow(
    heading: LibraryGridHeading? = null,
    filterPanels: List<LibraryFilterPanel>,
    filterState: LibraryFilterState,
    filterFacets: LibraryFilterFacets,
    libraryTitlesById: Map<String, String>,
    sortMode: LibrarySortMode,
    gridCellSizeDp: Int,
    fadeReadItems: Boolean,
    showSortControl: Boolean,
    availableSortModes: List<LibrarySortMode> = LibrarySortMode.entries,
    lockedSortLabel: String? = null,
    // null opens the filter sheet at the top; a panel opens it at that section.
    onOpenFilterPanel: (LibraryFilterPanel?) -> Unit,
    // The filters are docked open beside the library: the Filters chip shows as selected.
    filtersOpen: Boolean = false,
    onFilterStateChanged: (LibraryFilterState) -> Unit,
    onSortModeChanged: (LibrarySortMode) -> Unit,
    onGridCellSizeDpChanged: (Int) -> Unit,
    onFadeReadItemsChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onCoverAsPrintedChanged: ((Boolean) -> Unit)? = null,
) {
    val appliedFilters = remember(filterPanels, filterState, libraryTitlesById) {
        appliedLibraryFilters(filterState, filterPanels, libraryTitlesById)
    }
    val filtersEnabled = remember(filterPanels, filterState, filterFacets, libraryTitlesById) {
        !filterState.isEmpty || filterPanels.any { panel ->
            panel.isEnabled(filterState = filterState, filterFacets = filterFacets, libraryTitlesById = libraryTitlesById)
        }
    }
    LibraryFilterRowLayout(
        modifier = modifier,
        heading = heading?.let { shown -> { AppSectionHeading(text = shown.title, count = shown.count) } },
        filters = {
            LibraryFiltersChip(
                appliedCount = appliedFilters.size,
                enabled = filtersEnabled,
                selected = filtersOpen,
                onClick = { onOpenFilterPanel(null) },
            )
        },
        applied = {
            if (appliedFilters.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .animateContentSize(AppMotion.spatial()),
                    horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    appliedFilters.forEach { filter ->
                        LibraryAppliedFilterChip(
                            filter = filter,
                            onClick = { onOpenFilterPanel(filter.panel) },
                            onRemove = { onFilterStateChanged(filter.without) },
                        )
                    }
                }
            }
        },
        sort = {
            LibraryBrowseOptionsSelector(
                sortMode = sortMode,
                gridCellSizeDp = gridCellSizeDp,
                fadeReadItems = fadeReadItems,
                showSortOptions = showSortControl,
                availableSortModes = availableSortModes,
                lockedSortLabel = lockedSortLabel,
                onSortModeChanged = onSortModeChanged,
                onGridCellSizeDpChanged = onGridCellSizeDpChanged,
                onFadeReadItemsChanged = onFadeReadItemsChanged,
                onCoverAsPrintedChanged = onCoverAsPrintedChanged,
            )
        },
    )
}

/**
 * How the filter row lays out in [width]: one line while the heading, Filters and the sort chip fit
 * at their own widths (the applied values take what is left, scrolling); else the chips under the
 * heading; else (Filters and the sort chip too wide for one line together) the sort chip on a line
 * of its own. Without a heading, Filters and sort split onto two lines only when they don't fit.
 * [chipsWidth] is Filters, the gap and the sort chip; [headingSpace] the heading and the gap after it.
 */
internal enum class LibraryFilterRowLines { One, ChipsUnderHeading, SortOnItsOwn }

internal fun libraryFilterRowLines(width: Int, headingSpace: Int?, chipsWidth: Int): LibraryFilterRowLines = when {
    chipsWidth + (headingSpace ?: 0) <= width -> LibraryFilterRowLines.One
    headingSpace != null && chipsWidth <= width -> LibraryFilterRowLines.ChipsUnderHeading
    else -> LibraryFilterRowLines.SortOnItsOwn
}

@Composable
private fun LibraryFilterRowLayout(
    heading: (@Composable () -> Unit)?,
    filters: @Composable () -> Unit,
    applied: @Composable () -> Unit,
    sort: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val headingContent: @Composable () -> Unit = heading ?: {}
    Layout(
        contents = listOf(headingContent, filters, applied, sort),
        modifier = modifier.fillMaxWidth(),
        measurePolicy = LibraryFilterRowMeasurePolicy,
    )
}

/** One placed item of the row: what, and how far from the start of its line. */
private class LibraryFilterRowItem(val placeable: Placeable, val x: Int)

/** Measures the row's slots (heading, Filters, applied values, sort) and lays them out in lines. */
private object LibraryFilterRowMeasurePolicy : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<List<Measurable>>,
        constraints: Constraints,
    ): MeasureResult {
        val width = constraints.maxWidth
        val gap = AppLayout.headingGap.roundToPx()
        val loose = Constraints(maxWidth = width)
        val heading = measurables[HeadingSlot].firstOrNull()?.measure(loose)
        val filters = measurables[FiltersSlot].first().measure(loose)
        val sort = measurables[SortSlot].first().measure(loose)
        val headingSpace = heading?.let { it.width + LibraryFilterRowHeadingGap.roundToPx() }
        val lines = libraryFilterRowLines(width, headingSpace, filters.width + gap + sort.width)
        // The applied values take what their line leaves, and scroll in it.
        val appliedRoom = width - filters.width - gap - lines.besideApplied(gap + sort.width, headingSpace ?: 0)
        val applied = measurables[AppliedSlot].firstOrNull()?.measure(Constraints(maxWidth = appliedRoom.coerceAtLeast(0)))
        val chipsWidth = filters.width + (applied?.let { gap + it.width } ?: 0)
        // On one line with a heading the chips sit at the end, beside the sort chip.
        val chipsX = if (lines == LibraryFilterRowLines.One && heading != null) width - sort.width - gap - chipsWidth else 0
        val chips = listOfNotNull(
            LibraryFilterRowItem(filters, chipsX),
            applied?.let { LibraryFilterRowItem(it, chipsX + filters.width + gap) },
        )
        val sortAtEnd = LibraryFilterRowItem(sort, width - sort.width)
        val headingLine = heading?.let { listOf(LibraryFilterRowItem(it, 0)) }
        val rows = when (lines) {
            LibraryFilterRowLines.One -> listOf(headingLine.orEmpty() + chips + sortAtEnd)
            LibraryFilterRowLines.ChipsUnderHeading -> listOfNotNull(headingLine, chips + sortAtEnd)
            LibraryFilterRowLines.SortOnItsOwn -> listOfNotNull(headingLine, chips, listOf(LibraryFilterRowItem(sort, 0)))
        }
        return placeLines(width, rows, gap)
    }
}

/** What else shares the applied values' line: the sort chip and its gap ([sortSpace]), and the heading's. */
private fun LibraryFilterRowLines.besideApplied(sortSpace: Int, headingSpace: Int): Int = when (this) {
    LibraryFilterRowLines.One -> sortSpace + headingSpace
    LibraryFilterRowLines.ChipsUnderHeading -> sortSpace
    LibraryFilterRowLines.SortOnItsOwn -> 0
}

/** [rows] one under the other, [gap] apart, each item centred on its line's height. */
private fun MeasureScope.placeLines(width: Int, rows: List<List<LibraryFilterRowItem>>, gap: Int): MeasureResult {
    val heights = rows.map { row -> row.maxOf { item -> item.placeable.height } }
    val tops = heights.runningFold(0) { top, height -> top + height + gap }
    return layout(width, tops.last() - gap) {
        rows.forEachIndexed { line, row ->
            row.forEach { item -> item.placeable.place(item.x, tops[line] + (heights[line] - item.placeable.height) / 2) }
        }
    }
}

private val LibraryFilterRowHeadingGap = 12.dp

// The row's slots, in the order Layout gets them.
private const val HeadingSlot = 0
private const val FiltersSlot = 1
private const val AppliedSlot = 2
private const val SortSlot = 3


/** "Filters", with a 20 dp accent badge counting the applied values. */
@Composable
fun LibraryFiltersChip(
    appliedCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    AppChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.semantics {
            contentDescription = if (appliedCount > 0) "Filters, $appliedCount applied" else "Filters"
        },
        leadingIcon = {
            Icon(
                imageVector = AppIcons.FilterList,
                contentDescription = null,
                modifier = Modifier.size(AppChipDefaults.iconSize),
            )
        },
        label = {
            Row(
                modifier = Modifier.animateContentSize(AppMotion.spatial()),
                horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Filters", maxLines = 1)
                if (appliedCount > 0) {
                    AppBadge(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = appliedCount.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun LibraryAppliedFilterChip(
    filter: LibraryAppliedFilter,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    AppChip(
        selected = true,
        onClick = onClick,
        label = {
            Text(
                text = filter.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = LibraryAppliedFilterLabelMaxWidth),
            )
        },
        trailingIcon = {
            Icon(
                imageVector = AppIcons.Close,
                contentDescription = "Remove ${filter.label}",
                modifier = Modifier
                    .size(AppChipDefaults.iconSize)
                    .clickable(onClick = onRemove),
            )
        },
    )
}

private val LibraryAppliedFilterLabelMaxWidth = 200.dp
