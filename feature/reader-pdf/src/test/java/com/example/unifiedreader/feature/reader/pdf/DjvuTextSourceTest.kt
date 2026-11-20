package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.ui.geometry.Offset
import com.example.unifiedreader.core.djvu.DjvuPageText
import com.example.unifiedreader.core.djvu.DjvuTextWord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DjvuTextSourceTest {
    // A 1000 by 1600 pixel page: "Alice was" on one line, "beginning to" on the next (DjVu counts y up).
    private val words = listOf(
        DjvuTextWord("Alice", line = 0, left = 100, bottom = 1400, right = 300, top = 1450),
        DjvuTextWord("was", line = 0, left = 340, bottom = 1400, right = 460, top = 1440),
        DjvuTextWord("beginning", line = 1, left = 100, bottom = 1320, right = 460, top = 1370),
        DjvuTextWord("to", line = 1, left = 500, bottom = 1320, right = 580, top = 1360),
    )

    @Test
    fun `a DjVu page's words read as lines, each letter on its share of the word`() {
        val page = djvuPdfPageText(pageIndex = 4, DjvuPageText(1000, 1600, 0, words))!!

        assertEquals("Alice was\nbeginning to", page.text)
        assertEquals(4, page.pageIndex)
        assertEquals(1.6f, page.aspect, Tolerance)
        assertEquals(0, page.turn)
        val alice = page.rects(0, 5).single()
        assertEquals(0.1f, alice.left, Tolerance)
        assertEquals(0.3f, alice.right, Tolerance)
        assertEquals(1f - 1450f / 1600f, alice.top, Tolerance)
        assertEquals(1f - 1400f / 1600f, alice.bottom, Tolerance)
        val firstLetter = page.rects(0, 1).single()
        assertEquals(0.14f, firstLetter.right, Tolerance)
    }

    @Test
    fun `a press selects a word and a drag goes on to the next line`() {
        val page = djvuPdfPageText(pageIndex = 0, DjvuPageText(1000, 1600, 0, words))!!

        val selection = PdfTextSelection.at(page, Offset(0.4f, 1f - 1420f / 1600f), reach = 0.02f)!!

        assertEquals("was", selection.text)
        val longer = selection.dragTo(page.text.indexOf("beginning") + 2)
        assertEquals("was beginning", longer.text)
        assertEquals(2, longer.rects.size)
    }

    @Test
    fun `a page DjVu turns a quarter anticlockwise has its words running up`() {
        val page = djvuPdfPageText(pageIndex = 0, DjvuPageText(1000, 1600, 1, words))!!

        assertEquals(3, page.turn)
        assertEquals(1000f / 1600f, page.aspect, Tolerance)
        // Shown turned, "Alice" sits near the left edge, low on the page, reading upwards.
        val alice = page.rects(0, 5).single()
        assertEquals(1f - 1450f / 1600f, alice.left, Tolerance)
        assertEquals(0.7f, alice.top, Tolerance)
        assertEquals(0.9f, alice.bottom, Tolerance)
    }

    @Test
    fun `a page without words has no text`() {
        assertNull(djvuPdfPageText(0, DjvuPageText(1000, 1600, 0, emptyList())))
    }

    @Test
    fun `the source keeps what it read, gives nothing after closing, and never throws`() = runBlocking {
        var reads = 0
        val source = DjvuTextSource { pageIndex ->
            reads++
            if (pageIndex == 1) error("damaged page") else DjvuPageText(1000, 1600, 0, words)
        }

        assertEquals("Alice was\nbeginning to", source.page(0)?.text)
        source.page(0)
        assertEquals(1, reads)
        assertNull(source.page(1))
        source.close()
        assertNull(source.page(2))
        assertNull(source.cached(0))
    }

    private companion object {
        const val Tolerance = 1e-4f
    }
}
