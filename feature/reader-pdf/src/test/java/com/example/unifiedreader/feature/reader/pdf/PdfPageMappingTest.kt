package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.example.unifiedreader.feature.annotations.PageTextRect
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDFium places points the way PdfRenderer draws the page; these stand in for it with the placement PDF's
 * page rules give (the crop box, then /Rotate turning the page clockwise), at the size [PdfPageMapping] asks for.
 */
class PdfPageMappingTest {
    // A 600 by 800 point page whose crop box starts at (50, 40).
    private val cropLeft = 50.0
    private val cropBottom = 40.0
    private val width = 600.0
    private val height = 800.0

    @Test
    fun `an upright page's box lands where the page shows it`() {
        val mapping = PdfPageMapping.of(placer(rotation = 0))

        // Words 60 points from the crop box's left, 100 points below its top.
        val box = mapped(mapping, left = 110f, top = 740f, right = 170f, bottom = 720f)

        assertBox(PageTextRect(0.1f, 0.125f, 0.2f, 0.15f), box)
    }

    @Test
    fun `a page turned a quarter keeps its words under the finger`() {
        val mapping = PdfPageMapping.of(placer(rotation = 90))

        val box = mapped(mapping, left = 110f, top = 740f, right = 170f, bottom = 720f)

        // Turned clockwise, the page is 800 wide and 600 tall: its old bottom edge is now its left edge.
        assertBox(PageTextRect(0.85f, 0.1f, 0.875f, 0.2f), box)
    }

    @Test
    fun `half and three quarter turns`() {
        val upsideDown = mapped(PdfPageMapping.of(placer(rotation = 180)), 110f, 740f, 170f, 720f)
        val threeQuarters = mapped(PdfPageMapping.of(placer(rotation = 270)), 110f, 740f, 170f, 720f)

        assertBox(PageTextRect(0.8f, 0.85f, 0.9f, 0.875f), upsideDown)
        assertBox(PageTextRect(0.125f, 0.8f, 0.15f, 0.9f), threeQuarters)
    }

    @Test
    fun `a character with no box has none`() {
        val mapping = PdfPageMapping.of(placer(rotation = 0))
        val into = FloatArray(PdfPageText.BoxSize)

        mapping.writeBox(FloatArray(PdfPageText.BoxSize), into, 0)

        assertTrue(into.all(Float::isNaN))
    }

    @Test
    fun `a page fitted, zoomed and moved shows each fraction where the finger finds it again`() {
        val viewport = IntSize(1000, 1600)
        val content = Size(900f, 1200f)
        val fit = pdfTurnPageBounds(viewport, content, scale = 1f, offset = Offset.Zero)
        assertEquals(Rect(50f, 200f, 950f, 1400f), fit)
        assertEquals(Offset(500f, 800f), pageToScreen(fit, 0.5f, 0.5f))

        val zoomed = pdfTurnPageBounds(viewport, content, scale = 2f, offset = Offset(100f, -50f))
        assertEquals(Rect(-300f, -450f, 1500f, 1950f), zoomed)

        listOf(fit, zoomed).forEach { page ->
            val point = Offset(0.37f, 0.81f)
            val back = screenToPage(page, pageToScreen(page, point.x, point.y))
            assertEquals(point.x, back.x, Tolerance)
            assertEquals(point.y, back.y, Tolerance)
        }
    }

    @Test
    fun `a highlight's line covers the same words at any zoom`() {
        val line = PageTextRect(0.1f, 0.125f, 0.2f, 0.15f)
        val viewport = IntSize(1000, 1600)
        val content = Size(900f, 1200f)

        listOf(1f to Offset.Zero, 2.5f to Offset(-300f, 420f), 5f to Offset(1200f, -900f)).forEach { (scale, offset) ->
            val page = pdfTurnPageBounds(viewport, content, scale, offset)
            val onScreen = pageRectToScreen(page, line)
            val corner = screenToPage(page, onScreen.topLeft)
            val far = screenToPage(page, onScreen.bottomRight)
            assertEquals(line.left, corner.x, Tolerance)
            assertEquals(line.top, corner.y, Tolerance)
            assertEquals(line.right, far.x, Tolerance)
            assertEquals(line.bottom, far.y, Tolerance)
            assertEquals(content.width * scale * line.width, onScreen.width, 0.01f)
        }
    }

    /** Where a point of page space shows on the drawn page, as PDFium says, in whole pixels of a [size] page. */
    private fun placer(rotation: Int, size: Int = PdfPageMapping.DeviceSize): (Double, Double) -> Pair<Int, Int> = { x, y ->
        // Fractions of the upright page, from its top left.
        val u = (x - cropLeft) / width
        val v = (cropBottom + height - y) / height
        val (fx, fy) = when (rotation) {
            90 -> (1 - v) to u
            180 -> (1 - u) to (1 - v)
            270 -> v to (1 - u)
            else -> u to v
        }
        (fx * size).roundToInt() to (fy * size).roundToInt()
    }

    private fun mapped(mapping: PdfPageMapping, left: Float, top: Float, right: Float, bottom: Float): PageTextRect {
        val into = FloatArray(PdfPageText.BoxSize)
        mapping.writeBox(floatArrayOf(left, top, right, bottom), into, 0)
        return PageTextRect(into[PdfPageText.Left], into[PdfPageText.Top], into[PdfPageText.Right], into[PdfPageText.Bottom])
    }

    private fun assertBox(expected: PageTextRect, actual: PageTextRect) {
        assertEquals("left", expected.left, actual.left, Tolerance)
        assertEquals("top", expected.top, actual.top, Tolerance)
        assertEquals("right", expected.right, actual.right, Tolerance)
        assertEquals("bottom", expected.bottom, actual.bottom, Tolerance)
    }

    private companion object {
        const val Tolerance = 0.0001f
    }
}
