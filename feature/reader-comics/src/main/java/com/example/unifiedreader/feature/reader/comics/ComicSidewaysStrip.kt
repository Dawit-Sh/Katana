package com.example.unifiedreader.feature.reader.comics

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens
import androidx.compose.ui.unit.Dp

/**
 * The comic as one strip running sideways, for "Scroll" with "Left to right" or "Right to left": each page as
 * big as fits the view whole ([pageBox]: as tall as the view on a wide screen, as wide as it on a phone held
 * upright), the first page at the start edge, which is the right one for a right-to-left comic. Images load
 * and go as they do in the top-to-bottom strip.
 */
@Composable
internal fun ComicSidewaysStrip(
    listState: LazyListState,
    item: ReadableItem,
    pages: List<ComicPageSpec>,
    pageSource: ComicPageSource,
    // The pages whose images load: those on screen and one either side.
    loadWindow: IntRange,
    metadataLoadingEnabled: Boolean,
    rightToLeft: Boolean,
    pageBox: DpSize,
    onVisiblePageLoaded: (Int) -> Unit,
    onToggleOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The list runs from the start edge, so a right-to-left layout puts page one on the right.
    CompositionLocalProvider(LocalLayoutDirection provides if (rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        LazyRow(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .background(UnifiedReaderThemeTokens.colors.readerCanvas),
            contentPadding = PaddingValues(horizontal = SidewaysPageGap),
            horizontalArrangement = Arrangement.spacedBy(SidewaysPageGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(items = pages, key = { _, page -> page.id }) { _, page ->
                SidewaysComicPageItem(
                    item = item,
                    page = page,
                    pageSource = pageSource,
                    shouldLoadImage = page.pageIndex in loadWindow,
                    metadataLoadingEnabled = metadataLoadingEnabled,
                    pageBox = pageBox,
                    onVisiblePageLoaded = onVisiblePageLoaded,
                    onToggleOverlay = onToggleOverlay,
                )
            }
        }
    }
}

@Composable
private fun SidewaysComicPageItem(
    item: ReadableItem,
    page: ComicPageSpec,
    pageSource: ComicPageSource,
    shouldLoadImage: Boolean,
    metadataLoadingEnabled: Boolean,
    pageBox: DpSize,
    onVisiblePageLoaded: (Int) -> Unit,
    onToggleOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageMetadata by produceState<ComicPageMetadata?>(null, item.id, page.id, metadataLoadingEnabled) {
        value = if (metadataLoadingEnabled) pageSource.loadPageMetadata(item, page) else null
    }
    val pageResult by produceState<ComicPageLoadResult?>(null, item.id, page.id, shouldLoadImage) {
        value = if (shouldLoadImage) pageSource.loadPageImage(item = item, page = page, priority = ComicPageLoadPriority.Visible) else null
    }
    val latestOnLoaded by rememberUpdatedState(onVisiblePageLoaded)
    LaunchedEffect(shouldLoadImage, page.pageIndex, pageResult) {
        if (shouldLoadImage && pageResult != null) latestOnLoaded(page.pageIndex)
    }
    val result = pageResult
    val aspect = (result as? ComicPageLoadResult.Success)?.image?.let { image ->
        image.width / image.height.coerceAtLeast(1).toFloat()
    } ?: pageMetadata?.takeIf { it.width > 0 && it.height > 0 }?.aspectRatio ?: SidewaysFallbackAspect
    val latestOnToggle by rememberUpdatedState(onToggleOverlay)
    val size = sidewaysComicPageSize(pageBox, aspect)
    Box(
        modifier = modifier
            .height(size.height)
            .width(size.width)
            .pointerInput(page.id) { detectTapGestures(onTap = { latestOnToggle() }) },
        contentAlignment = Alignment.Center,
    ) {
        when (result) {
            is ComicPageLoadResult.Success -> Image(
                bitmap = result.image,
                contentDescription = page.contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            is ComicPageLoadResult.Failure -> ReaderPageMessage(message = result.message)
            null -> ReaderPageMessage(message = "Loading page...", isLoading = true)
        }
    }
}

/** The box a sideways page fits in: the view, less a little room above and below and the gaps beside it. */
internal fun sidewaysComicPageBox(viewportWidth: Dp, viewportHeight: Dp): DpSize = DpSize(
    width = (viewportWidth - SidewaysPageGap * 2).coerceAtLeast(SidewaysMinPageSide),
    height = (viewportHeight - SidewaysVerticalRoom * 2).coerceAtLeast(SidewaysMinPageSide),
)

/** A page of shape [aspect] (width over height) as big as fits whole in [box]. */
internal fun sidewaysComicPageSize(box: DpSize, aspect: Float): DpSize {
    val safeAspect = aspect.takeIf { it.isFinite() && it > 0f } ?: SidewaysFallbackAspect
    val height = minOf(box.height, box.width / safeAspect)
    return DpSize(width = height * safeAspect, height = height)
}

private val SidewaysPageGap = 4.dp
private val SidewaysVerticalRoom = 12.dp
private val SidewaysMinPageSide = 120.dp

// A comic page is about two thirds as wide as it is tall; used until a page's own shape is known.
private const val SidewaysFallbackAspect = 0.68f
