package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfTextSelectionTest {
    private val page = testPage("The quick brown fox", "jumps over the lazy dog")

    @Test
    fun `a long press selects the word under it`() {
        val selection = pressOn("quick")

        assertEquals("quick", selection.text)
        assertEquals(PdfSelectionDrag.Words, selection.dragging)
    }

    @Test
    fun `a long press on a picture or a page without text selects nothing`() {
        assertNull(PdfTextSelection.at(page, Offset(0.9f, 0.9f), reach = 0.02f))
        val empty = PdfPageText(pageIndex = 0, text = "", boxes = FloatArray(0), aspect = 1.4f)
        assertNull(PdfTextSelection.at(empty, Offset(0.5f, 0.5f), reach = 1f))
    }

    @Test
    fun `dragging on adds whole words, onto the next line too`() {
        val selection = pressOn("quick").dragTo(page.text.indexOf("over") + 1)

        assertEquals("quick brown fox jumps over", selection.text)
        assertEquals(2, selection.rects.size)
    }

    @Test
    fun `dragging back keeps the pressed word and adds the words before it`() {
        val selection = pressOn("brown").dragTo(page.text.indexOf("The"))

        assertEquals("The quick brown", selection.text)
    }

    @Test
    fun `coming back to the pressed word leaves just it`() {
        val selection = pressOn("brown").dragTo(page.text.indexOf("fox")).dragTo(page.text.indexOf("brown") + 2)

        assertEquals("brown", selection.text)
    }

    @Test
    fun `a handle moves its own end, a character at a time, never past the other`() {
        val resting = pressOn("brown").withDrag(null)

        val longer = resting.withDrag(PdfSelectionDrag.End).dragTo(page.text.indexOf("fox") + 1)
        assertEquals("brown fo", longer.text)

        val shorter = longer.withDrag(PdfSelectionDrag.Start).dragTo(page.text.indexOf("own"))
        assertEquals("own fo", shorter.text)

        val crossed = shorter.withDrag(PdfSelectionDrag.Start).dragTo(page.length - 1)
        assertEquals(shorter.end - 1, crossed.start)
    }

    @Test
    fun `the handles hang under the first line's start and the last line's end`() {
        val selection = pressOn("fox").dragTo(page.text.indexOf("jumps")).withDrag(null)
        val screen = Rect(0f, 0f, 1000f, 1400f)

        val handles = pdfSelectionHandles(selection, screen)!!
        val first = selection.rects.first()
        val last = selection.rects.last()
        assertEquals(Offset(first.left * 1000f, first.bottom * 1400f), handles.start)
        assertEquals(Offset(last.right * 1000f, last.bottom * 1400f), handles.end)
        assertEquals(PdfSelectionDrag.Start, handles.at(handles.start + Offset(-20f, 25f), radius = 20f, reach = 40f))
        assertEquals(PdfSelectionDrag.End, handles.at(handles.end + Offset(20f, 25f), radius = 20f, reach = 40f))
        assertNull(handles.at(Offset(500f, 1300f), radius = 20f, reach = 40f))
    }

    private fun pressOn(word: String): PdfTextSelection {
        val index = page.text.indexOf(word)
        val box = page.rects(index, index + 1).single()
        return PdfTextSelection.at(page, Offset((box.left + box.right) / 2f, (box.top + box.bottom) / 2f), reach = 0.05f)!!
    }
}
