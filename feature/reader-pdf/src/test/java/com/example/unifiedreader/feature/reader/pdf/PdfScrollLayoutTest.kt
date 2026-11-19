package com.example.unifiedreader.feature.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfScrollLayoutTest {
    private val letter = 612f / 792f

    @Test
    fun `a page is as tall as its shape says at the width it is laid out`() {
        assertEquals(1398, pdfPageHeightPx(widthPx = 1080, aspect = letter))
        assertEquals(1000, pdfPageHeightPx(widthPx = 2000, aspect = 2f))
        // An unknown or broken shape lays the page out as a letter page.
        assertEquals(pdfPageHeightPx(1080, letter), pdfPageHeightPx(1080, Float.NaN))
        assertEquals(pdfPageHeightPx(1080, letter), pdfPageHeightPx(1080, 0f))
    }

    @Test
    fun `pages are drawn at the width they show, and at the next zoom step when zoomed`() {
        val plenty = Long.MAX_VALUE
        assertEquals(1080, pdfRenderWidthPx(layoutWidthPx = 1080, scale = 1f, aspect = letter, maxPagePixels = plenty))
        assertEquals(1620, pdfRenderWidthPx(layoutWidthPx = 1080, scale = 1.3f, aspect = letter, maxPagePixels = plenty))
        assertEquals(2160, pdfRenderWidthPx(layoutWidthPx = 1080, scale = 2f, aspect = letter, maxPagePixels = plenty))
        assertEquals(4320, pdfRenderWidthPx(layoutWidthPx = 1080, scale = 4f, aspect = letter, maxPagePixels = plenty))
        assertEquals(0, pdfRenderWidthPx(layoutWidthPx = 0, scale = 2f, aspect = letter, maxPagePixels = plenty))
    }

    @Test
    fun `a zoomed page stays within its pixel budget but never under the width it shows at`() {
        val budget = 2_000_000L
        val width = pdfRenderWidthPx(layoutWidthPx = 1080, scale = 3f, aspect = letter, maxPagePixels = budget)
        val pixels = width.toLong() * pdfPageHeightPx(width, letter)

        assertTrue(pixels <= budget)
        assertTrue(width > 1080)
        assertEquals(1080, pdfRenderWidthPx(layoutWidthPx = 1080, scale = 3f, aspect = letter, maxPagePixels = 1000L))
    }

    @Test
    fun `zoom steps keep a pinch from redrawing at every step of the fingers`() {
        assertEquals(1f, pdfZoomStep(1.02f))
        assertEquals(1.5f, pdfZoomStep(1.3f))
        assertEquals(2f, pdfZoomStep(1.9f))
        assertEquals(3f, pdfZoomStep(2.5f))
        assertEquals(4f, pdfZoomStep(3.9f))
        assertEquals(4f, pdfZoomStep(9f))
    }

    @Test
    fun `the cache budget fits the device`() {
        val mb = 1024L * 1024L
        // The Fire 7: 600 by 1024, a 128 MB class.
        assertEquals(16L * mb, pdfPageCacheBudgetBytes(600, 1024, 128))
        // A 1080 by 2424 phone with a 256 MB class gets an eighth of it.
        assertEquals(32L * mb, pdfPageCacheBudgetBytes(1080, 2424, 256))
        // Never more than 64 MB.
        assertEquals(64L * mb, pdfPageCacheBudgetBytes(2560, 1600, 1024))
        // Half of it is the most one zoomed page may take.
        assertEquals(16L * mb / 4 / 2, pdfMaxPagePixels(16L * mb))
    }

    @Test
    fun `the page in view is the one across the middle of the screen`() {
        val pages = listOf(
            PdfVisiblePage(pageIndex = 4, top = -900, height = 1400),
            PdfVisiblePage(pageIndex = 5, top = 506, height = 1400),
        )
        assertEquals(5, pdfPageInView(pages, focusY = 1200f))
        assertEquals(4, pdfPageInView(pages, focusY = 400f))
        // In the gap between two pages, the nearer one.
        assertEquals(5, pdfPageInView(pages, focusY = 505f))
        assertNull(pdfPageInView(emptyList(), focusY = 100f))
    }

    @Test
    fun `the top anchor is the first page reaching into the screen and how far down it is`() {
        val pages = listOf(
            PdfVisiblePage(pageIndex = 2, top = -1400, height = 1400),
            PdfVisiblePage(pageIndex = 3, top = -350, height = 1400),
        )
        assertEquals(PdfScrollAnchor(pageIndex = 3, offsetRatio = 0.25f), pdfTopAnchor(pages))
        assertNull(pdfTopAnchor(emptyList()))
    }

    @Test
    fun `the pages drawn ahead are the next two and the one before, inside the book`() {
        assertEquals(listOf(11, 12, 9), pdfPrefetchPages(pageInView = 10, pageCount = 500))
        assertEquals(listOf(1, 2), pdfPrefetchPages(pageInView = 0, pageCount = 500))
        assertEquals(listOf(498), pdfPrefetchPages(pageInView = 499, pageCount = 500))
    }

    @Test
    fun `only a touch pull left over at the end finishes the book`() {
        assertTrue(pdfPullsPastEnd(canScrollForward = false, availableY = -40f, byTouch = true))
        assertFalse(pdfPullsPastEnd(canScrollForward = true, availableY = -40f, byTouch = true))
        assertFalse(pdfPullsPastEnd(canScrollForward = false, availableY = -10f, byTouch = true))
        assertFalse(pdfPullsPastEnd(canScrollForward = false, availableY = -40f, byTouch = false))
        assertFalse(pdfPullsPastEnd(canScrollForward = false, availableY = 40f, byTouch = true))
    }
}
