package com.example.unifiedreader.feature.library

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppLayout
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln

/**
 * Large covers, the default. 176, not a round 180: with the 24 dp gutter from 600 dp the grid keeps the column
 * counts it had at 16 (4 at 800 dp portrait, 6 beside the rail at 1280 dp and on the tablet in
 * landscape, so the reading card still sits beside the shelves there).
 */
const val defaultLibraryGridCellSizeDp: Int = 176
const val minLibraryGridCellSizeDp: Int = 70
const val maxLibraryGridCellSizeDp: Int = 240

/**
 * The fewest columns a cover grid lays out in a window under 600 dp. One cover as wide as a phone showed a book or
 * two per screen, which is no way to browse a library.
 */
const val libraryCompactGridMinColumns: Int = 2

/** The fewest columns in a window of this kind: [libraryCompactGridMinColumns] under 600 dp, else one. */
fun libraryGridMinColumns(compactWindow: Boolean): Int = if (compactWindow) libraryCompactGridMinColumns else 1

internal val supportedLibraryGridCellSizesDp: List<Int> = LibraryCoverSize.entries.map(LibraryCoverSize::cellSizeDp)

/**
 * The cover sizes the sort menu's slider offers, smallest first. [cellSizeDp] is the size the menu saves and the
 * narrowest a cover gets from 600 dp. A phone (a window under 600 dp) asks for narrower covers, [compactCellSizeDp],
 * and keeps at least two columns: Tiny gives it 4 or 5 columns (360 or 411 wide), Small 3 or 4, Medium 3, Large 2
 * or 3 (3 from 376 wide), Huge 2. From 600 dp nothing changes: the tablet's Large is still 2 columns at 600 dp.
 * Grids saved at 160 or 200 dp, sizes the menu no longer has, read as Large.
 */
enum class LibraryCoverSize(
    val label: String,
    val cellSizeDp: Int,
    val compactCellSizeDp: Int,
) {
    Tiny("Tiny", minLibraryGridCellSizeDp, compactCellSizeDp = 56),
    Small("Small", 96, compactCellSizeDp = 72),
    Medium("Medium", 140, compactCellSizeDp = 88),
    Large("Large", defaultLibraryGridCellSizeDp, compactCellSizeDp = 104),
    Huge("Huge", maxLibraryGridCellSizeDp, compactCellSizeDp = 144),
    ;

    /** Tiny tiles leave the title out: beside the ••• it would be a few letters. */
    val showsTitle: Boolean
        get() = this != Tiny

    /** The narrowest its covers get: [compactCellSizeDp] in a window under 600 dp, [cellSizeDp] from 600 dp. */
    fun minCellWidthDp(compactWindow: Boolean): Int = if (compactWindow) compactCellSizeDp else cellSizeDp

    companion object {
        fun of(gridCellSizeDp: Int): LibraryCoverSize {
            val sanitized = sanitizeLibraryGridCellSizeDp(gridCellSizeDp)
            return entries.first { size -> size.cellSizeDp == sanitized }
        }
    }
}

internal val libraryGridHorizontalSpacingDp: Dp = AppLayout.gridGap

/** The grid's end padding while the alphabet rail shows: the rail takes the gutter's place. */
internal val libraryGridNavigationEndPaddingDp: Dp = 44.dp

const val LibraryCoverAspectRatio: Float = 2f / 3f

/**
 * A cover size as the grid lays it out in one width: [cellSizeDp] is the size the menu saved (it names the stop),
 * [minCellWidthDp] the narrowest its covers get in this window (narrower on a phone), [cellWidthDp] how wide they
 * come out once the columns share the width.
 */
data class LibraryGridSizeOption(
    val cellSizeDp: Int,
    val columnCount: Int,
    val minCellWidthDp: Int = cellSizeDp,
    val cellWidthDp: Float = minCellWidthDp.toFloat(),
)

/**
 * The library grid's column setup, handed to content drawn above the grid (the shelves) so its covers
 * match the grid's covers in size and line up with its columns. [compactWindow]: a window under 600 dp,
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

    override fun hashCode(): Int = minCellWidth.hashCode() * HASH_MULTIPLIER + minColumns

    private companion object {
        const val HASH_MULTIPLIER = 31
    }
}

/** The cells for the cover size the menu saved, [gridCellSizeDp], in this kind of window. */
fun libraryGridCells(
    gridCellSizeDp: Int,
    compactWindow: Boolean,
): GridCells = LibraryGridMetrics(cellSizeDp = gridCellSizeDp, compactWindow = compactWindow).gridCells

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
fun libraryCoverTitleLines(cellWidth: Dp): Int = if (cellWidth < libraryTwoLineTitleMaxCellWidth) 2 else 1

private val libraryTwoLineTitleMaxCellWidth: Dp = 160.dp

internal data class LibraryGridPresentation(
    val selectedGridSize: LibraryGridSizeOption,
    val availableGridSizes: List<LibraryGridSizeOption>,
    val scrollingPrefetchItemLimit: Int,
    // While a docked panel narrows the grid it lays out exactly `selectedGridSize.columnCount` columns.
    val fixedColumns: Boolean = false,
    // The image the grid's covers ask for. On a phone it follows the size asked for, the smaller of two sizes that
    // lay out alike there (Large and Huge at 360 dp); from 600 dp it follows the selected size, as it always did.
    val coverImageVariant: LibraryCoverImageVariant = libraryGridCoverImageVariant(selectedGridSize.minCellWidthDp),
)

fun sanitizeLibraryGridCellSizeDp(
    value: Int,
): Int = when {
    value <= TinyCoverUpperBoundDp -> LibraryCoverSize.Tiny.cellSizeDp
    value <= SmallCoverUpperBoundDp -> LibraryCoverSize.Small.cellSizeDp
    value <= MediumCoverUpperBoundDp -> LibraryCoverSize.Medium.cellSizeDp
    value >= HugeCoverLowerBoundDp -> maxLibraryGridCellSizeDp
    // Large, the default, including the 160 and 200 dp sizes the menu used to offer.
    else -> defaultLibraryGridCellSizeDp
}

/**
 * [undockedMaxWidth] is the grid's width without a docked panel beside it. While a panel narrows the grid, the
 * covers keep the width they have there: the grid drops columns, and the ones left come as close to that width as
 * whole columns allow, instead of stretching to fill the way the adaptive grid would (Large covers growing by a
 * third, Huge to a single column). [compactWindow]: a window under 600 dp, where covers take their phone sizes and
 * the grid keeps at least two columns.
 */
internal fun libraryGridPresentation(
    maxWidth: Dp,
    startPadding: Dp,
    endPadding: Dp,
    requestedGridCellSizeDp: Int,
    undockedMaxWidth: Dp = maxWidth,
    compactWindow: Boolean = false,
): LibraryGridPresentation {
    val docked = undockedMaxWidth > maxWidth
    val layoutWidth = if (docked) undockedMaxWidth else maxWidth
    val availableGridSizes = availableLibraryGridSizeOptions(
        maxWidth = layoutWidth,
        startPadding = startPadding,
        endPadding = endPadding,
        compactWindow = compactWindow,
    )
    val undockedGridSize = selectedLibraryGridSizeOption(
        options = availableGridSizes,
        maxWidth = layoutWidth,
        startPadding = startPadding,
        endPadding = endPadding,
        requestedGridCellSizeDp = requestedGridCellSizeDp,
        compactWindow = compactWindow,
    )
    val selectedGridSize = if (docked) {
        val columnCount = libraryGridColumnCountNearest(
            maxWidth = maxWidth,
            startPadding = startPadding,
            endPadding = endPadding,
            cellWidthDp = undockedGridSize.cellWidthDp,
            minColumns = libraryGridMinColumns(compactWindow),
        )
        undockedGridSize.copy(
            columnCount = columnCount,
            cellWidthDp = libraryGridCellWidthDp(maxWidth, startPadding, endPadding, columnCount),
        )
    } else {
        undockedGridSize
    }
    val compactGrid = selectedGridSize.minCellWidthDp <= LibraryCoverSize.Medium.cellSizeDp
    val imageCellWidthDp = if (compactWindow) {
        LibraryCoverSize.of(requestedGridCellSizeDp).minCellWidthDp(compactWindow = true)
    } else {
        selectedGridSize.minCellWidthDp
    }
    return LibraryGridPresentation(
        selectedGridSize = selectedGridSize,
        availableGridSizes = availableGridSizes,
        scrollingPrefetchItemLimit = if (compactGrid) 2 else 4,
        fixedColumns = docked,
        coverImageVariant = libraryGridCoverImageVariant(imageCellWidthDp),
    )
}

/**
 * The cover image a grid cell asks for, sized to the cell. [minCellWidthDp] is the narrowest the cells get. A tablet's
 * stops keep the images they had. On a phone Medium and Large take the grid thumbnail (480 px wide) rather than the
 * 720 px one, Huge (two columns, up to 240 dp) the 720 px one rather than the 960 px one, and Tiny and Small decode the
 * grid thumbnail at about half size.
 */
fun libraryGridCoverImageVariant(
    minCellWidthDp: Int,
): LibraryCoverImageVariant = when {
    minCellWidthDp >= extraLargeGridCoverCellSizeDp -> LibraryCoverImageVariant.GridExtraLarge
    minCellWidthDp >= largeGridCoverCellSizeDp -> LibraryCoverImageVariant.GridLarge
    minCellWidthDp > smallGridCoverMaxCellSizeDp -> LibraryCoverImageVariant.Grid
    else -> LibraryCoverImageVariant.GridSmall
}

fun availableLibraryGridSizeOptions(
    maxWidth: Dp,
    startPadding: Dp,
    endPadding: Dp,
    compactWindow: Boolean = false,
): List<LibraryGridSizeOption> = LibraryCoverSize.entries
    .map { size -> libraryGridSizeOption(size, maxWidth, startPadding, endPadding, compactWindow) }
    .groupBy(LibraryGridSizeOption::columnCount)
    .values
    .map { optionsForColumnCount ->
        optionsForColumnCount.maxBy(LibraryGridSizeOption::cellSizeDp)
    }
    .sortedBy(LibraryGridSizeOption::cellSizeDp)

private fun libraryGridSizeOption(
    size: LibraryCoverSize,
    maxWidth: Dp,
    startPadding: Dp,
    endPadding: Dp,
    compactWindow: Boolean,
): LibraryGridSizeOption {
    val minCellWidthDp = size.minCellWidthDp(compactWindow)
    val columnCount = libraryGridColumnCount(
        maxWidth = maxWidth,
        startPadding = startPadding,
        endPadding = endPadding,
        minCellSizeDp = minCellWidthDp,
        minColumns = libraryGridMinColumns(compactWindow),
    )
    return LibraryGridSizeOption(
        cellSizeDp = size.cellSizeDp,
        columnCount = columnCount,
        minCellWidthDp = minCellWidthDp,
        cellWidthDp = libraryGridCellWidthDp(maxWidth, startPadding, endPadding, columnCount),
    )
}

private fun selectedLibraryGridSizeOption(
    options: List<LibraryGridSizeOption>,
    maxWidth: Dp,
    startPadding: Dp,
    endPadding: Dp,
    requestedGridCellSizeDp: Int,
    compactWindow: Boolean,
): LibraryGridSizeOption {
    val requested = libraryGridSizeOption(
        size = LibraryCoverSize.of(requestedGridCellSizeDp),
        maxWidth = maxWidth,
        startPadding = startPadding,
        endPadding = endPadding,
        compactWindow = compactWindow,
    )
    return options.firstOrNull { option -> option.columnCount == requested.columnCount }
        ?: options.minBy { option -> abs(option.cellSizeDp - requested.cellSizeDp) }
}

private fun libraryGridColumnCount(
    maxWidth: Dp,
    startPadding: Dp,
    endPadding: Dp,
    minCellSizeDp: Int,
    minColumns: Int = 1,
): Int {
    val availableWidthDp = libraryGridAvailableWidthDp(maxWidth, startPadding, endPadding)
    val minCellWidthDp = minCellSizeDp.toFloat()
    val spacingDp = libraryGridHorizontalSpacingDp.value
    return floor((availableWidthDp + spacingDp) / (minCellWidthDp + spacingDp))
        .toInt()
        .coerceAtLeast(minColumns)
        .coerceAtLeast(1)
}

/** The column count whose cells come closest to [cellWidthDp], measured as a ratio so a step down weighs like a step up. */
private fun libraryGridColumnCountNearest(
    maxWidth: Dp,
    startPadding: Dp,
    endPadding: Dp,
    cellWidthDp: Float,
    minColumns: Int,
): Int {
    val atLeastAsWide = libraryGridColumnCount(
        maxWidth = maxWidth,
        startPadding = startPadding,
        endPadding = endPadding,
        minCellSizeDp = floor(cellWidthDp).toInt(),
        minColumns = minColumns,
    )
    return listOf(atLeastAsWide, atLeastAsWide + 1).minBy { columnCount ->
        abs(ln(libraryGridCellWidthDp(maxWidth, startPadding, endPadding, columnCount) / cellWidthDp))
    }
}

private fun libraryGridCellWidthDp(
    maxWidth: Dp,
    startPadding: Dp,
    endPadding: Dp,
    columnCount: Int,
): Float {
    val spacingDp = libraryGridHorizontalSpacingDp.value
    return (libraryGridAvailableWidthDp(maxWidth, startPadding, endPadding) - spacingDp * (columnCount - 1)) / columnCount
}

private fun libraryGridAvailableWidthDp(
    maxWidth: Dp,
    startPadding: Dp,
    endPadding: Dp,
): Float = (maxWidth.value - startPadding.value - endPadding.value).coerceAtLeast(minLibraryGridCellSizeDp.toFloat())

private const val TinyCoverUpperBoundDp = 83
private const val SmallCoverUpperBoundDp = 118
private const val MediumCoverUpperBoundDp = 150
private const val HugeCoverLowerBoundDp = 220
// Huge on a phone (144) and up; a tablet's stops below Large (140 at most) stay on the grid thumbnail.
private const val largeGridCoverCellSizeDp = 144
private const val extraLargeGridCoverCellSizeDp = 200
private const val smallGridCoverMaxCellSizeDp = 72
