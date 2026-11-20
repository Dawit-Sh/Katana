package com.example.unifiedreader.feature.annotations

import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * Highlights on books read as page images with a text layer (PDF). Their place is the page, as a page
 * bookmark's is ("page:N" as the href, the 0-based page in the locator, so the Bookmarks page opens them the
 * same way), plus the selected characters of the page's text ([PageTextSpot.start] to [PageTextSpot.end]) and
 * where they sit on the page: one box per line, each side a fraction (0..1) of the page's width or height,
 * from its top left corner. The boxes draw the highlight again at any zoom without reading the text.
 */

/** One line of a highlight on its page: each side a fraction of the page's width or height, from the top left. */
data class PageTextRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float, slop: Float = 0f): Boolean =
        x >= left - slop && x <= right + slop && y >= top - slop && y <= bottom + slop
}

/** Where a highlight sits: page [pageIndex] (0-based), its characters [start] up to [end] (not included), and its lines. */
data class PageTextSpot(
    val pageIndex: Int,
    val start: Int,
    val end: Int,
    val rects: List<PageTextRect>,
)

/**
 * The place of a highlight at [spot], on a book of [pageCount] pages whose page has [pageCharCount] characters.
 * Highlights keep reading order by page, then by where on the page their words start.
 */
fun pageHighlightPlace(spot: PageTextSpot, pageCount: Int, pageCharCount: Int): AnnotationPlace {
    val withinPage = if (pageCharCount > 0) spot.start.toDouble() / pageCharCount else 0.0
    return AnnotationPlace(
        href = pageHref(spot.pageIndex),
        locatorJson = JSONObject()
            .put(PageKey, spot.pageIndex)
            .put(StartKey, spot.start)
            .put(EndKey, spot.end)
            .put(RectsKey, JSONArray().apply { spot.rects.forEach { rect -> put(rect.toJson()) } })
            .toString(),
        chapterTitle = pageTitle(spot.pageIndex),
        progression = if (pageCount > 0) ((spot.pageIndex + withinPage.coerceIn(0.0, 1.0)) / pageCount).coerceIn(0.0, 1.0) else 0.0,
    )
}

/** Where a page highlight's locator puts it, or null for any other place (an EPUB's, or a page bookmark's). */
fun pageTextSpotOf(locatorJson: String): PageTextSpot? = try {
    val json = JSONObject(locatorJson)
    if (!json.has(PageKey) || !json.has(RectsKey)) {
        null
    } else {
        val rects = json.getJSONArray(RectsKey)
        PageTextSpot(
            pageIndex = json.getInt(PageKey),
            start = json.optInt(StartKey, 0),
            end = json.optInt(EndKey, 0),
            rects = List(rects.length()) { index -> rects.getJSONArray(index).toRect() },
        ).takeIf { it.pageIndex >= 0 && it.rects.isNotEmpty() }
    }
} catch (_: JSONException) {
    null
}

/** What a page highlight's place calls its page, as the Bookmarks page and the export show it. */
fun pageTitle(pageIndex: Int): String = "Page ${pageIndex + 1}"

private fun pageHref(pageIndex: Int): String = "page:$pageIndex"

private fun PageTextRect.toJson(): JSONArray =
    JSONArray().put(left.rounded()).put(top.rounded()).put(right.rounded()).put(bottom.rounded())

private fun JSONArray.toRect(): PageTextRect = PageTextRect(
    left = getDouble(RectLeft).toFloat(),
    top = getDouble(RectTop).toFloat(),
    right = getDouble(RectRight).toFloat(),
    bottom = getDouble(RectBottom).toFloat(),
)

/** Five decimal places: far finer than a pixel on any screen, and the stored place stays short. */
private fun Float.rounded(): Double = (this * RectPrecision).roundToInt() / RectPrecision

private const val RectPrecision = 100_000.0
private const val RectLeft = 0
private const val RectTop = 1
private const val RectRight = 2
private const val RectBottom = 3
private const val PageKey = "page"
private const val StartKey = "start"
private const val EndKey = "end"
private const val RectsKey = "rects"
