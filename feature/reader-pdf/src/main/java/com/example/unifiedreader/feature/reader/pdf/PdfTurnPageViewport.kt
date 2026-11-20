package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.feature.reader.core.ReaderTapSide
import com.example.unifiedreader.feature.reader.core.ReaderZoomLayout
import com.example.unifiedreader.feature.reader.core.ReaderZoomViewportState
import com.example.unifiedreader.feature.reader.core.detectReaderZoomGestures
import com.example.unifiedreader.feature.reader.core.readerTapSide
import com.example.unifiedreader.ui.components.AppCircularProgress
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * One turned-to page, fitted to the screen, zoomed and moved by pinching, panning and double taps; a tap at a side
 * turns the page and one in the middle shows the bars. With [marks] (a PDF that keeps highlights) the page also
 * shows its highlights, and a long press selects words ([PdfPageWords]).
 */
@Composable
internal fun PdfPageViewport(
    pageState: PdfPageRenderState,
    onToggleOverlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRenderLongEdgeRequested: (pageIndex: Int, longEdgePx: Int) -> Unit,
    modifier: Modifier = Modifier,
    marks: PdfTextMarks? = null,
) {
    val animationScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val viewportState = remember(pageState.cacheKey) {
        ReaderZoomViewportState(maximumScale = 5f)
    }
    var viewportSize by remember(pageState.cacheKey) { mutableStateOf(IntSize.Zero) }
    val imageSize = when (pageState) {
        is PdfPageRenderState.Ready -> IntSize(pageState.bitmap.width, pageState.bitmap.height)
        else -> IntSize.Zero
    }
    val contentPaddingPx = with(density) { PdfPagePadding.toPx() }
    val zoomLayout = remember(viewportSize, imageSize, contentPaddingPx) { pdfPageZoomLayout(viewportSize, imageSize, contentPaddingPx) }
    PdfPageZoomEffects(pageState, viewportState, zoomLayout, onRenderLongEdgeRequested)
    // The page's text, for a page that is drawn and a book that keeps highlights. A page turned away from
    // takes its selection along.
    val readyPageIndex = (pageState as? PdfPageRenderState.Ready)?.pageIndex
    LaunchedEffect(marks, readyPageIndex) {
        if (readyPageIndex != null) marks?.keepOnly(readyPageIndex)
    }
    val latestTaps = rememberUpdatedState(PdfPageTaps(onPrevious = onPrevious, onNext = onNext, onToggleOverlay = onToggleOverlay))
    val text = marks?.let { pageMarks ->
        (pageState as? PdfPageRenderState.Ready)?.let { ready ->
            rememberPdfPageWords(
                marks = pageMarks,
                pageIndex = ready.pageIndex,
                pageBounds = { pdfTurnPageBounds(viewportSize, zoomLayout.contentSize, viewportState.scale, viewportState.offset) },
            )
        }
    }
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .then(text?.placeTracking() ?: Modifier)
            .pdfPageTouches(
                viewportState = viewportState,
                animationScope = animationScope,
                viewportSize = { viewportSize },
                text = text,
                taps = { latestTaps.value },
            )
            .pdfZoomSemantics(viewportState, animationScope) { viewportSize.center },
        contentAlignment = Alignment.Center,
    ) {
        when (pageState) {
            PdfPageRenderState.Empty -> PdfStatus("No PDF document available.")
            PdfPageRenderState.Loading -> AppCircularProgress()
            is PdfPageRenderState.Error -> PdfStatus(pageState.message)
            is PdfPageRenderState.Ready -> {
                PdfPageImage(pageState, viewportState)
                text?.Layer(Modifier.fillMaxSize())
                text?.Menu()
            }
        }
    }
}

/**
 * Hands the zoom its layout, and once a zoom rests asks for the page drawn sharp enough for it
 * ([onRenderLongEdgeRequested]).
 */
@Composable
private fun PdfPageZoomEffects(
    pageState: PdfPageRenderState,
    viewportState: ReaderZoomViewportState,
    zoomLayout: ReaderZoomLayout,
    onRenderLongEdgeRequested: (pageIndex: Int, longEdgePx: Int) -> Unit,
) {
    LaunchedEffect(zoomLayout) {
        viewportState.updateLayout(zoomLayout)
    }
    val latestRenderRequest by rememberUpdatedState(onRenderLongEdgeRequested)
    LaunchedEffect(pageState.cacheKey, zoomLayout, viewportState.scale) {
        val readyPage = pageState as? PdfPageRenderState.Ready ?: return@LaunchedEffect
        if (!zoomLayout.isReady) return@LaunchedEffect
        delay(PdfRenderRequestDebounceMillis)
        val requiredLongEdge = (
            maxOf(zoomLayout.contentSize.width, zoomLayout.contentSize.height) *
                viewportState.scale
            ).roundToInt()
        latestRenderRequest(
            readyPage.pageIndex,
            pdfRenderLongEdgeBucket(requiredLongEdge),
        )
    }
}

/** The drawn page, fitted, then zoomed and moved as [viewportState] says; its bitmap goes when it leaves. */
@Composable
private fun PdfPageImage(pageState: PdfPageRenderState.Ready, viewportState: ReaderZoomViewportState) {
    val bitmap = pageState.bitmap
    DisposableEffect(bitmap) {
        onDispose {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = pageState.contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .padding(PdfPagePadding)
            .graphicsLayer {
                scaleX = viewportState.scale
                scaleY = viewportState.scale
                translationX = viewportState.offset.x
                translationY = viewportState.offset.y
            },
    )
}

internal fun pdfPageZoomLayout(
    viewportSize: IntSize,
    imageSize: IntSize,
    contentPaddingPx: Float,
): ReaderZoomLayout {
    if (!viewportSize.hasArea || !imageSize.hasArea) {
        return ReaderZoomLayout.Empty
    }
    val availableWidth = (viewportSize.width - contentPaddingPx * 2f).coerceAtLeast(1f)
    val availableHeight = (viewportSize.height - contentPaddingPx * 2f).coerceAtLeast(1f)
    val baseScale = minOf(
        availableWidth / imageSize.width,
        availableHeight / imageSize.height,
    )
    return ReaderZoomLayout(
        viewportSize = viewportSize,
        contentSize = Size(
            width = imageSize.width * baseScale,
            height = imageSize.height * baseScale,
        ),
    )
}

private val IntSize.center: Offset
    get() = Offset(width / 2f, height / 2f)

private val IntSize.hasArea: Boolean
    get() = width > 0 && height > 0

/** What a tap does to the turned-to page when it isn't on a highlight or a selection. */
private class PdfPageTaps(
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onToggleOverlay: () -> Unit,
)

/**
 * The page's touches: a tap turns the page at a side or shows the bars in the middle (only the bars while zoomed),
 * unless it was on a highlight or a selection ([text]); a double tap zooms; pinching and panning zoom and move it;
 * and, with [text], a long press, a drag or a handle selects words, which never pans the page.
 */
private fun Modifier.pdfPageTouches(
    viewportState: ReaderZoomViewportState,
    animationScope: CoroutineScope,
    viewportSize: () -> IntSize,
    text: PdfPageWords?,
    taps: () -> PdfPageTaps,
): Modifier = this
        .pointerInput(viewportState, text) {
            detectTapGestures(
                onTap = { tapOffset ->
                    if (text?.tap(tapOffset, this) == true) return@detectTapGestures
                    when (if (viewportState.isZoomed) ReaderTapSide.Middle else readerTapSide(tapOffset.x, size.width.toFloat())) {
                        ReaderTapSide.Left -> taps().onPrevious()
                        ReaderTapSide.Right -> taps().onNext()
                        ReaderTapSide.Middle -> taps().onToggleOverlay()
                    }
                },
                onDoubleTap = { tapOffset ->
                    viewportState.animateZoomToggle(
                        coroutineScope = animationScope,
                        focalPoint = tapOffset,
                    )
                },
            )
        }
        .pointerInput(viewportState, text) {
            detectReaderZoomGestures(
                viewportCenter = { viewportSize().center },
                // A finger selecting words moves the selection, never the page.
                canPan = { intent -> text?.marks?.selecting != true && viewportState.canPan(intent) },
                onPointerDown = viewportState::stopAnimation,
                onTransformStart = {},
                onPan = viewportState::onPan,
                onTransform = { centroid, panChange, zoomChange ->
                    viewportState.onGesture(
                        centroid = centroid,
                        zoomChange = zoomChange,
                        panChange = panChange,
                    )
                },
                onTransformEnd = { velocity, _ ->
                    viewportState.launchFling(
                        coroutineScope = animationScope,
                        initialVelocity = velocity,
                        density = this,
                    )
                },
            )
        }
        .then(text?.selecting() ?: Modifier)

/** The zoom for accessibility services: how far in, and zoom in, out and back. */
private fun Modifier.pdfZoomSemantics(
    viewportState: ReaderZoomViewportState,
    animationScope: CoroutineScope,
    center: () -> Offset,
): Modifier = semantics {
    stateDescription = "${(viewportState.scale * Percent).roundToInt()}% zoom"
    customActions = listOf(
        CustomAccessibilityAction(label = "Zoom in") {
            viewportState.animateZoomBy(coroutineScope = animationScope, zoomFactor = ZoomStep, focalPoint = center())
            true
        },
        CustomAccessibilityAction(label = "Zoom out") {
            viewportState.animateZoomBy(coroutineScope = animationScope, zoomFactor = 1f / ZoomStep, focalPoint = center())
            true
        },
        CustomAccessibilityAction(label = "Reset zoom") {
            viewportState.animateReset(coroutineScope = animationScope)
            true
        },
    )
}

@Composable
private fun PdfStatus(
    message: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The room around a turned-to page at its fitted size; the zoom layout leaves the same. */
private val PdfPagePadding = 12.dp
private const val PdfRenderRequestDebounceMillis = 180L
private const val ZoomStep = 1.5f
private const val Percent = 100f
