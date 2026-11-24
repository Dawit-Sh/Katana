package com.example.unifiedreader.feature.annotations

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The effects' maths, as the spec has them (projects/katana/2026-11-24-card-effects.md), on small pictures. */
class QuoteCardEffectsTest {
    @Test
    fun `luminance weighs green most and blue least`() {
        assertEquals(0.0, QuoteCardPixels.luminance(rgb(0, 0, 0)), DELTA)
        assertEquals(255.0, QuoteCardPixels.luminance(rgb(255, 255, 255)), DELTA)
        assertEquals(76.245, QuoteCardPixels.luminance(rgb(255, 0, 0)), DELTA)
        assertEquals(149.685, QuoteCardPixels.luminance(rgb(0, 255, 0)), DELTA)
        assertEquals(29.07, QuoteCardPixels.luminance(rgb(0, 0, 255)), DELTA)
    }

    @Test
    fun `dither turns a flat grey into a mix of the two inks near half and half, in 3 px squares`() {
        val grid = flat(90, 90, grey(128))
        QuoteCardEffects.dither(grid)
        assertTrue("only the two inks", grid.pixels.all { it == QuoteCardEffects.INK || it == QuoteCardEffects.PAPER })
        val light = grid.pixels.count { it == QuoteCardEffects.PAPER }.toDouble() / grid.pixels.size
        assertTrue("near half light, was $light", light in 0.4..0.6)
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                assertEquals("($x, $y) is its square's", grid.at(x / 3 * 3, y / 3 * 3), grid.at(x, y))
            }
        }
    }

    @Test
    fun `dither keeps black and white as the two inks`() {
        val dark = flat(30, 30, grey(0)).also(QuoteCardEffects::dither)
        val light = flat(30, 30, grey(255)).also(QuoteCardEffects::dither)
        assertTrue(dark.pixels.all { it == QuoteCardEffects.INK })
        assertTrue(light.pixels.all { it == QuoteCardEffects.PAPER })
    }

    @Test
    fun `a black halftone cell is a full dot and a white one none`() {
        val black = flat(12, 12, grey(0)).also(QuoteCardEffects::halftone)
        assertEquals(QuoteCardEffects.INK, black.at(5, 5))
        assertEquals(QuoteCardEffects.INK, black.at(6, 6))
        // The dot reaches the cell's sides (almost all ink at the anti-aliased edge) but not its corners.
        assertTrue(red(black.at(0, 5)) < 40)
        assertTrue(red(black.at(11, 6)) < 40)
        assertEquals(QuoteCardEffects.PAPER, black.at(0, 0))
        assertEquals(QuoteCardEffects.PAPER, black.at(11, 11))
        // A disc of radius 6 is about 113 pixels.
        assertEquals(Math.PI * 36, inked(black), 3.0)

        val white = flat(12, 12, grey(255)).also(QuoteCardEffects::halftone)
        assertTrue(white.pixels.all { it == QuoteCardEffects.PAPER })
    }

    @Test
    fun `a halftone dot's area follows the cell's mean luminance`() {
        // Mean L 127.5: radius 6 × √0.5, about 56.5 pixels of ink.
        val half = PixelGrid(12, 12, IntArray(144) { if (it % 2 == 0) grey(0) else grey(255) })
        QuoteCardEffects.halftone(half)
        assertEquals(Math.PI * 18, inked(half), 2.0)
        // A short last row of cells still gets its dots, centred where the full cell's centre is.
        val tall = flat(12, 18, grey(0)).also(QuoteCardEffects::halftone)
        assertEquals(QuoteCardEffects.INK, tall.at(6, 17))
        assertEquals(QuoteCardEffects.PAPER, tall.at(0, 12))
    }

    @Test
    fun `grain is the same every time, from the fixed seed`() {
        val picture = IntArray(64 * 40) { rgb(it % 256, (it * 7) % 256, (it * 13) % 256) }
        val first = PixelGrid(64, 40, picture.copyOf()).also { QuoteCardEffects.grain(it.pixels) }
        val second = PixelGrid(64, 40, picture.copyOf()).also { QuoteCardEffects.grain(it.pixels) }
        assertArrayEquals(first.pixels, second.pixels)
    }

    @Test
    fun `grain adds the generator's noise to every channel alike`() {
        // On grey the desaturation changes nothing, so each pixel is 128 + n: n = 25, 0, 4, -3, -4 first.
        val row = flat(5, 1, grey(128)).also { QuoteCardEffects.grain(it.pixels) }
        assertArrayEquals(intArrayOf(grey(153), grey(128), grey(132), grey(125), grey(124)), row.pixels)
        val noisy = flat(200, 100, grey(128)).also { QuoteCardEffects.grain(it.pixels) }
        assertTrue("within 28 of grey", noisy.pixels.all { red(it) in 100..156 })
        assertTrue("noise both ways", noisy.pixels.any { red(it) < 110 } && noisy.pixels.any { red(it) > 146 })
    }

    @Test
    fun `grain desaturates by 40 percent before the noise`() {
        val pure = flat(1, 1, rgb(255, 0, 0)).also { QuoteCardEffects.grain(it.pixels) }
        // L = 76.245; red 255 → 183.498, green and blue 0 → 30.498; then n = 25.
        assertEquals(rgb(208, 55, 55), pure.pixels[0])
    }

    @Test
    fun `mono is L with its contrast raised 1·15 times around 128`() {
        val pixels = intArrayOf(grey(128), grey(200), grey(10), rgb(255, 0, 0), grey(255))
        QuoteCardEffects.mono(pixels)
        assertArrayEquals(intArrayOf(grey(128), grey(211), grey(0), grey(68), grey(255)), pixels)
    }

    @Test
    fun `duotone runs from the shadow on black to the highlight on white`() {
        val pixels = intArrayOf(grey(0), grey(255), grey(128), rgb(0, 255, 0))
        QuoteCardEffects.duotone(pixels)
        assertEquals(QuoteCardEffects.DUOTONE_SHADOW, pixels[0])
        assertEquals(QuoteCardEffects.DUOTONE_HIGHLIGHT, pixels[1])
        // t = 128/255: #1D2B45 → #F0C48A about halfway.
        assertEquals(rgb(135, 120, 104), pixels[2])
        // Pure green, t = 149.685/255.
        assertEquals(rgb(153, 133, 110), pixels[3])
    }

    @Test
    fun `pixel blocks are flat, each its pixels' mean, a short last row included`() {
        val grid = PixelGrid(72, 54, IntArray(72 * 54) { rgb(it % 72 * 3, it / 72 * 4, 90) })
        val original = grid.pixels.copyOf()
        QuoteCardEffects.pixelate(grid)
        // Columns 0..35 average to red 52.5 (rounds up to 53), rows 0..35 to green 70.
        assertEquals(rgb(53, 70, 90), grid.at(0, 0))
        // The short last row, 36..53, averages green 178.
        assertEquals(rgb(161, 178, 90), grid.at(71, 53))
        listOf(0 to 0, 36 to 0, 0 to 36, 36 to 36).forEach { (left, top) ->
            val corner = grid.at(left, top)
            for (y in top until minOf(top + 36, 54)) {
                for (x in left until left + 36) assertEquals("block ($left, $top) at ($x, $y)", corner, grid.at(x, y))
            }
        }
        assertTrue(!original.contentEquals(grid.pixels))
    }

    @Test
    fun `none and blur leave the pixels to the backdrop`() {
        val picture = IntArray(36) { rgb(it * 7, 20, 40) }
        listOf(QuoteCardEffect.None, QuoteCardEffect.Blur).forEach { effect ->
            val grid = PixelGrid(6, 6, picture.copyOf())
            QuoteCardEffects.apply(effect, grid)
            assertArrayEquals(picture, grid.pixels)
        }
    }

    /** How many pixels' worth of ink [grid] holds, a half-inked pixel counting half. */
    private fun inked(grid: PixelGrid): Double {
        val paper = red(QuoteCardEffects.PAPER)
        val ink = red(QuoteCardEffects.INK)
        return grid.pixels.sumOf { (paper - red(it)).toDouble() / (paper - ink) }
    }

    private fun PixelGrid.at(x: Int, y: Int): Int = pixels[y * width + x]

    private fun flat(width: Int, height: Int, color: Int) = PixelGrid(width, height, IntArray(width * height) { color })

    private fun grey(level: Int): Int = rgb(level, level, level)

    private fun rgb(red: Int, green: Int, blue: Int): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    private fun red(color: Int): Int = (color shr 16) and 0xFF

    private companion object {
        const val DELTA = 1e-9
    }
}
