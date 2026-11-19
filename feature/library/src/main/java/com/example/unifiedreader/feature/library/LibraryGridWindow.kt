package com.example.unifiedreader.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.appGutter

/**
 * The cover grid's setup in the current window for the cover size the menu saved: a phone's (a window under
 * 600 dp) narrower covers and two columns at least, a tablet's sizes as they were. Every cover grid takes it
 * (the library, See all, a list's books, Bookmarks), so a cover size looks the same everywhere.
 */
@Composable
fun libraryWindowGridMetrics(gridCellSizeDp: Int): LibraryGridMetrics =
    LibraryGridMetrics(cellSizeDp = gridCellSizeDp, compactWindow = isLibraryCompactWindow())

/**
 * Lines for the titles under covers in a grid that spans the window between the gutters (a list's books): on a
 * phone the cells' width follows from the window's, so titles get two lines on narrow covers; from 600 dp, where a
 * rail or a panel may share the width, they keep one.
 */
@Composable
fun libraryWindowGridTitleLines(metrics: LibraryGridMetrics): Int {
    if (!metrics.compactWindow) return 1
    val gridWidth = LocalConfiguration.current.screenWidthDp.dp - appGutter() * 2
    val columns = with(LocalDensity.current) { libraryGridColumns(availableWidth = gridWidth, metrics = metrics) }
    return libraryCoverTitleLines(columns.cellWidth)
}
