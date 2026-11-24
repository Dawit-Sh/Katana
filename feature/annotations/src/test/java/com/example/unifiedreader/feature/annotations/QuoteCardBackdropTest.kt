package com.example.unifiedreader.feature.annotations

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuoteCardBackdropTest {
    @Test
    fun `a cover fills a square card from its middle, and a tall card nearly whole`() {
        assertEquals(Rect(0, 240, 960, 1200), QuoteCardBackdrops.fillCrop(960, 1440, 1080, 1080))
        assertEquals(Rect(0, 120, 960, 1320), QuoteCardBackdrops.fillCrop(960, 1440, 1080, 1350))
        // A wide photo loses its sides.
        assertEquals(Rect(500, 0, 3500, 3000), QuoteCardBackdrops.fillCrop(4000, 3000, 1080, 1080))
    }

    @Test
    fun `a big photo is read at a fraction, never smaller than a card`() {
        assertEquals(1, QuoteCardBackdrops.photoSampleSize(1080, 1350))
        assertEquals(1, QuoteCardBackdrops.photoSampleSize(2699, 4000))
        assertEquals(2, QuoteCardBackdrops.photoSampleSize(4000, 3000))
        assertEquals(4, QuoteCardBackdrops.photoSampleSize(8000, 6000))
    }

    @Test
    fun `the blur keeps a flat colour flat and softens a hard edge`() {
        val width = 20
        val flat = IntArray(width * 4) { BLUE }
        QuoteCardBackdrops.boxBlur(flat, width, 4, 3)
        assertTrue(flat.all { it == BLUE })

        val edge = IntArray(width * 4) { index -> if (index % width < width / 2) Color.BLACK else Color.WHITE }
        QuoteCardBackdrops.boxBlur(edge, width, 4, 3)
        val atEdge = Color.red(edge[width / 2])
        assertTrue("grey at the edge, was $atEdge", atEdge in 60..200)
        assertEquals(Color.BLACK, edge[0])
        assertEquals(Color.WHITE, edge[width - 1])
    }

    @Test
    fun `a card on a picture shows the picture, darkened, blurred or sharp`() {
        val red = createBitmap(300, 450).apply { eraseColor(Color.rgb(220, 40, 40)) }
        listOf(QuoteCardEffect.Blur, QuoteCardEffect.None).forEach { effect ->
            val card = render(QuoteCardBackdrop(red, effect = effect))
            assertEquals(1080, card.width)
            assertEquals(1080, card.height)
            // The top corner: the picture's red under the scrim, not the card's own ground.
            val corner = card.getPixel(4, 4)
            assertTrue("red shows through, was ${Integer.toHexString(corner)}", Color.red(corner) > Color.green(corner) * 3)
            assertTrue("darkened, was ${Integer.toHexString(corner)}", Color.red(corner) < 220)
        }
    }

    @Test
    fun `a card with no picture keeps its colour ground`() {
        val card = render(null)
        assertEquals(GROUND, card.getPixel(4, 4))
    }

    private fun render(backdrop: QuoteCardBackdrop?): Bitmap = QuoteCardRenderer.render(
        text = QuoteCardText(quote = "The hole was there all along.", title = "The Hole", author = "Hiroko Oyamada"),
        colors = QuoteCardColors(ground = GROUND, ink = Color.WHITE, secondary = Color.LTGRAY, accent = Color.WHITE),
        type = QuoteCardType(Typeface.SERIF, Typeface.SERIF, Typeface.DEFAULT_BOLD, Typeface.DEFAULT),
        backdrop = backdrop,
    )

    private companion object {
        val BLUE = Color.rgb(30, 60, 200)
        val GROUND = Color.rgb(31, 31, 31)
    }
}
