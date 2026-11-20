package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.example.unifiedreader.feature.annotations.PageTextRect
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTextTurnTest {
    private val lines = arrayOf("The quick brown fox", "jumps over the lazy dog")
    private val upright = testPage(*lines)

    @Test
    fun `the way words run is read from their letters`() {
        val turns = (0..3).map { turn -> turnedPage(turn).turn }

        assertEquals(listOf(0, 1, 2, 3), turns)
    }

    @Test
    fun `words running right to left on an upright page are not taken as upside down`() {
        assertEquals(0, turnedPage(2, pageTurn = 0).turn)
        assertEquals(2, turnedPage(2, pageTurn = 2).turn)
    }

    @Test
    fun `a page without two letters side by side takes the page's own turn`() {
        val single = PdfPageText(pageIndex = 0, text = "a", boxes = floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f), aspect = 1.4f, pageTurn = 1)

        assertEquals(1, single.turn)
    }

    @Test
    fun `on a turned page a selection over two lines is one box per line`() {
        val start = upright.text.indexOf("quick")
        val end = upright.text.indexOf("over") + "over".length
        for (turn in 1..3) {
            val rects = turnedPage(turn).rects(start, end)

            assertEquals("turn $turn", 2, rects.size)
            upright.rects(start, end).zip(rects).forEach { (line, turned) -> assertRect(drawn(line, turn), turned) }
        }
    }

    @Test
    fun `a press and a drag on a turned page take the words under the finger`() {
        for (turn in 1..3) {
            val page = turnedPage(turn)
            val selection = pressOn(page, "brown")

            assertEquals("turn $turn", "brown", selection.text)
            assertEquals("turn $turn", "brown fox jumps over the lazy", selection.dragTo(page.text.indexOf("lazy")).text)
        }
    }

    @Test
    fun `on a page turned a quarter the handles hang from the words' true start and end`() {
        val page = turnedPage(1)
        val selection = pressOn(page, "quick").dragTo(page.text.indexOf("over")).withDrag(null)

        val handles = pdfSelectionHandles(selection, Screen)!!

        // Upright, "quick" starts 0.14 across a line whose foot is 0.13 down, and "over" ends 0.199 across a line
        // whose foot is 0.18 down. Turned clockwise, lines run down the page and the next line is to the left.
        assertPoint(Offset(0.87f * Screen.width, 0.14f * Screen.height), handles.start)
        assertPoint(Offset(0.82f * Screen.width, 0.199f * Screen.height), handles.end)
        // Their round parts sit before the start (above it) and after the end (below it), on the next line's side.
        assertPoint(handles.start + Offset(-Radius, -Radius), handles.centre(PdfSelectionDrag.Start, Radius))
        assertPoint(handles.end + Offset(-Radius, Radius), handles.centre(PdfSelectionDrag.End, Radius))
        assertEquals(PdfSelectionDrag.Start, handles.at(handles.centre(PdfSelectionDrag.Start, Radius), Radius, reach = 2 * Radius))
        assertEquals(PdfSelectionDrag.End, handles.at(handles.centre(PdfSelectionDrag.End, Radius), Radius, reach = 2 * Radius))
    }

    @Test
    fun `on an upside down page the handles hang from the words' true start and end`() {
        val page = turnedPage(2)
        val selection = pressOn(page, "quick").dragTo(page.text.indexOf("over")).withDrag(null)

        val handles = pdfSelectionHandles(selection, Screen)!!

        assertPoint(Offset(0.86f * Screen.width, 0.87f * Screen.height), handles.start)
        assertPoint(Offset(0.801f * Screen.width, 0.82f * Screen.height), handles.end)
        assertPoint(handles.start + Offset(Radius, -Radius), handles.centre(PdfSelectionDrag.Start, Radius))
        assertPoint(handles.end + Offset(-Radius, -Radius), handles.centre(PdfSelectionDrag.End, Radius))
    }

    @Test
    fun `the text points a handle drags sit half way across the end lines`() {
        val page = turnedPage(3)
        val selection = pressOn(page, "quick")

        // Turned anticlockwise, lines run up the page and the next line is to the right.
        assertPoint(Offset(0.115f, 0.86f), selection.startPoint!!)
        assertPoint(Offset(0.115f, 1f - 0.189f), selection.endPoint!!)
    }

    @Test
    fun `a line under turned words is drawn as for upright words, then turned about the line's centre`() {
        val shown = Rect(left = 860f, top = 140f, right = 900f, bottom = 330f)

        assertEquals(shown, readingLine(shown, 0))
        assertEquals(shown, readingLine(shown, 2))
        val reading = readingLine(shown, 1)
        assertEquals(shown.center, reading.center)
        assertEquals(shown.height, reading.width, ScreenTolerance)
        assertEquals(shown.width, reading.height, ScreenTolerance)
    }

    /** The test page drawn turned [turn] quarter turns clockwise, with the page's own turn [pageTurn]. */
    private fun turnedPage(turn: Int, pageTurn: Int = turn): PdfPageText {
        val (text, boxes) = testText(*lines)
        for (at in boxes.indices step PdfPageText.BoxSize) {
            if (boxes[at].isNaN()) continue
            val box = drawn(PageTextRect(boxes[at], boxes[at + 1], boxes[at + 2], boxes[at + 3]), turn)
            boxes[at] = box.left
            boxes[at + 1] = box.top
            boxes[at + 2] = box.right
            boxes[at + 3] = box.bottom
        }
        return PdfPageText(pageIndex = 0, text = text, boxes = boxes, aspect = 1.4f, pageTurn = pageTurn)
    }

    private fun pressOn(page: PdfPageText, word: String): PdfTextSelection {
        val index = page.text.indexOf(word)
        val box = page.rects(index, index + 1).single()
        return PdfTextSelection.at(page, Offset((box.left + box.right) / 2f, (box.top + box.bottom) / 2f), reach = 0.05f)!!
    }

    private fun assertRect(expected: PageTextRect, actual: PageTextRect) {
        assertEquals("left", expected.left, actual.left, Tolerance)
        assertEquals("top", expected.top, actual.top, Tolerance)
        assertEquals("right", expected.right, actual.right, Tolerance)
        assertEquals("bottom", expected.bottom, actual.bottom, Tolerance)
    }

    private fun assertPoint(expected: Offset, actual: Offset) {
        assertEquals("x", expected.x, actual.x, ScreenTolerance)
        assertEquals("y", expected.y, actual.y, ScreenTolerance)
    }

    private companion object {
        const val Tolerance = 1e-4f
        const val ScreenTolerance = 0.01f
        const val Radius = 20f
        val Screen = Rect(0f, 0f, 1000f, 1400f)
    }
}
