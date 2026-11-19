package com.example.unifiedreader.feature.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.feature.reader.core.ReaderDocumentState
import com.example.unifiedreader.feature.reader.core.ReaderOpenFailure
import com.example.unifiedreader.feature.reader.core.ReaderOpenMode
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import com.example.unifiedreader.feature.reader.core.ReaderTapSide
import com.example.unifiedreader.feature.reader.core.ReaderZoomLayout
import com.example.unifiedreader.feature.reader.core.ReaderZoomViewportState
import com.example.unifiedreader.feature.reader.core.ReportReaderOpening
import com.example.unifiedreader.feature.reader.core.detectReaderZoomGestures
import com.example.unifiedreader.feature.reader.core.readerFileMissingFailure
import com.example.unifiedreader.feature.reader.core.readerOpenFailureOf
import com.example.unifiedreader.feature.reader.core.readerTapSide
import com.example.unifiedreader.feature.reader.core.rememberReaderDocument
import com.example.unifiedreader.feature.reader.preferences.InMemoryReaderComfortPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.InMemoryReaderPageNumberPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.InMemoryReaderPagesPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.ReaderComfortPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.ReaderPageNumberPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesPreferencesStore
import com.example.unifiedreader.journal.JournalReadingSessionRecorder
import com.example.unifiedreader.ui.components.AppCircularProgress
import java.util.LinkedHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The PDF and DjVu reader. Its pages turn one at a time or scroll as one strip ("Pages" in its settings,
 * kept per book); the place, the reading session and the chrome are the same in both.
 */
@Composable
fun PdfReaderScreen(
    item: ReadableItem,
    documentUri: Uri? = null,
    documentUriProvider: (suspend (ReadableItem) -> Uri?)? = null,
    sessionStore: ReaderSessionStore? = null,
    journalReadingSessionRecorder: JournalReadingSessionRecorder? = null,
    pageNumberPreferences: ReaderPageNumberPreferencesStore = remember { InMemoryReaderPageNumberPreferencesStore() },
    pagesPreferences: ReaderPagesPreferencesStore = remember { InMemoryReaderPagesPreferencesStore() },
    comfortPreferences: ReaderComfortPreferencesStore = remember { InMemoryReaderComfortPreferencesStore() },
    openMode: ReaderOpenMode = ReaderOpenMode.Default,
    onBack: () -> Unit = {},
    onCompletionRequested: () -> Unit = {},
) {
    val context = LocalContext.current
    // Try again asks for the file again.
    var openAttempt by remember(item.id) { mutableIntStateOf(0) }
    val documentState by rememberReaderDocument(item, documentUri, documentUriProvider, openAttempt)
    val documentInfo by rememberPdfDocumentInfo(item, documentState)
    val pageCount = (documentInfo as? PdfDocumentInfoState.Ready)?.pageCount ?: 0
    val pages = rememberPdfPagesChoice(item, sessionStore, pagesPreferences)
    val place = rememberPdfReaderPlace(item, documentState, pageCount, openMode, pages.mode, sessionStore)
    val actions = rememberPdfReaderSessionActions(
        item = item,
        place = place,
        pageCount = pageCount,
        sessionStore = sessionStore,
        journalReadingSessionRecorder = journalReadingSessionRecorder,
        scope = rememberCoroutineScope(),
        onBack = onBack,
        onCompletionRequested = onCompletionRequested,
    )
    val pageRenderer = remember(item.id, item.format) { pageImageRenderer(context.applicationContext, item) }
    if (item.format == FormatType.DJVU) {
        DisposableEffect(item.id) { onDispose(::releaseDjvuDocument) }
    }
    // The opening state stays until the first page is drawn.
    val opening = remember(item.id) { PdfOpeningState() }
    ReportReaderOpening(
        status = pdfOpenStatus(documentInfo, documentState, opening.pageFailed, opening.firstPageShown, item),
        retry = { openAttempt++ },
    )
    PdfReaderContent(
        parts = PdfReaderParts(PdfReaderBook(item, documentState, pageCount, pageRenderer), place, pages, actions, opening),
        pageNumberPreferences = pageNumberPreferences,
        volumeKeysTurnPages = comfortPreferences.comfortFlow.collectAsStateWithLifecycle().value.volumeKeysTurnPages,
        turnPages = { navigation -> PdfTurnPages(item, documentState, pageCount, place, pageRenderer, opening, navigation) },
    )
}

/** The page count, read once the file is here: PdfRenderer for a PDF, DjVuLibre for a DjVu book. */
@Composable
private fun rememberPdfDocumentInfo(
    item: ReadableItem,
    documentState: ReaderDocumentState,
): State<PdfDocumentInfoState> {
    val context = LocalContext.current
    return produceState<PdfDocumentInfoState>(
        initialValue = PdfDocumentInfoState.Loading,
        key1 = documentState.cacheKey,
    ) {
        value = when (val document = documentState) {
            ReaderDocumentState.Loading -> PdfDocumentInfoState.Loading
            is ReaderDocumentState.Failed -> PdfDocumentInfoState.Error(document.failure)
            is ReaderDocumentState.Ready -> withContext(Dispatchers.IO) {
                if (item.format == FormatType.DJVU) {
                    inspectDjvuDocument(context = context.applicationContext, item = item, documentUri = document.uri)
                } else {
                    inspectPdfDocument(context = context.applicationContext, item = item, documentUri = document.uri)
                }
            }
        }
    }
}

/**
 * Turn pages: one page at a time, drawn when it is turned to (sharper when zoomed), as before scrolling
 * came. Nothing of the scrolling view runs here.
 */
@Composable
private fun PdfTurnPages(
    item: ReadableItem,
    documentState: ReaderDocumentState,
    pageCount: Int,
    place: PdfReaderPlace,
    pageRenderer: PageImageRenderer,
    opening: PdfOpeningState,
    navigation: PdfNavigation,
    modifier: Modifier = Modifier,
) {
    val currentPageIndex = place.pageIndex
    var renderLongEdgePx by remember(item.id, documentState.cacheKey, currentPageIndex) {
        mutableIntStateOf(PDF_RENDER_LONG_EDGE_PX)
    }
    // Nothing is drawn until the saved page is known: drawing page 1 first would hold PDFium while the saved
    // page waits, so a book reopened mid-way showed its page later than it needed to.
    val restored = place.restored
    val pageState by produceState<PdfPageRenderState>(
        initialValue = PdfPageRenderState.Loading,
        documentState.cacheKey,
        currentPageIndex,
        pageCount,
        renderLongEdgePx,
        restored,
    ) {
        val uri = (documentState as? ReaderDocumentState.Ready)?.uri
        val targetPageIndex = currentPageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val renderedState = when {
            !restored && pageCount > 0 -> return@produceState
            uri == null || pageCount <= 0 -> PdfPageRenderState.Empty
            else -> withContext(Dispatchers.IO) { pageRenderer(uri, targetPageIndex, renderLongEdgePx) }
        }
        val previousReadyPage = value as? PdfPageRenderState.Ready
        if (renderedState is PdfPageRenderState.Error && previousReadyPage?.pageIndex == targetPageIndex) {
            return@produceState
        }
        value = renderedState
    }
    LaunchedEffect(pageState) {
        if (pageState is PdfPageRenderState.Ready) opening.firstPageShown = true
        opening.pageFailed = pageState is PdfPageRenderState.Error
    }
    PdfPageViewport(
        pageState = pageState,
        modifier = modifier.fillMaxSize(),
        onToggleOverlay = navigation.toggleOverlay,
        onPrevious = navigation.previous,
        onNext = navigation.next,
        onRenderLongEdgeRequested = { pageIndex, requestedLongEdge ->
            if (pageIndex == place.pageIndex && requestedLongEdge > renderLongEdgePx) {
                renderLongEdgePx = requestedLongEdge
            }
        },
    )
}

@Composable
private fun PdfPageViewport(
    pageState: PdfPageRenderState,
    modifier: Modifier = Modifier,
    onToggleOverlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRenderLongEdgeRequested: (pageIndex: Int, longEdgePx: Int) -> Unit,
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
    val contentPaddingPx = with(density) { 12.dp.toPx() }
    val zoomLayout = remember(viewportSize, imageSize, contentPaddingPx) {
        pdfPageZoomLayout(
            viewportSize = viewportSize,
            imageSize = imageSize,
            contentPaddingPx = contentPaddingPx,
        )
    }
    LaunchedEffect(zoomLayout) {
        viewportState.updateLayout(zoomLayout)
    }
    LaunchedEffect(pageState.cacheKey, zoomLayout, viewportState.scale) {
        val readyPage = pageState as? PdfPageRenderState.Ready ?: return@LaunchedEffect
        if (!zoomLayout.isReady) return@LaunchedEffect
        delay(PDF_RENDER_REQUEST_DEBOUNCE_MILLIS)
        val requiredLongEdge = (
            maxOf(zoomLayout.contentSize.width, zoomLayout.contentSize.height) *
                viewportState.scale
            ).roundToInt()
        onRenderLongEdgeRequested(
            readyPage.pageIndex,
            pdfRenderLongEdgeBucket(requiredLongEdge),
        )
    }
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(pageState.cacheKey, viewportSize) {
                detectTapGestures(
                    onTap = { tapOffset ->
                        when (if (viewportState.isZoomed) ReaderTapSide.Middle else readerTapSide(tapOffset.x, size.width.toFloat())) {
                            ReaderTapSide.Left -> onPrevious()
                            ReaderTapSide.Right -> onNext()
                            ReaderTapSide.Middle -> onToggleOverlay()
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
            .pointerInput(pageState.cacheKey, viewportSize) {
                detectReaderZoomGestures(
                    viewportCenter = { viewportSize.center },
                    canPan = viewportState::canPan,
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
            .semantics {
                stateDescription = "${(viewportState.scale * 100f).roundToInt()}% zoom"
                customActions = listOf(
                    CustomAccessibilityAction(label = "Zoom in") {
                        viewportState.animateZoomBy(
                            coroutineScope = animationScope,
                            zoomFactor = 1.5f,
                            focalPoint = viewportSize.center,
                        )
                        true
                    },
                    CustomAccessibilityAction(label = "Zoom out") {
                        viewportState.animateZoomBy(
                            coroutineScope = animationScope,
                            zoomFactor = 1f / 1.5f,
                            focalPoint = viewportSize.center,
                        )
                        true
                    },
                    CustomAccessibilityAction(label = "Reset zoom") {
                        viewportState.animateReset(
                            coroutineScope = animationScope,
                        )
                        true
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when (pageState) {
            PdfPageRenderState.Empty -> PdfStatus("No PDF document available.")
            PdfPageRenderState.Loading -> AppCircularProgress()
            is PdfPageRenderState.Error -> PdfStatus(pageState.message)
            is PdfPageRenderState.Ready -> {
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
                        .padding(12.dp)
                        .graphicsLayer {
                            scaleX = viewportState.scale
                            scaleY = viewportState.scale
                            translationX = viewportState.offset.x
                            translationY = viewportState.offset.y
                        },
                )
            }
        }
    }
}

internal fun pdfPageZoomLayout(
    viewportSize: IntSize,
    imageSize: IntSize,
    contentPaddingPx: Float,
): ReaderZoomLayout {
    if (
        viewportSize.width <= 0 || viewportSize.height <= 0 ||
        imageSize.width <= 0 || imageSize.height <= 0
    ) {
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

internal fun pdfRenderLongEdgeBucket(requiredLongEdgePx: Int): Int = when {
    requiredLongEdgePx <= PDF_RENDER_LONG_EDGE_PX -> PDF_RENDER_LONG_EDGE_PX
    requiredLongEdgePx <= PDF_RENDER_MEDIUM_LONG_EDGE_PX -> PDF_RENDER_MEDIUM_LONG_EDGE_PX
    else -> PDF_RENDER_MAX_LONG_EDGE_PX
}

private val IntSize.center: Offset
    get() = Offset(width / 2f, height / 2f)

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


private suspend fun inspectPdfDocument(
    context: Context,
    item: ReadableItem,
    documentUri: Uri,
): PdfDocumentInfoState = runSuspendCatching {
    context.contentResolver.openFileDescriptor(documentUri, "r")?.use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            val pageCount = renderer.pageCount
            if (pageCount <= 0) {
                PdfDocumentInfoState.Error(ReaderOpenFailure.NoPdfPages)
            } else {
                PdfDocumentInfoState.Ready(pageCount = pageCount)
            }
        }
    } ?: PdfDocumentInfoState.Error(readerFileMissingFailure(item))
}.getOrElse { error ->
    // A file that can't be found is gone; one PdfRenderer refuses is damaged or locked.
    val failure = readerOpenFailureOf(error, item).takeUnless { it == ReaderOpenFailure.Unknown } ?: ReaderOpenFailure.PdfUnreadable
    PdfDocumentInfoState.Error(failure)
}

internal suspend fun renderPdfPage(
    context: Context,
    documentUri: Uri,
    pageIndex: Int,
    targetLongEdgePx: Int = PDF_RENDER_LONG_EDGE_PX,
): PdfPageRenderState {
    var unownedBitmap: Bitmap? = null
    return try {
        val renderedState = context.contentResolver.openFileDescriptor(documentUri, "r")
            ?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount <= 0) {
                        return PdfPageRenderState.Error("This PDF has no pages.")
                    }
                    val safePageIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
                    renderer.openPage(safePageIndex).use { page ->
                        val scale = targetLongEdgePx.toFloat() /
                            maxOf(page.width, page.height).coerceAtLeast(1)
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        unownedBitmap = bitmap
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        currentCoroutineContext().ensureActive()
                        PdfPageRenderState.Ready(
                            bitmap = bitmap,
                            pageIndex = safePageIndex,
                        )
                    }
                }
            }
            ?: PdfPageRenderState.Error("Unable to open this PDF.")
        if (renderedState is PdfPageRenderState.Ready) {
            unownedBitmap = null
        }
        renderedState
    } catch (error: CancellationException) {
        unownedBitmap?.recycle()
        throw error
    } catch (_: Throwable) {
        unownedBitmap?.recycle()
        PdfPageRenderState.Error("Unable to open this PDF.")
    }
}

private fun pdfProgressLabel(
    currentPageIndex: Int,
    pageCount: Int,
): String = if (pageCount > 0) {
    "Page ${currentPageIndex + 1} of $pageCount"
} else {
    "Preparing PDF"
}

internal sealed interface PdfDocumentInfoState {
    data object Loading : PdfDocumentInfoState
    data class Ready(val pageCount: Int) : PdfDocumentInfoState
    data class Error(val failure: ReaderOpenFailure) : PdfDocumentInfoState
}

internal sealed interface PdfPageRenderState {
    val cacheKey: String

    data object Empty : PdfPageRenderState {
        override val cacheKey: String = "empty"
    }

    data object Loading : PdfPageRenderState {
        override val cacheKey: String = "loading"
    }

    data class Ready(
        val bitmap: Bitmap,
        val pageIndex: Int,
    ) : PdfPageRenderState {
        override val cacheKey: String = "page:$pageIndex"
        val contentDescription: String = "PDF page ${pageIndex + 1}"
    }

    data class Error(val message: String) : PdfPageRenderState {
        override val cacheKey: String = "error:$message"
    }
}

internal class PdfPreviewBitmapCache(
    private val maxEntries: Int,
) {
    private val lock = Any()
    private var closed = false
    private val cache = object : LinkedHashMap<Int, PdfPageRenderState.Ready>(
        maxEntries,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Int, PdfPageRenderState.Ready>,
        ): Boolean {
            val shouldRemove = size > maxEntries
            if (shouldRemove) {
                eldest.value.recycle()
            }
            return shouldRemove
        }
    }

    fun get(pageIndex: Int): PdfPageRenderState.Ready? = synchronized(lock) {
        if (closed) return@synchronized null
        cache[pageIndex]?.takeUnless { state -> state.bitmap.isRecycled }
    }

    fun put(
        pageIndex: Int,
        state: PdfPageRenderState.Ready,
    ) {
        synchronized(lock) {
            if (closed) {
                state.recycle()
                return@synchronized
            }
            cache.put(pageIndex, state)?.recycle()
        }
    }

    fun clear() {
        synchronized(lock) {
            closed = true
            cache.values.forEach(PdfPageRenderState.Ready::recycle)
            cache.clear()
        }
    }
}

private fun PdfPageRenderState.Ready.recycle() {
    if (!bitmap.isRecycled) {
        bitmap.recycle()
    }
}

private const val PDF_RENDER_LONG_EDGE_PX = 2200
private const val PDF_RENDER_MEDIUM_LONG_EDGE_PX = 3200
private const val PDF_RENDER_MAX_LONG_EDGE_PX = 4096
private const val PDF_RENDER_REQUEST_DEBOUNCE_MILLIS = 180L
