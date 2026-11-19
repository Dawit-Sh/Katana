package com.example.unifiedreader.feature.reader.pdf

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The arithmetic of the scrolling page view, kept apart from Compose so it can be tested: how tall a
 * page is, how wide to draw it, how much the drawn pages may take, which page is in view, and how a
 * zoomed drag splits between the list and the zoom's own offset.
 */

/** A page a letter-sized page wide is this many times as tall; used until a page's own size is known. */
internal const val PdfDefaultPageAspect = 612f / 792f

/** A page's height in pixels when it is [widthPx] wide and its width over height is [aspect]. */
internal fun pdfPageHeightPx(widthPx: Int, aspect: Float): Int {
    val safeAspect = aspect.takeIf { it.isFinite() && it > 0f } ?: PdfDefaultPageAspect
    return (widthPx / safeAspect).roundToInt().coerceAtLeast(1)
}

/**
 * The zoom a page is drawn for: the steps 1, 1.5, 2, 3 and 4, the next one up from [scale], so a pinch
 * redraws a page a few times at most instead of at every step of the fingers.
 */
internal fun pdfZoomStep(scale: Float): Float = PdfZoomSteps.firstOrNull { step -> scale <= step + PdfZoomStepSlack } ?: PdfZoomSteps.last()

/**
 * How wide to draw a page laid out [layoutWidthPx] wide at [scale]: as wide as it shows, at the zoom step
 * above [scale], and never more than [maxPagePixels] pixels in all (the page's [aspect] says how tall).
 */
internal fun pdfRenderWidthPx(
    layoutWidthPx: Int,
    scale: Float,
    aspect: Float,
    maxPagePixels: Long,
): Int {
    val wanted = (layoutWidthPx.coerceAtLeast(0) * pdfZoomStep(scale)).roundToInt()
    val wantedPixels = wanted.toLong() * pdfPageHeightPx(wanted, aspect)
    return when {
        layoutWidthPx <= 0 -> 0
        wantedPixels <= maxPagePixels -> wanted
        // Smaller by the square root of how far over it is, but never under the width it shows at.
        else -> max(layoutWidthPx, (wanted * sqrt(maxPagePixels.toDouble() / wantedPixels)).toInt())
    }
}

/**
 * How many bytes of drawn pages the scrolling view keeps: room for six screens' worth, within an eighth
 * of what Android lets the app use ([memoryClassMb]) and between 16 and 64 MB. On the Fire 7 (600 by 1024,
 * a 128 MB class) that is 16 MB; on a 1080 by 2424 phone with a 256 MB class, 32 MB.
 */
internal fun pdfPageCacheBudgetBytes(
    screenWidthPx: Int,
    screenHeightPx: Int,
    memoryClassMb: Int,
): Long {
    val screens = screenWidthPx.toLong() * screenHeightPx * BytesPerPixel * PdfCacheScreens
    val share = memoryClassMb.toLong() * BytesPerMegabyte / PdfCacheMemoryShare
    return min(screens, share).coerceIn(PdfCacheMinBytes, PdfCacheMaxBytes)
}

/**
 * The largest page drawn for zoom: half the cache. Zoomed in, a page or two is on screen; the neighbours
 * drawn at the plain width make room and are drawn again when the zoom comes back out.
 */
internal fun pdfMaxPagePixels(budgetBytes: Long): Long = budgetBytes / BytesPerPixel / PdfZoomedPageShare

/** One page of the scrolling list as laid out: its index, its top and its height, in list pixels. */
internal data class PdfVisiblePage(
    val pageIndex: Int,
    val top: Int,
    val height: Int,
)

/**
 * The page in view: the one across the line [focusY] (list pixels from the viewport's top, the middle of
 * the screen), or the nearest one when the line falls in a gap. Null with nothing laid out.
 */
internal fun pdfPageInView(
    visiblePages: List<PdfVisiblePage>,
    focusY: Float,
): Int? {
    val across = visiblePages.firstOrNull { page -> focusY >= page.top && focusY < page.top + page.height }
    val nearest = across ?: visiblePages.minByOrNull { page -> kotlin.math.abs(page.top + page.height / 2f - focusY) }
    return nearest?.pageIndex
}

/** The page at the top of the viewport and how far into it the top edge is, for reopening the book there. */
internal fun pdfTopAnchor(visiblePages: List<PdfVisiblePage>): PdfScrollAnchor? {
    val top = visiblePages.firstOrNull { page -> page.top + page.height > 0 } ?: return null
    val ratio = if (top.height > 0) (-top.top).coerceAtLeast(0) / top.height.toFloat() else 0f
    return PdfScrollAnchor(pageIndex = top.pageIndex, offsetRatio = ratio.coerceIn(0f, 1f))
}

/**
 * The pages to draw ahead of the reader once the view settles on [pageInView]: the next two, then the one
 * before, inside the book.
 */
internal fun pdfPrefetchPages(pageInView: Int, pageCount: Int): List<Int> =
    listOf(pageInView + 1, pageInView + 2, pageInView - 1).filter { it in 0 until pageCount }

private val PdfZoomSteps = listOf(1f, 1.5f, 2f, 3f, 4f)
private const val PdfZoomStepSlack = 0.05f
private const val BytesPerPixel = 4L
private const val BytesPerMegabyte = 1024L * 1024L
private const val PdfCacheScreens = 6L
private const val PdfCacheMemoryShare = 8L
private const val PdfCacheMinBytes = 16L * 1024L * 1024L
private const val PdfCacheMaxBytes = 64L * 1024L * 1024L
private const val PdfZoomedPageShare = 2L
