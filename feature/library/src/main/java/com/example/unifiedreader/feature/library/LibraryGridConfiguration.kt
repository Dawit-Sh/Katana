package com.example.unifiedreader.feature.library

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
 * The grid's layout for the cover size [requested] asks for, in its window. [undockedMaxWidth] is the grid's width
 * without a docked panel beside it. While a panel narrows the grid, the covers keep the width they have there: the
 * grid drops columns, and the ones left come as close to that width as whole columns allow, instead of stretching to
 * fill the way the adaptive grid would (Large covers growing by a third, Huge to a single column).
 */
internal fun libraryGridPresentation(
    maxWidth: Dp,
    startPadding: Dp,
    endPadding: Dp,
    requested: LibraryGridMetrics,
    undockedMaxWidth: Dp = maxWidth,
): LibraryGridPresentation {
    val compactWindow = requested.compactWindow
    val docked = undockedMaxWidth > maxWidth
    val layoutWidth = if (docked) undockedMaxWidth else maxWidth
    val availableGridSizes = availableLibraryGridSizeOptions(
        maxWidth = layoutWidth,
        startPadding = startPadding,
        endPadding = endPadding,
        compactWindow = compactWindow,
    )
    val requestedSize = LibraryCoverSize.of(requested.cellSizeDp)
    val requestedColumnCount = libraryGridSizeOption(requestedSize, layoutWidth, startPadding, endPadding, compactWindow).columnCount
    val undockedGridSize = availableGridSizes.firstOrNull { option -> option.columnCount == requestedColumnCount }
        ?: availableGridSizes.minBy { option -> abs(option.cellSizeDp - requestedSize.cellSizeDp) }
    val selectedGridSize = if (docked) {
        val columnCount = libraryGridColumnCountNearest(
            maxWidth = maxWidth,
            startPadding = startPadding,
            endPadding = endPadding,
            cellWidthDp = undockedGridSize.cellWidthDp,
            minColumns = requested.minColumns,
        )
        undockedGridSize.copy(
            columnCount = columnCount,
            cellWidthDp = libraryGridCellWidthDp(maxWidth, startPadding, endPadding, columnCount),
        )
    } else {
        undockedGridSize
    }
    val compactGrid = selectedGridSize.minCellWidthDp <= LibraryCoverSize.Medium.cellSizeDp
    return LibraryGridPresentation(
        selectedGridSize = selectedGridSize,
        availableGridSizes = availableGridSizes,
        scrollingPrefetchItemLimit = if (compactGrid) 2 else 4,
        fixedColumns = docked,
        coverImageVariant = if (compactWindow) {
            requested.coverImageVariant
        } else {
            libraryGridCoverImageVariant(selectedGridSize.minCellWidthDp)
        },
    )
}

/**
 * The cover image a grid cell asks for, sized to the cell. [minCellWidthDp] is the narrowest the cells get. A tablet's
 * stops keep the images they had, apart from Tiny. On a phone Medium and Large take the grid thumbnail (480 px wide)
 * rather than the 720 px one, Huge (two columns, up to 240 dp) the 720 px one rather than the 960 px one, and Tiny
 * and Small (72 dp or less) decode the grid thumbnail at about half size.
 */
fun libraryGridCoverImageVariant(
    minCellWidthDp: Int,
): LibraryCoverImageVariant = when {
    minCellWidthDp >= extraLargeGridCoverCellSizeDp -> LibraryCoverImageVariant.GridExtraLarge
    minCellWidthDp >= LargeGridCoverMinCellSizeDp -> LibraryCoverImageVariant.GridLarge
    minCellWidthDp > SmallGridCoverMaxCellSizeDp -> LibraryCoverImageVariant.Grid
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
private const val extraLargeGridCoverCellSizeDp = 200

// Huge on a phone (144) and up; a tablet's stops under Large (140 at most) stay on the grid thumbnail.
private const val LargeGridCoverMinCellSizeDp = 144
private const val SmallGridCoverMaxCellSizeDp = 72
