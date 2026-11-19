package com.example.unifiedreader.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag

internal const val LibraryGridLoadingTag = "library-grid-loading"

@Composable
internal fun LoadingLibraryState(
    gridMetrics: LibraryGridMetrics,
    modifier: Modifier = Modifier,
) {
    GridLoadingLibraryState(
        gridMetrics = gridMetrics,
        modifier = modifier,
    )
}

/** The grid's stand-in while it loads: two rows in the columns the grid will lay out. */
@Composable
private fun GridLoadingLibraryState(
    gridMetrics: LibraryGridMetrics,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag(LibraryGridLoadingTag),
    ) {
        val columns = with(LocalDensity.current) { libraryGridColumns(availableWidth = maxWidth, metrics = gridMetrics) }
        Column(verticalArrangement = Arrangement.spacedBy(gridMetrics.spacing)) {
            repeat(GridLoadingRowCount) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gridMetrics.spacing),
                ) {
                    repeat(columns.count) {
                        LibraryGridPlaceholderCard(
                            modifier = Modifier.width(columns.cellWidth),
                        )
                    }
                }
            }
        }
    }
}

private const val GridLoadingRowCount = 2
