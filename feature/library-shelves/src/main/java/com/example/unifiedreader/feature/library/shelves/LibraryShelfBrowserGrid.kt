package com.example.unifiedreader.feature.library.shelves

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.library.LibraryCoverActionCard
import com.example.unifiedreader.feature.library.LibraryCoverAspectRatio
import com.example.unifiedreader.feature.library.LibraryCoverImageVariant
import com.example.unifiedreader.feature.library.LibraryCoverProgressBar
import com.example.unifiedreader.feature.library.LibraryCoverSize
import com.example.unifiedreader.feature.library.LibraryGridMetrics
import com.example.unifiedreader.feature.library.LibraryItemActionContext
import com.example.unifiedreader.feature.library.LibraryItemPageButton
import com.example.unifiedreader.feature.library.isDownloaded
import com.example.unifiedreader.feature.library.isLibraryCompactWindow
import com.example.unifiedreader.feature.library.libraryCoverTitleLines
import com.example.unifiedreader.feature.library.libraryGridColumns
import com.example.unifiedreader.feature.library.menu.LibraryItemMenuButtonStyle
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.appGutter
import com.example.unifiedreader.ui.theme.AppMotion

/**
 * See all's grid: the shelf's books at the library's cover size, in the library grid's columns (at least two on a
 * phone), titles on two lines where the covers are narrow.
 */
@Composable
internal fun ShelfBrowserGrid(
    kind: LibraryShelfBrowserKind,
    entries: List<LibraryShelfBrowserEntry>,
    header: @Composable () -> Unit,
    contentTopInset: Dp,
    gridCellSizeDp: Int,
    gridState: LazyGridState,
    switchedAtMillis: Long,
    itemActionContext: LibraryItemActionContext,
    coverImageUrlProvider: (ReadableItem, LibraryCoverImageVariant) -> String?,
    onItemSelected: (ReadableItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = LibraryGridMetrics(cellSizeDp = gridCellSizeDp, compactWindow = isLibraryCompactWindow())
    val coverSize = LibraryCoverSize.of(gridCellSizeDp)
    val compactGrid = metrics.minCellWidthDp <= LibraryCoverSize.Medium.cellSizeDp
    val gutter = appGutter()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val columns = with(LocalDensity.current) { libraryGridColumns(availableWidth = maxWidth - gutter * 2, metrics = metrics) }
        val titleLines = libraryCoverTitleLines(columns.cellWidth)
        LazyVerticalGrid(
            columns = metrics.gridCells,
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = gutter,
                end = gutter,
                top = contentTopInset + AppLayout.contentTop,
                bottom = AppLayout.contentBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(if (compactGrid) 12.dp else AppLayout.gridGap),
            horizontalArrangement = Arrangement.spacedBy(AppLayout.gridGap),
        ) {
            item(key = ShelfBrowserHeaderKey, span = { GridItemSpan(maxLineSpan) }) { header() }
            items(
                items = entries,
                // Per shelf, so switching tabs replaces the tiles (they fade) instead of moving them.
                key = { entry -> "${kind.name}:${entry.item.id}" },
            ) { entry ->
                ShelfBrowserGridCard(
                    entry = entry,
                    coverSize = coverSize,
                    titleLines = titleLines,
                    imageVariant = metrics.coverImageVariant,
                    itemActionContext = itemActionContext,
                    coverImageUrlProvider = coverImageUrlProvider,
                    onItemSelected = { onItemSelected(entry.item) },
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = AppMotion.effects(),
                            placementSpec = null,
                            fadeOutSpec = tween(durationMillis = ShelfSwitchFadeOutMillis),
                        )
                        .shelfSwitchEntrance(switchedAtMillis),
                )
            }
        }
    }
}

/** The home strip's tile, at the grid's cover size. */
@Composable
private fun ShelfBrowserGridCard(
    entry: LibraryShelfBrowserEntry,
    coverSize: LibraryCoverSize,
    titleLines: Int,
    imageVariant: LibraryCoverImageVariant,
    itemActionContext: LibraryItemActionContext,
    coverImageUrlProvider: (ReadableItem, LibraryCoverImageVariant) -> String?,
    onItemSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LibraryCoverActionCard(
        item = entry.item,
        title = entry.title,
        showTitle = coverSize.showsTitle,
        titleLines = titleLines,
        supportingText = entry.supportingText.takeIf { coverSize.showsTitle },
        trailingText = entry.trailingText,
        coverImageUrlProvider = coverImageUrlProvider,
        imageVariant = imageVariant,
        isDownloaded = itemActionContext.isDownloaded(entry.item),
        isRead = entry.isRead,
        onClick = onItemSelected,
        titleStyle = if (coverSize <= LibraryCoverSize.Medium) {
            MaterialTheme.typography.labelMedium
        } else {
            MaterialTheme.typography.titleSmall
        },
        supportingStyle = MaterialTheme.typography.bodySmall,
        metadataTopPadding = 8.dp,
        menuContent = {
            LibraryItemPageButton(
                item = entry.item,
                actionContext = itemActionContext,
                buttonStyle = LibraryItemMenuButtonStyle.CompactInline,
            )
        },
        modifier = modifier.fillMaxWidth(),
        coverModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(LibraryCoverAspectRatio),
        coverOverlay = {
            entry.progressFraction?.let { fraction ->
                LibraryCoverProgressBar(
                    fraction = fraction,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        },
    )
}
