package com.example.unifiedreader.feature.library

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The fewest columns a cover grid lays out in a window under 600 dp. One cover as wide as a phone showed a book or
 * two per screen, which is no way to browse a library.
 */
const val LibraryCompactGridMinColumns: Int = 2

/** The fewest columns in a window of this kind: [LibraryCompactGridMinColumns] under 600 dp, else one. */
fun libraryGridMinColumns(compactWindow: Boolean): Int = if (compactWindow) LibraryCompactGridMinColumns else 1

/**
 * The library grid's column setup for a cover size in a window, handed to content drawn above the grid (the shelves)
 * so its covers match the grid's covers in size and line up with its columns, and to every other cover grid (See all,
 * a list's books, Bookmarks) so a cover size looks the same everywhere. [compactWindow]: a window under 600 dp,
 * where covers take their phone sizes and the grid keeps at least two columns.
 */
data class LibraryGridMetrics(
    val cellSizeDp: Int = defaultLibraryGridCellSizeDp,
    val compactWindow: Boolean = false,
) {
    val spacing: Dp
        get() = libraryGridHorizontalSpacingDp

    /** The narrowest a cover gets in this window. */
    val minCellWidthDp: Int
        get() = LibraryCoverSize.of(cellSizeDp).minCellWidthDp(compactWindow)

    val minColumns: Int
        get() = libraryGridMinColumns(compactWindow)

    val coverImageVariant: LibraryCoverImageVariant
        get() = libraryGridCoverImageVariant(minCellWidthDp)

    /** The grid's cells: as many columns as [minCellWidthDp] allows, never fewer than [minColumns]. */
    val gridCells: GridCells
        get() = LibraryGridCells(minCellWidth = minCellWidthDp.dp, minColumns = minColumns)
}

data class LibraryGridColumns(
    val count: Int,
    val cellWidth: Dp,
)

/**
 * The columns the grid lays out across [availableWidth] (its width inside the content padding).
 * Counted the way [LibraryGridCells], which the grid uses, counts them, so a full-span row can reproduce its cells.
 */
fun Density.libraryGridColumns(
    availableWidth: Dp,
    metrics: LibraryGridMetrics,
): LibraryGridColumns {
    val availableWidthPx = availableWidth.roundToPx()
    val spacingPx = metrics.spacing.roundToPx()
    val count = libraryGridColumnCount(
        availableWidthPx = availableWidthPx,
        spacingPx = spacingPx,
        minCellWidthPx = metrics.minCellWidthDp.dp.roundToPx(),
        minColumns = metrics.minColumns,
    )
    val cellWidthPx = ((availableWidthPx - spacingPx * (count - 1)) / count).coerceAtLeast(0)
    return LibraryGridColumns(
        count = count,
        cellWidth = cellWidthPx.toDp(),
    )
}

/**
 * A cover grid's cells: as many columns as [minCellWidth] allows across the width, the rest of the width shared out
 * among them, as `GridCells.Adaptive` does, but never fewer than [minColumns].
 */
@Immutable
class LibraryGridCells(
    private val minCellWidth: Dp,
    private val minColumns: Int = 1,
) : GridCells {
    init {
        require(minCellWidth > 0.dp) { "minCellWidth must be positive" }
        require(minColumns >= 1) { "minColumns must be at least 1" }
    }

    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = libraryGridColumnCount(
            availableWidthPx = availableSize,
            spacingPx = spacing,
            minCellWidthPx = minCellWidth.roundToPx(),
            minColumns = minColumns,
        )
        return with(GridCells.Fixed(count)) { calculateCrossAxisCellSizes(availableSize, spacing) }
    }

    override fun equals(other: Any?): Boolean =
        other is LibraryGridCells && minCellWidth == other.minCellWidth && minColumns == other.minColumns

    override fun hashCode(): Int = minCellWidth.hashCode() * HashMultiplier + minColumns

    private companion object {
        const val HashMultiplier = 31
    }
}

/** How many columns of at least [minCellWidthPx] fit across [availableWidthPx], and at least [minColumns]. */
internal fun libraryGridColumnCount(
    availableWidthPx: Int,
    spacingPx: Int,
    minCellWidthPx: Int,
    minColumns: Int,
): Int = maxOf((availableWidthPx + spacingPx) / (minCellWidthPx + spacingPx).coerceAtLeast(1), minColumns, 1)

/**
 * Lines for the title under a cover [cellWidth] wide: two, both kept, on covers narrower than 160 dp (a phone's,
 * a tablet's Small), where one line cut most titles to a word or two; one, scrolling once when long, on wider covers.
 */
fun libraryCoverTitleLines(cellWidth: Dp): Int = if (cellWidth < LibraryTwoLineTitleMaxCellWidth) 2 else 1

private val LibraryTwoLineTitleMaxCellWidth: Dp = 160.dp

/**
 * The title lines under each cover of the grid around it ([libraryCoverTitleLines] of its cells), which a grid
 * provides and its tiles ([LibraryCoverActionCard]) read. One outside a grid.
 */
val LocalLibraryCoverTitleLines = staticCompositionLocalOf { 1 }
