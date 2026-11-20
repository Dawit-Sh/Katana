package com.example.unifiedreader.feature.reader.pdf

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.reader.core.readerOverlayTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * A jump while scrolling: to the top of page [pageIndex]; [id] tells two jumps apart. [byReader] is false for
 * a place the book was restored to (progress pulled after it opened), which isn't the reader moving.
 */
internal data class PdfScrollJump(
    val id: Int,
    val pageIndex: Int,
    val animate: Boolean,
    val byReader: Boolean = true,
)

/** What the scrolling view tells the reader: where it is, and the taps and pulls it doesn't handle itself. */
internal class PdfScrollCallbacks(
    /** The page across the middle of the screen, and whether the reader moved there. */
    val onPageInView: (pageIndex: Int, byReader: Boolean) -> Unit,
    /** The finer place, once the pages rest. */
    val onAnchorSettled: (PdfScrollAnchor) -> Unit,
    val onToggleOverlay: () -> Unit,
    /** A pull past the last page: the reader finishing the book. */
    val onScrolledPastEnd: () -> Unit,
    /** The first page drawn (true), or one that couldn't be (false): the end of the book's opening. */
    val onOpened: (drawn: Boolean) -> Unit,
)

/**
 * The book as one strip of pages, top to bottom, for "Pages: Scroll". Pages are laid out as wide as the
 * view and drawn that wide (sharper when zoomed in, once the fingers rest), only near the screen, off the
 * main thread, into a cache with a fixed budget; a page not drawn yet is a plain page-coloured box.
 */
@Composable
internal fun PdfScrollViewport(
    item: ReadableItem,
    documentUri: Uri,
    pageCount: Int,
    start: PdfScrollAnchor,
    jump: PdfScrollJump?,
    callbacks: PdfScrollCallbacks,
    modifier: Modifier = Modifier,
    // The book's highlights and selectable text; null for a book without them.
    marks: PdfTextMarks? = null,
) {
    val images = rememberPdfScrollPageImages(LocalContext.current, item, documentUri)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start.pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    val zoom = remember(listState) { PdfScrollZoomState(listState) }
    val zoomed by remember(zoom) { derivedStateOf { zoom.isZoomed } }
    val scope = rememberCoroutineScope()
    val latestCallbacks by rememberUpdatedState(callbacks)
    BoxWithConstraints(
        modifier = modifier
            // A shade under the pages, so where one ends and the next begins shows on a white page.
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clipToBounds()
            .onSizeChanged { zoom.viewportSize = it }
            .pointerInput(zoom, marks) { detectPdfScrollZoom(zoom, scope, selecting = { marks?.selecting == true }) }
            .pointerInput(zoom) {
                detectTapGestures(
                    onTap = { latestCallbacks.onToggleOverlay() },
                    onDoubleTap = { focal -> zoom.animateToggle(scope, focal) },
                )
            },
    ) {
        val layoutWidthPx = with(LocalDensity.current) { (maxWidth - PdfScrollSideGap * 2).roundToPx() }.coerceAtLeast(1)
        val pages = remember(listState, zoom, images, layoutWidthPx) { PdfScrollPages(listState, zoom, images, layoutWidthPx) }
        PdfScrollPositionEffects(PdfScrollParts(listState, zoom, images, pages), pageCount, start, jump, latestCallbacks)
        PdfScrollPrefetchEffects(PdfScrollParts(listState, zoom, images, pages), pageCount, layoutWidthPx)
        LazyColumn(
            state = listState,
            userScrollEnabled = !zoomed,
            contentPadding = PaddingValues(vertical = PdfScrollPageGap),
            verticalArrangement = Arrangement.spacedBy(PdfScrollPageGap),
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(rememberPdfPastEndConnection(listState) { latestCallbacks.onScrolledPastEnd() })
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offsetX
                    translationY = zoom.offsetY
                },
        ) {
            items(count = pageCount, key = { index -> index }) { index ->
                PdfScrollPageItem(
                    pageIndex = index,
                    heightPx = pdfPageHeightPx(layoutWidthPx, images.aspects[index] ?: images.typicalAspect),
                    renderWidthPx = pages.renderWidthFor(index),
                    images = images,
                    marks = marks,
                )
            }
        }
    }
}

/**
 * One page: its drawing once there is one, a plain page until then. With [marks], its highlights over it, and a
 * long press on it selects its words, as on a turned-to page.
 */
@Composable
private fun PdfScrollPageItem(
    pageIndex: Int,
    heightPx: Int,
    renderWidthPx: Int,
    images: PdfScrollPageImages,
    marks: PdfTextMarks?,
    modifier: Modifier = Modifier,
) {
    val shown = remember(pageIndex, images) { PdfShownPage(images) }
    LaunchedEffect(pageIndex, renderWidthPx, images) {
        if (shown.current == null) shown.show(images.widest(pageIndex))
        if (shown.current?.key?.widthPx != renderWidthPx) shown.show(images.load(pageIndex, renderWidthPx))
    }
    val height = with(LocalDensity.current) { heightPx.toDp() }
    // The page fills its item, so its words sit at fractions of the item's own size, zoomed with the strip.
    var size by remember { mutableStateOf(IntSize.Zero) }
    val words = marks?.let { rememberPdfPageWords(it, pageIndex) { Rect(Offset.Zero, size.toSize()) } }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(readerOverlayTokens().colors.fallbackPage)
            .onSizeChanged { size = it }
            .then(words?.placeTracking() ?: Modifier)
            .then(words?.tapping() ?: Modifier)
            .then(words?.selecting() ?: Modifier)
            .semantics { contentDescription = "Page ${pageIndex + 1}" },
    ) {
        shown.current?.let { page ->
            Image(
                bitmap = page.image,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        words?.Layer(Modifier.fillMaxSize())
        words?.Menu()
    }
}

@Composable
private fun rememberPdfScrollPageImages(
    context: Context,
    item: ReadableItem,
    documentUri: Uri,
): PdfScrollPageImages {
    val images = remember(item.id, documentUri) {
        val metrics = context.resources.displayMetrics
        val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: DefaultMemoryClassMb
        PdfScrollPageImages(
            source = pdfScrollPageSource(context.applicationContext, item, documentUri),
            budgetBytes = pdfPageCacheBudgetBytes(metrics.widthPixels, metrics.heightPixels, memoryClass),
        )
    }
    DisposableEffect(images) { onDispose(images::close) }
    return images
}

/** What the list knows about its pages right now: which are laid out and on screen, the one in view, how wide to draw each. */
@Stable
private class PdfScrollPages(
    private val listState: LazyListState,
    private val zoom: PdfScrollZoomState,
    private val images: PdfScrollPageImages,
    private val layoutWidthPx: Int,
) {
    val laidOut: List<PdfVisiblePage> by derivedStateOf {
        listState.layoutInfo.visibleItemsInfo.map { info -> PdfVisiblePage(pageIndex = info.index, top = info.offset, height = info.size) }
    }

    val pageInView: Int? by derivedStateOf { pdfPageInView(laidOut, zoom.focusListY) }

    // Zoomed in, the screen shows the middle 1/scale of the list's viewport, moved by the offset.
    private val onScreen: Set<Int> by derivedStateOf {
        val halfSpan = zoom.viewportSize.height / (2f * zoom.scale)
        val top = zoom.focusListY - halfSpan
        val bottom = zoom.focusListY + halfSpan
        laidOut.filter { page -> page.top < bottom && page.top + page.height > top }.mapTo(HashSet()) { it.pageIndex }
    }

    /** The zoom the pages on screen are drawn for: the zoom on screen, once a pinch or a zoomed drag has rested. */
    var drawnZoom by mutableFloatStateOf(1f)

    fun renderWidthFor(pageIndex: Int): Int {
        val scale = if (drawnZoom > 1f && pageIndex in onScreen) drawnZoom else 1f
        return pdfRenderWidthPx(layoutWidthPx, scale, images.aspects[pageIndex] ?: images.typicalAspect, images.maxPagePixels)
    }
}

private class PdfScrollParts(
    val listState: LazyListState,
    val zoom: PdfScrollZoomState,
    val images: PdfScrollPageImages,
    val pages: PdfScrollPages,
)

/**
 * Puts the list at [start] once it is laid out, follows [jump]s, and reports the page in view and, once the
 * list rests, the finer place.
 */
@Composable
private fun PdfScrollPositionEffects(
    parts: PdfScrollParts,
    pageCount: Int,
    start: PdfScrollAnchor,
    jump: PdfScrollJump?,
    callbacks: PdfScrollCallbacks,
) {
    val listState = parts.listState
    val zoom = parts.zoom
    val latest by rememberUpdatedState(callbacks)
    var placed by remember { mutableStateOf(false) }
    var jumpByReader by remember { mutableStateOf(false) }
    LaunchedEffect(listState, parts.images) {
        // The start page's own shape first, so it opens at its real height, then down to the saved spot in it.
        parts.images.learnAspect(start.pageIndex)
        val startItem = snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == start.pageIndex } }
            .filterNotNull()
            .first()
        if (start.offsetRatio > 0f) listState.scrollToItem(start.pageIndex, (startItem.size * start.offsetRatio).toInt())
        placed = true
    }
    LaunchedEffect(jump?.id) {
        val target = jump ?: return@LaunchedEffect
        val page = target.pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        jumpByReader = jumpByReader || target.byReader
        parts.images.learnAspect(page)
        if (zoom.isZoomed) zoom.reset()
        if (target.animate) listState.animateScrollToItem(page) else listState.scrollToItem(page)
    }
    LaunchedEffect(parts.images) {
        snapshotFlow { parts.images.drawnOnce to parts.images.failedOnce }
            .collect { (drawn, failed) -> if (drawn || failed) latest.onOpened(drawn) }
    }
    LaunchedEffect(parts.pages) {
        snapshotFlow { parts.pages.pageInView }
            .distinctUntilChanged()
            .collect { page -> if (page != null && placed) latest.onPageInView(page, zoom.touched || jumpByReader) }
    }
    val restingAnchor by remember(parts.pages) {
        derivedStateOf { pdfTopAnchor(parts.pages.laidOut).takeUnless { listState.isScrollInProgress } }
    }
    LaunchedEffect(restingAnchor, placed) {
        val anchor = restingAnchor?.takeIf { placed } ?: return@LaunchedEffect
        delay(PdfAnchorRestMillis)
        latest.onAnchorSettled(anchor)
    }
}

/**
 * Once the list rests, draws the next pages ahead of the reader and reads the shapes of the pages around;
 * once a zoom rests, redraws the pages on screen for it.
 */
@Composable
private fun PdfScrollPrefetchEffects(
    parts: PdfScrollParts,
    pageCount: Int,
    layoutWidthPx: Int,
) {
    val images = parts.images
    val settledPage by remember(parts.pages) {
        derivedStateOf { parts.pages.pageInView.takeUnless { parts.listState.isScrollInProgress } }
    }
    LaunchedEffect(settledPage, layoutWidthPx) {
        val page = settledPage ?: return@LaunchedEffect
        delay(PdfPrefetchDelayMillis)
        for (next in pdfPrefetchPages(page, pageCount)) {
            images.prefetch(next, pdfRenderWidthPx(layoutWidthPx, 1f, images.aspects[next] ?: images.typicalAspect, images.maxPagePixels))
        }
        for (around in (page - PdfAspectWindow..page + PdfAspectWindow).filter { it in 0 until pageCount }) {
            images.learnAspect(around)
        }
    }
    LaunchedEffect(parts.zoom, parts.pages) {
        snapshotFlow { parts.zoom.scale }.collectLatest { scale ->
            delay(PdfZoomRedrawDelayMillis)
            parts.pages.drawnZoom = scale
        }
    }
}

/** A pull past the last page while the list is at its end: the reader finishing the book. */
@Composable
private fun rememberPdfPastEndConnection(
    listState: LazyListState,
    onScrolledPastEnd: () -> Unit,
): NestedScrollConnection {
    val latest by rememberUpdatedState(onScrolledPastEnd)
    return remember(listState) {
        object : NestedScrollConnection {
            private var triggered = false

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!triggered && pdfPullsPastEnd(listState.canScrollForward, available.y, source == NestedScrollSource.UserInput)) {
                    triggered = true
                    latest()
                }
                return Offset.Zero
            }
        }
    }
}

/** Whether a scroll the list couldn't take ([availableY] left over) is the reader pulling past the end. */
internal fun pdfPullsPastEnd(canScrollForward: Boolean, availableY: Float, byTouch: Boolean): Boolean =
    !canScrollForward && byTouch && availableY < -PdfPastEndThresholdPx

/** Two fingers zoom the strip; one finger moves a zoomed strip (unzoomed, the list scrolls by itself). */
private suspend fun PointerInputScope.detectPdfScrollZoom(
    zoom: PdfScrollZoomState,
    scope: CoroutineScope,
    selecting: () -> Boolean,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        zoom.touched = true
        zoom.stopAnimation()
        val gesture = PdfZoomGesture(zoom, viewConfiguration.touchSlop, down, selecting)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            gesture.onEvent(event)
        } while (event.changes.any { it.pressed })
        if (gesture.panning && !gesture.pinched) {
            val maximum = viewConfiguration.maximumFlingVelocity
            val fling = gesture.velocity.calculateVelocity(Velocity(maximum, maximum))
            zoom.launchFling(scope, Offset(fling.x, fling.y), this@detectPdfScrollZoom)
        }
        if (gesture.pinched && !zoom.isZoomed) zoom.reset()
    }
}

/**
 * One touch on the strip: a pinch while two fingers are down, a drag of the zoomed strip once one finger
 * moves past the touch slop. Both take the moves before the list sees them (the initial pass).
 */
private class PdfZoomGesture(
    private val zoom: PdfScrollZoomState,
    private val touchSlop: Float,
    down: PointerInputChange,
    // A finger selecting words moves the selection, never the strip.
    private val selecting: () -> Boolean,
) {
    val velocity = VelocityTracker().apply { addPointerInputChange(down) }
    private var dragged = Offset.Zero

    var panning = false
        private set

    var pinched = false
        private set

    fun onEvent(event: PointerEvent) {
        val pressed = event.changes.filter { it.pressed }
        val movesStrip = zoom.isZoomed && !pinched && !selecting()
        when {
            pressed.size >= 2 -> pinch(event)
            pressed.size == 1 && movesStrip -> drag(pressed.first())
        }
    }

    private fun pinch(event: PointerEvent) {
        pinched = true
        zoom.onTransform(event.calculateCentroid(useCurrent = false), event.calculatePan(), event.calculateZoom())
        event.changes.filter { it.positionChanged() }.forEach { it.consume() }
    }

    private fun drag(change: PointerInputChange) {
        velocity.addPointerInputChange(change)
        dragged += change.positionChange()
        if (!panning && dragged.getDistance() > touchSlop) panning = true
        if (panning) {
            zoom.onPan(change.positionChange())
            change.consume()
        }
    }
}

private val PdfScrollPageGap = 6.dp
private val PdfScrollSideGap = 0.dp
private const val DefaultMemoryClassMb = 128
private const val PdfPrefetchDelayMillis = 120L
private const val PdfZoomRedrawDelayMillis = 180L
private const val PdfAnchorRestMillis = 300L
private const val PdfAspectWindow = 10
private const val PdfPastEndThresholdPx = 24f
