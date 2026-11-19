package com.example.unifiedreader.feature.reader.pdf

import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.feature.reader.core.ReaderOpenMode
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionSnapshot
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfReaderPagesTest {
    private fun session(vararg preferences: Pair<String, String>) =
        ReaderSessionSnapshot(itemId = "book", preferences = preferences.toMap())

    @Test
    fun `a book opens the way it was left, else the way last picked in any PDF, else turning pages`() {
        assertEquals(ReaderPagesMode.Scroll, restorePdfPagesMode(session(PdfPagesKey to "Scroll"), lastPick = ReaderPagesMode.Turn))
        assertEquals(ReaderPagesMode.Turn, restorePdfPagesMode(session(PdfPagesKey to "Turn"), lastPick = ReaderPagesMode.Scroll))
        assertEquals(ReaderPagesMode.Scroll, restorePdfPagesMode(session(), lastPick = ReaderPagesMode.Scroll))
        assertEquals(ReaderPagesMode.Scroll, restorePdfPagesMode(null, lastPick = ReaderPagesMode.Scroll))
        assertEquals(ReaderPagesMode.Turn, restorePdfPagesMode(null, lastPick = null))
        // A value from a later version falls back the same way.
        assertEquals(ReaderPagesMode.Turn, restorePdfPagesMode(session(PdfPagesKey to "Sideways"), lastPick = null))
    }

    @Test
    fun `the scroll anchor survives the session store`() {
        val anchor = PdfScrollAnchor(pageIndex = 41, offsetRatio = 0.375f)
        assertEquals("41:0.3750", encodePdfScrollAnchor(anchor))
        assertEquals(anchor, decodePdfScrollAnchor(encodePdfScrollAnchor(anchor)))
        assertNull(decodePdfScrollAnchor(null))
        assertNull(decodePdfScrollAnchor("41"))
        assertNull(decodePdfScrollAnchor("-1:0.5"))
        assertNull(decodePdfScrollAnchor("x:0.5"))
        assertEquals(PdfScrollAnchor(3, 1f), decodePdfScrollAnchor("3:7.5"))
    }

    @Test
    fun `scrolling opens at the finer place while it agrees with the saved page`() {
        val anchor = PdfScrollAnchor(pageIndex = 41, offsetRatio = 0.6f)
        // The page in view is 42 when the top of the screen is well down page 41.
        assertEquals(anchor, pdfScrollStart(progressPageIndex = 42, anchor = anchor, pageCount = 500))
        assertEquals(anchor, pdfScrollStart(progressPageIndex = 41, anchor = anchor, pageCount = 500))
    }

    @Test
    fun `a page saved since elsewhere wins over an old anchor`() {
        val anchor = PdfScrollAnchor(pageIndex = 41, offsetRatio = 0.6f)
        // Turned to page 120 in turn-pages mode, or synced from another device.
        assertEquals(PdfScrollAnchor(120, 0f), pdfScrollStart(progressPageIndex = 120, anchor = anchor, pageCount = 500))
        assertEquals(PdfScrollAnchor(9, 0f), pdfScrollStart(progressPageIndex = 9, anchor = null, pageCount = 500))
        // A book that lost pages: inside it.
        assertEquals(PdfScrollAnchor(9, 0f), pdfScrollStart(progressPageIndex = 30, anchor = anchor, pageCount = 10))
    }

    @Test
    fun `the place is the same page in both ways of reading`() {
        val place = PdfReaderPlace()
        place.restore(page = 12)

        // Turning pages.
        assertTrue(place.goTo(13, pageCount = 50))
        assertTrue(place.userNavigated)
        assertFalse(place.goTo(13, pageCount = 50))
        assertEquals(13, place.pageIndex)

        // Switching to Scroll keeps page 13; the strip then reports the page across the middle.
        place.follow(page = 14, byReader = true)
        assertEquals(14, place.pageIndex)
        assertEquals(15f / 50f, place.progress(pageCount = 50))
        // Back to turning pages: still page 14.
        assertTrue(place.goTo(15, pageCount = 50))
        assertEquals(15, place.pageIndex)
    }

    @Test
    fun `the book settling where it was is not the reader moving`() {
        val place = PdfReaderPlace()
        place.restore(page = 30)
        place.follow(page = 30, byReader = false)

        assertFalse(place.userNavigated)
        assertFalse(place.shouldPersist)

        place.follow(page = 31, byReader = true)
        assertTrue(place.shouldPersist)
    }

    @Test
    fun `a saved page opens there, except from the beginning or when it was finished`() {
        val saved = ReaderProgressSnapshot(itemId = "book", locatorType = LocatorType.PAGE, page = 40)
        assertEquals(40, initialPdfPageIndex(saved, pageCount = 500, openMode = ReaderOpenMode.Default))
        assertEquals(0, initialPdfPageIndex(saved, pageCount = 500, openMode = ReaderOpenMode.StartFromBeginning))
        assertEquals(0, initialPdfPageIndex(saved.copy(isFinished = true), pageCount = 500, openMode = ReaderOpenMode.Default))
        assertEquals(9, initialPdfPageIndex(saved, pageCount = 10, openMode = ReaderOpenMode.Default))
    }
}
