package com.example.unifiedreader.feature.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfScrollZoomMathTest {
    /** A list that can move [room] pixels either way; records how far it was asked to move. */
    private class FakeList(var room: Float = Float.MAX_VALUE) {
        var moved = 0f

        fun scroll(delta: Float): Float {
            val taken = delta.coerceIn(-room, room)
            moved += taken
            return taken
        }
    }

    @Test
    fun `a zoomed drag mid-book scrolls the list by the finger over the zoom`() {
        val list = FakeList()
        val offset = pdfZoomedVerticalPan(dy = -200f, scale = 2f, offsetY = 0f, maxOffsetY = 500f, scrollList = list::scroll)

        assertEquals(100f, list.moved)
        assertEquals(0f, offset)
    }

    @Test
    fun `at the top of the book the zoomed view moves by its offset to reach the page's top`() {
        val list = FakeList(room = 0f)
        val offset = pdfZoomedVerticalPan(dy = 300f, scale = 2f, offsetY = 0f, maxOffsetY = 500f, scrollList = list::scroll)

        assertEquals(300f, offset)
        assertEquals(500f, pdfZoomedVerticalPan(dy = 900f, scale = 2f, offsetY = 300f, maxOffsetY = 500f, scrollList = list::scroll))
    }

    @Test
    fun `going back the other way gives up the offset before the list moves`() {
        val list = FakeList()
        val offset = pdfZoomedVerticalPan(dy = -400f, scale = 2f, offsetY = 300f, maxOffsetY = 500f, scrollList = list::scroll)

        assertEquals(0f, offset)
        // 300 from the offset, the other 100 finger pixels are 50 list pixels at twice the size.
        assertEquals(50f, list.moved)
    }

    @Test
    fun `a pinch keeps the point under the fingers still`() {
        // A point 100 right of the centre, zooming from 1 to 2: the content moves 100 left.
        assertEquals(-100f, pdfFocalOffset(offset = 0f, oldScale = 1f, newScale = 2f, focal = 100f))
        // Zooming back out returns it.
        assertEquals(0f, pdfFocalOffset(offset = -100f, oldScale = 2f, newScale = 1f, focal = 100f))
    }

    @Test
    fun `a zoomed view moves at most half its extra size from the centre`() {
        assertEquals(540f, pdfMaxZoomOffset(viewportPx = 1080, scale = 2f))
        assertEquals(0f, pdfMaxZoomOffset(viewportPx = 1080, scale = 1f))
        assertEquals(0f, pdfMaxZoomOffset(viewportPx = 1080, scale = 0.5f))
    }
}
