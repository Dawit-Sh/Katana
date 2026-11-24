package com.example.unifiedreader.feature.annotations

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Each effect drawn on a whole card, square and tall: the right size, and the picture showing through. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuoteCardEffectRenderTest(private val effect: QuoteCardEffect) {
    @Test
    fun `the card comes out at its size and is not blank`() {
        QuoteCardShape.entries.forEach { shape ->
            val card = render(effect, shape)
            assertEquals("$effect $shape width", shape.width, card.width)
            assertEquals("$effect $shape height", shape.height, card.height)
            assertNotEquals("$effect $shape: the picture, not the colour ground", GROUND, card.getPixel(4, 4))
            // Across the top, above the quote mark, the picture's gradient shows as light and dark.
            val levels = strip(card).map { pixel -> Color.red(pixel) * 3 + Color.green(pixel) * 6 + Color.blue(pixel) }
            val spread = levels.max() - levels.min()
            assertTrue("$effect $shape: light and dark across the top, spread was $spread", spread > SPREAD)
        }
    }

    @Test
    fun `the effect changes the picture, the same way every time`() {
        val card = render(effect, QuoteCardShape.Square)
        val again = render(effect, QuoteCardShape.Square)
        assertEquals("$effect: the preview is the saved card", strip(card), strip(again))
        if (effect != QuoteCardEffect.None) {
            val plain = strip(render(QuoteCardEffect.None, QuoteCardShape.Square))
            val changed = strip(card).zip(plain).count { (a, b) -> a != b }
            assertTrue("$effect: changed $changed of ${plain.size} pixels", changed > plain.size / 4)
        }
    }

    /** Every fourth pixel of the row 30 px down: clear of the quote mark and the text. */
    private fun strip(card: Bitmap): List<Int> = (0 until card.width step 4).map { x -> card.getPixel(x, 30) }

    private fun render(effect: QuoteCardEffect, shape: QuoteCardShape): Bitmap = QuoteCardRenderer.render(
        text = QuoteCardText(quote = "The hole was there all along.", title = "The Hole", author = "Hiroko Oyamada"),
        colors = QuoteCardColors(ground = GROUND, ink = Color.WHITE, secondary = Color.LTGRAY, accent = Color.WHITE),
        type = QuoteCardType(Typeface.SERIF, Typeface.SERIF, Typeface.DEFAULT_BOLD, Typeface.DEFAULT),
        shape = shape,
        backdrop = QuoteCardBackdrop(picture(), effect),
    )

    /** A cover-shaped picture, dark to light from left to right, with a tint down it. */
    private fun picture(): Bitmap {
        val width = 400
        val height = 600
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            Color.rgb(x * 255 / (width - 1), x * 200 / (width - 1) + y * 55 / (height - 1), 60 + y * 120 / (height - 1))
        }
        return createBitmap(width, height).apply { setPixels(pixels, 0, width, 0, 0, width, height) }
    }

    companion object {
        // Read when the runner lists the parameters, before Robolectric's Android is there: no Color calls here.
        private const val GROUND = 0xFF1F1F1F.toInt()
        private const val SPREAD = 200

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun effects(): List<Array<Any>> = QuoteCardEffect.entries.map { arrayOf(it) }
    }
}
