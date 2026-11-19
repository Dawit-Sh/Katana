package com.example.unifiedreader.feature.library

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryGridConfigurationTest {
    @Test
    fun `grid cell size snaps to supported values`() {
        assertEquals(minLibraryGridCellSizeDp, sanitizeLibraryGridCellSizeDp(10))
        assertEquals(70, sanitizeLibraryGridCellSizeDp(83))
        assertEquals(96, sanitizeLibraryGridCellSizeDp(90))
        assertEquals(96, sanitizeLibraryGridCellSizeDp(118))
        assertEquals(140, sanitizeLibraryGridCellSizeDp(119))
        assertEquals(140, sanitizeLibraryGridCellSizeDp(130))
        assertEquals(140, sanitizeLibraryGridCellSizeDp(149))
        assertEquals(maxLibraryGridCellSizeDp, sanitizeLibraryGridCellSizeDp(999))
    }

    @Test
    fun `the old 160 and 200 sizes read as Large`() {
        assertEquals(LibraryCoverSize.Large, LibraryCoverSize.of(160))
        assertEquals(LibraryCoverSize.Large, LibraryCoverSize.of(200))
        assertEquals(LibraryCoverSize.Medium, LibraryCoverSize.of(140))
        assertEquals(LibraryCoverSize.Huge, LibraryCoverSize.of(240))
        assertEquals(listOf(70, 96, 140, 176, 240), supportedLibraryGridCellSizesDp)
    }

    @Test
    fun `every size reads back as itself`() {
        LibraryCoverSize.entries.forEach { size -> assertEquals(size, LibraryCoverSize.of(size.cellSizeDp)) }
    }

    @Test
    fun `on a phone every size has its own column count`() {
        // Tiny, Small, Medium, Large, Huge at 360 and 411 dp: the cover-size slider's stops on a phone.
        assertEquals(listOf(4, 3, 3, 2, 2), LibraryCoverSize.entries.map { size -> phoneColumns(size, 360) })
        assertEquals(listOf(5, 4, 3, 3, 2), LibraryCoverSize.entries.map { size -> phoneColumns(size, 411) })
    }

    @Test
    fun `on a phone Large, the default, gives two columns at 360 and three on wider phones`() {
        assertEquals(2, phoneColumns(LibraryCoverSize.Large, 360))
        assertEquals(2, phoneColumns(LibraryCoverSize.Large, 375))
        listOf(376, 384, 393, 411, 412, 480).forEach { width ->
            assertEquals("at $width dp", 3, phoneColumns(LibraryCoverSize.Large, width))
        }
    }

    @Test
    fun `a phone never gets a single column, whatever size was saved`() {
        // No migration: a size saved when it made one column (Large at 360, Huge anywhere) reads as two now.
        (280..599).forEach { width ->
            LibraryCoverSize.entries.forEach { size ->
                assertTrue("${size.label} at $width dp", phoneColumns(size, width) >= 2)
            }
            listOf(160, 200, 999).forEach { saved ->
                assertTrue("saved $saved at $width dp", phoneColumns(LibraryCoverSize.of(saved), width) >= 2)
            }
        }
    }

    @Test
    fun `from 600 dp the columns are what they were`() {
        // The Fire 7 in portrait: 600 dp, the 24 dp gutter. Tiny 6, Small 5, Medium 3, Large 2, Huge 2.
        assertEquals(listOf(6, 5, 3, 2, 2), LibraryCoverSize.entries.map { size -> tabletColumns(size, 600) })
        // The old rule, minimum widths of 70, 96, 140, 176 and 240, at every width from 600 dp.
        (600..1400 step 7).forEach { width ->
            LibraryCoverSize.entries.forEach { size ->
                val old = maxOf(((width - 48 + 16) / (size.cellSizeDp + 16).toFloat()).toInt(), 1)
                assertEquals("${size.label} at $width dp", old, tabletColumns(size, width))
            }
        }
    }

    @Test
    fun `phone covers ask for the grid thumbnail and tablet covers keep theirs`() {
        LibraryCoverSize.entries.forEach { size ->
            assertEquals(
                LibraryCoverImageVariant.Grid,
                LibraryGridMetrics(cellSizeDp = size.cellSizeDp, compactWindow = true).coverImageVariant,
            )
        }
        assertEquals(
            listOf(
                LibraryCoverImageVariant.Grid,
                LibraryCoverImageVariant.Grid,
                LibraryCoverImageVariant.Grid,
                LibraryCoverImageVariant.GridLarge,
                LibraryCoverImageVariant.GridExtraLarge,
            ),
            LibraryCoverSize.entries.map { size -> LibraryGridMetrics(cellSizeDp = size.cellSizeDp).coverImageVariant },
        )
    }

    @Test
    fun `the grid cells lay out the columns the shelves count`() {
        listOf(1f, 1.875f, 2.625f, 3f).forEach { scale ->
            val density = Density(density = scale)
            listOf(true, false).forEach { compact ->
                (300..1300 step 13).forEach { width ->
                    LibraryCoverSize.entries.forEach { size ->
                        val metrics = LibraryGridMetrics(cellSizeDp = size.cellSizeDp, compactWindow = compact)
                        val available = width.dp
                        val counted = with(density) { libraryGridColumns(availableWidth = available, metrics = metrics) }
                        val cells = with(metrics.gridCells) {
                            density.calculateCrossAxisCellSizes(
                                availableSize = with(density) { available.roundToPx() },
                                spacing = with(density) { metrics.spacing.roundToPx() },
                            )
                        }
                        assertEquals(cells.size, counted.count)
                        assertEquals(cells.min(), with(density) { counted.cellWidth.roundToPx() })
                        assertTrue(cells.size >= metrics.minColumns)
                    }
                }
            }
        }
    }

    @Test
    fun `the presentation counts the columns the grid cells lay out`() {
        // At 1 px per dp, as on the Fire 7. At other densities the two can still differ by one column on the exact
        // width where a column starts to fit, as GridCells.Adaptive and the presentation always could.
        val density = Density(density = 1f)
        (320..1300 step 3).forEach { width ->
            val compact = width < 600
            val gutter = if (compact) 16 else 24
            LibraryCoverSize.entries.forEach { size ->
                val presentation = libraryGridPresentation(
                    maxWidth = width.dp,
                    startPadding = gutter.dp,
                    endPadding = gutter.dp,
                    requestedGridCellSizeDp = size.cellSizeDp,
                    compactWindow = compact,
                )
                val metrics = LibraryGridMetrics(presentation.selectedGridSize.cellSizeDp, compact)
                val counted = with(density) { libraryGridColumns(availableWidth = (width - gutter * 2).dp, metrics = metrics) }
                assertEquals("${size.label} at $width dp", presentation.selectedGridSize.columnCount, counted.count)
            }
        }
    }

    @Test
    fun `narrow covers give the title two lines`() {
        assertEquals(2, libraryCoverTitleLines(98.dp))
        assertEquals(2, libraryCoverTitleLines(156.dp))
        assertEquals(1, libraryCoverTitleLines(160.dp))
        assertEquals(1, libraryCoverTitleLines(268.dp))
        // Large on a 411 dp phone (3 columns) and on the Fire 7 (2 columns of 268 dp).
        val phone = libraryGridPresentation(411.dp, 16.dp, 16.dp, defaultLibraryGridCellSizeDp, compactWindow = true)
        assertEquals(2, libraryCoverTitleLines(phone.selectedGridSize.cellWidthDp.dp))
        val fire = libraryGridPresentation(600.dp, 24.dp, 24.dp, defaultLibraryGridCellSizeDp)
        assertEquals(1, libraryCoverTitleLines(fire.selectedGridSize.cellWidthDp.dp))
    }

    private fun phoneColumns(size: LibraryCoverSize, width: Int): Int = libraryGridPresentation(
        maxWidth = width.dp,
        startPadding = 16.dp,
        endPadding = 16.dp,
        requestedGridCellSizeDp = size.cellSizeDp,
        compactWindow = true,
    ).selectedGridSize.columnCount

    private fun tabletColumns(size: LibraryCoverSize, width: Int): Int = libraryGridPresentation(
        maxWidth = width.dp,
        startPadding = 24.dp,
        endPadding = 24.dp,
        requestedGridCellSizeDp = size.cellSizeDp,
    ).selectedGridSize.columnCount

    @Test
    fun `only Tiny tiles leave the title out`() {
        assertEquals(
            listOf(LibraryCoverSize.Tiny),
            LibraryCoverSize.entries.filterNot(LibraryCoverSize::showsTitle),
        )
    }

    @Test
    fun `docked filters keep the covers near their undocked width instead of stretching`() {
        // Beside the 88 dp rail at 1280 dp, then with the 400 dp panel and its 24 dp gutter docked.
        val undockedWidth = (1280 - 88).dp
        val dockedWidth = undockedWidth - 424.dp
        fun presentation(size: LibraryCoverSize, docked: Boolean) = libraryGridPresentation(
            maxWidth = if (docked) dockedWidth else undockedWidth,
            startPadding = 24.dp,
            endPadding = 24.dp,
            requestedGridCellSizeDp = size.cellSizeDp,
            undockedMaxWidth = undockedWidth,
        )

        val undocked = presentation(LibraryCoverSize.Large, docked = false)
        assertEquals(false, undocked.fixedColumns)
        assertEquals(6, undocked.selectedGridSize.columnCount)

        // The adaptive grid gave Large 3 columns (229 dp covers) and Huge 2 (352 dp).
        val large = presentation(LibraryCoverSize.Large, docked = true)
        assertEquals(true, large.fixedColumns)
        assertEquals(176, large.selectedGridSize.cellSizeDp)
        assertEquals(4, large.selectedGridSize.columnCount)
        assertEquals(3, presentation(LibraryCoverSize.Huge, docked = true).selectedGridSize.columnCount)
        assertEquals(4, presentation(LibraryCoverSize.Medium, docked = true).selectedGridSize.columnCount)
        assertEquals(6, presentation(LibraryCoverSize.Small, docked = true).selectedGridSize.columnCount)
        assertEquals(8, presentation(LibraryCoverSize.Tiny, docked = true).selectedGridSize.columnCount)
    }

    @Test
    fun `grid size options keep only values that change the layout`() {
        val options = availableLibraryGridSizeOptions(
            maxWidth = 900.dp,
            startPadding = 16.dp,
            endPadding = 16.dp,
        )

        assertEquals(listOf(70, 96, 140, 176, 240), options.map(LibraryGridSizeOption::cellSizeDp))
        assertEquals(listOf(10, 7, 5, 4, 3), options.map(LibraryGridSizeOption::columnCount))
    }

    @Test
    fun `large covers, the default, keep their column counts with the tablet gutter`() {
        fun mediumColumns(width: Int, gutter: Int): Int = libraryGridPresentation(
            maxWidth = width.dp,
            startPadding = gutter.dp,
            endPadding = gutter.dp,
            requestedGridCellSizeDp = defaultLibraryGridCellSizeDp,
        ).selectedGridSize.columnCount

        // 800 dp portrait, the tablet's 914 dp portrait, then beside the 88 dp rail: 1280 dp and the tablet's 1292 dp landscape.
        assertEquals(4, mediumColumns(width = 800, gutter = 24))
        assertEquals(4, mediumColumns(width = 914, gutter = 24))
        assertEquals(6, mediumColumns(width = 1280 - 88, gutter = 24))
        assertEquals(6, mediumColumns(width = 1292 - 88, gutter = 24))
    }

    @Test
    fun `compact layouts reduce scrolling prefetch`() {
        val presentation = libraryGridPresentation(
            maxWidth = 660.dp,
            startPadding = 16.dp,
            endPadding = 16.dp,
            requestedGridCellSizeDp = 140,
        )

        assertEquals(140, presentation.selectedGridSize.cellSizeDp)
        assertEquals(2, presentation.scrollingPrefetchItemLimit)
    }

    @Test
    fun `large grid cells use larger cover thumbnails`() {
        assertEquals(LibraryCoverImageVariant.Grid, libraryGridCoverImageVariant(160))
        assertEquals(LibraryCoverImageVariant.GridLarge, libraryGridCoverImageVariant(180))
        assertEquals(LibraryCoverImageVariant.GridExtraLarge, libraryGridCoverImageVariant(200))
        assertEquals(LibraryCoverImageVariant.GridExtraLarge, libraryGridCoverImageVariant(240))
    }

    @Test
    fun `wider layouts keep overflow button visible`() {
        val presentation = libraryGridPresentation(
            maxWidth = 900.dp,
            startPadding = 16.dp,
            endPadding = 16.dp,
            requestedGridCellSizeDp = 200,
        )

        assertEquals(176, presentation.selectedGridSize.cellSizeDp)
        assertEquals(4, presentation.scrollingPrefetchItemLimit)
    }

    @Test
    fun `grid columns match the adaptive grid cells for the same width`() {
        val density = Density(density = 2f)
        val metrics = LibraryGridMetrics(cellSizeDp = 140)
        val availableWidth = 1264.dp

        val columns = with(density) {
            libraryGridColumns(availableWidth = availableWidth, metrics = metrics)
        }
        val adaptiveCellWidthsPx = with(GridCells.Adaptive(minSize = 140.dp)) {
            density.calculateCrossAxisCellSizes(
                availableSize = with(density) { availableWidth.roundToPx() },
                spacing = with(density) { metrics.spacing.roundToPx() },
            )
        }

        assertEquals(adaptiveCellWidthsPx.size, columns.count)
        assertEquals(adaptiveCellWidthsPx.min(), with(density) { columns.cellWidth.roundToPx() })
    }

    @Test
    fun `grid columns never drop below one column`() {
        val columns = with(Density(density = 2f)) {
            libraryGridColumns(availableWidth = 100.dp, metrics = LibraryGridMetrics(cellSizeDp = 240))
        }

        assertEquals(1, columns.count)
        assertEquals(100.dp, columns.cellWidth)
    }

    @Test
    fun `grid metrics pick the grid cover variant for their cell size`() {
        assertEquals(LibraryCoverImageVariant.Grid, LibraryGridMetrics(cellSizeDp = 140).coverImageVariant)
        assertEquals(LibraryCoverImageVariant.GridExtraLarge, LibraryGridMetrics(cellSizeDp = 240).coverImageVariant)
    }
}
