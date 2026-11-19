package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.example.unifiedreader.feature.annotations.PageTextRect
import kotlin.math.max
import kotlin.math.min

/**
 * From a PDF page's own space (points, y going up, the page maybe cropped and turned by its /Rotate) to fractions
 * of the page as it is drawn (0..1 from the top left corner, x across, y down). PDFium says where three points
 * land on the drawn page ([of]); PdfRenderer draws a page with that same placement, so a box mapped here sits on
 * the drawn words at any size, and a turned page's boxes turn with it.
 */
internal class PdfPageMapping private constructor(
    private val originX: Double,
    private val originY: Double,
    private val xFromX: Double,
    private val yFromX: Double,
    private val xFromY: Double,
    private val yFromY: Double,
) {
    /** Where the page-space point ([x], [y]) is on the drawn page, as fractions. */
    fun map(x: Double, y: Double): Offset = Offset(
        x = (originX + xFromX * x + xFromY * y).toFloat(),
        y = (originY + yFromX * x + yFromY * y).toFloat(),
    )

    /**
     * Writes the page-space box ([left], [top], [right], [bottom]; top above bottom) as fractions of the drawn page
     * into [into] at [at]: left, top, right, bottom. A box with no width or height (a character PDFium made up)
     * is written as NaN, which [PdfPageText] reads as no box.
     */
    fun writeBox(left: Float, top: Float, right: Float, bottom: Float, into: FloatArray, at: Int) {
        val hasArea = right > left && top > bottom && listOf(left, top, right, bottom).all(Float::isFinite)
        if (!hasArea) {
            for (offset in 0 until PdfPageText.BoxSize) into[at + offset] = Float.NaN
            return
        }
        val a = map(left.toDouble(), top.toDouble())
        val b = map(right.toDouble(), bottom.toDouble())
        into[at + PdfPageText.Left] = min(a.x, b.x)
        into[at + PdfPageText.Top] = min(a.y, b.y)
        into[at + PdfPageText.Right] = max(a.x, b.x)
        into[at + PdfPageText.Bottom] = max(a.y, b.y)
    }

    companion object {
        /** The drawn page's size PDFium is asked to place points on: large, so its whole pixels are fine fractions. */
        const val DeviceSize: Int = 1 shl 20

        /** How far apart, in points, the three placed points are. */
        private const val Step = 1000.0

        /** The mapping from where [toDevice] places page-space points on a drawn page of [DeviceSize] square. */
        fun of(toDevice: (x: Double, y: Double) -> Pair<Int, Int>): PdfPageMapping {
            val origin = toDevice(0.0, 0.0)
            val alongX = toDevice(Step, 0.0)
            val alongY = toDevice(0.0, Step)
            val size = DeviceSize.toDouble()
            return PdfPageMapping(
                originX = origin.first / size,
                originY = origin.second / size,
                xFromX = (alongX.first - origin.first) / size / Step,
                yFromX = (alongX.second - origin.second) / size / Step,
                xFromY = (alongY.first - origin.first) / size / Step,
                yFromY = (alongY.second - origin.second) / size / Step,
            )
        }
    }
}

/**
 * Where a page drawn in Turn mode is on screen: fitted inside the viewport (the zoom layout's content size),
 * centred, then zoomed by [scale] about the viewport's centre and moved by [offset], as its image is.
 */
internal fun pdfTurnPageBounds(viewport: IntSize, content: Size, scale: Float, offset: Offset): Rect {
    val width = content.width * scale
    val height = content.height * scale
    val centreX = viewport.width / 2f + offset.x
    val centreY = viewport.height / 2f + offset.y
    return Rect(centreX - width / 2f, centreY - height / 2f, centreX + width / 2f, centreY + height / 2f)
}

/** Where the fractions ([x], [y]) of a page drawn at [page] are on screen. */
internal fun pageToScreen(page: Rect, x: Float, y: Float): Offset = Offset(page.left + x * page.width, page.top + y * page.height)

/** The fractions of a page drawn at [page] that [point] on screen is at (outside 0..1 when off the page). */
internal fun screenToPage(page: Rect, point: Offset): Offset = Offset(
    x = if (page.width > 0f) (point.x - page.left) / page.width else 0f,
    y = if (page.height > 0f) (point.y - page.top) / page.height else 0f,
)

/** [rect] of a page drawn at [page], on screen. */
internal fun pageRectToScreen(page: Rect, rect: PageTextRect): Rect = Rect(
    left = page.left + rect.left * page.width,
    top = page.top + rect.top * page.height,
    right = page.left + rect.right * page.width,
    bottom = page.top + rect.bottom * page.height,
)
