package com.example.unifiedreader.feature.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageTextTest {
    @Test
    fun `a selection over two lines is one box per line, each as tall as its line`() {
        val page = testPage("The quick fox", "jumps over it")
        val start = page.text.indexOf("fox")
        val end = page.text.indexOf("jumps") + "jumps".length

        val rects = page.rects(start, end)

        assertEquals(2, rects.size)
        assertEquals(lineBand(0), rects[0].top to rects[0].bottom)
        assertEquals(lineBand(1), rects[1].top to rects[1].bottom)
        assertEquals(charLeft(page, start), rects[0].left, 0f)
        assertEquals(charLeft(page, page.text.indexOf("jumps")), rects[1].left, 0f)
    }

    @Test
    fun `a short word gets its line's height, not its letters'`() {
        val page = testPage("a lot")

        val rect = page.rects(0, 1).single()

        assertEquals(lineBand(0), rect.top to rect.bottom)
    }

    @Test
    fun `a raised footnote number stays on its line`() {
        val page = testPage("word1 more", raised = setOf(4))

        assertEquals(1, page.rects(0, page.length).size)
    }

    @Test
    fun `the nearest character is the one under the finger, never a space`() {
        val page = testPage("The quick fox")
        val q = page.text.indexOf('q')

        assertEquals(q, page.charNear(centreX(page, q), LineTop + 0.01f))
        val space = page.text.indexOf(' ')
        val near = page.charNear(centreX(page, space), LineTop + 0.01f)!!
        assertFalse(page.text[near].isWhitespace())
    }

    @Test
    fun `a press far from any text finds none`() {
        val page = testPage("The quick fox")

        assertNull(page.charNear(0.9f, 0.9f, maxDistance = 0.02f))
        assertTrue(page.charNear(0.9f, 0.9f) != null)
    }

    @Test
    fun `a press between two lines takes the nearer line`() {
        val page = testPage("upper line", "lower line")
        val gapNearLower = lineBand(1).first - 0.002f

        val index = page.charNear(0.11f, gapNearLower)!!

        assertTrue(index > page.text.indexOf("lower") - 1)
    }

    @Test
    fun `words hold their apostrophes and hyphens, and a mark stands alone`() {
        val page = testPage("don't well-known, 42.")

        assertEquals("don't", page.word(page.text.indexOf('o')))
        assertEquals("well-known", page.word(page.text.indexOf('k')))
        assertEquals(",", page.word(page.text.indexOf(',')))
        assertEquals("42", page.word(page.text.indexOf('4')))
    }

    @Test
    fun `copied text joins lines, and a word broken by a hyphen is whole again`() {
        assertEquals("one two", cleanPdfText("one\r\ntwo"))
        assertEquals("information", cleanPdfText("infor\u0002\r\nmation"))
        assertEquals("information", cleanPdfText("infor\u00AD\r\nmation"))
        assertEquals("well-known", cleanPdfText("well-\r\nknown"))
        assertEquals("a b", cleanPdfText("  a \t  b \r\n"))
    }

    @Test
    fun `characters PDFium made up have no box and never count`() {
        val page = testPage("ab", "cd")
        val breakIndex = page.text.indexOf('\r')

        assertFalse(page.hasBox(breakIndex))
        assertEquals(2, page.rects(0, page.length).size)
    }

    private fun PdfPageText.word(index: Int): String = wordAt(index).let { text.substring(it.first, it.last + 1) }

    private fun charLeft(page: PdfPageText, index: Int): Float = page.rects(index, index + 1).single().left

    private fun centreX(page: PdfPageText, index: Int): Float =
        page.rects(index, index + 1).single().let { (it.left + it.right) / 2f }
}

internal const val LineTop = 0.1f
internal const val LineStep = 0.05f
internal const val LineHeight = 0.03f
internal const val CharWidth = 0.01f

/** Where line [line] of a [testPage] reaches: from its tallest letter's top to its lowest letter's bottom. */
internal fun lineBand(line: Int): Pair<Float, Float> = (LineTop + line * LineStep) to (LineTop + line * LineStep + LineHeight)

/**
 * A page of [lines], one character every [CharWidth] across from 0.1, lines [LineStep] apart. Letters without
 * ascenders sit lower, as real glyph boxes do; PDFium's line breaks (\r\n) have no box. Characters at [raised]
 * sit half a line up, like a footnote number.
 */
internal fun testPage(vararg lines: String, raised: Set<Int> = emptySet()): PdfPageText {
    val text = StringBuilder()
    val boxes = ArrayList<Float>()
    lines.forEachIndexed { lineIndex, line ->
        if (lineIndex > 0) {
            text.append("\r\n")
            repeat(2 * PdfPageText.BoxSize) { boxes += Float.NaN }
        }
        val top = LineTop + lineIndex * LineStep
        line.forEachIndexed { column, char ->
            val short = char in "acemnorsuvwxz"
            val lift = if (text.length in raised) LineHeight / 2f else 0f
            val left = 0.1f + column * CharWidth
            boxes += listOf(left, (if (short) top + 0.01f else top) - lift, left + CharWidth * 0.9f, top + LineHeight - lift)
            text.append(char)
        }
    }
    return PdfPageText(pageIndex = 0, text = text.toString(), boxes = boxes.toFloatArray(), aspect = 1.4f)
}
