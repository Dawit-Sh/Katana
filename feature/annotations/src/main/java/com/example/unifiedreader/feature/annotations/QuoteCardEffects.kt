package com.example.unifiedreader.feature.annotations

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A picture as packed ARGB colours, row by row: [width] × [height]. */
internal class PixelGrid(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(pixels.size == width * height) { "$width × $height needs ${width * height} pixels, not ${pixels.size}" }
    }
}

/**
 * The card's picture effects, each drawn in place on the picture already cropped to the card, the
 * way projects/katana/2026-11-24-card-effects.md has them, so Katana and Katana Desk draw the same
 * pixels. L is luminance, 0.299 R + 0.587 G + 0.114 B (0 to 255). Nothing is random between the
 * preview and the saved card: Grain's noise comes from a fixed seed.
 */
internal object QuoteCardEffects {
    /** Draws [effect] on [grid]. None and Blur leave it as it is: [QuoteCardBackdrops] draws those. */
    fun apply(effect: QuoteCardEffect, grid: PixelGrid) {
        when (effect) {
            QuoteCardEffect.None, QuoteCardEffect.Blur -> Unit
            QuoteCardEffect.Dither -> dither(grid)
            QuoteCardEffect.Halftone -> halftone(grid)
            QuoteCardEffect.Grain -> grain(grid.pixels)
            QuoteCardEffect.Mono -> mono(grid.pixels)
            QuoteCardEffect.Duotone -> duotone(grid.pixels)
            QuoteCardEffect.Pixel -> pixelate(grid)
        }
    }

    /** A third of the size (each 3 × 3 square averaged), Atkinson-dithered to the two inks, then back up in 3 px squares. */
    fun dither(grid: PixelGrid) {
        val small = QuoteCardPixels.blockMeans(grid, DITHER_SCALE)
        atkinson(small)
        QuoteCardPixels.expand(small, DITHER_SCALE, grid)
    }

    /**
     * Atkinson dithering of [grid]'s luminance to [INK] (L under 128) and [PAPER]: each pixel's error
     * against black or white goes, an eighth to each, to six neighbours ahead of it.
     */
    fun atkinson(grid: PixelGrid) {
        val levels = DoubleArray(grid.pixels.size) { QuoteCardPixels.luminance(grid.pixels[it]) }
        for (index in levels.indices) {
            val light = levels[index] >= DITHER_THRESHOLD
            grid.pixels[index] = if (light) PAPER else INK
            val error = levels[index] - if (light) WHITE else 0.0
            spreadError(levels, grid.width, index, error / ATKINSON_PARTS)
        }
    }

    private fun spreadError(levels: DoubleArray, width: Int, index: Int, share: Double) {
        val x = index % width
        val y = index / width
        val height = levels.size / width
        for (step in ATKINSON_STEPS.indices step 2) {
            val nextX = x + ATKINSON_STEPS[step]
            val nextY = y + ATKINSON_STEPS[step + 1]
            if (nextX in 0 until width && nextY < height) levels[nextY * width + nextX] += share
        }
    }

    /**
     * Paper with a grid of 12 px cells, each with an ink dot centred in it of radius 6 × √(1 − L/255)
     * for the cell's mean L: a black cell is a full dot, a white one none. The dot's edge is
     * anti-aliased, a pixel inked by how far its centre lies inside the dot, across one pixel.
     */
    fun halftone(grid: PixelGrid) {
        val cell = HALFTONE_CELL
        val columns = QuoteCardPixels.blocksAcross(grid.width, cell)
        val levels = QuoteCardPixels.cellLuminance(grid, cell)
        val radii = DoubleArray(levels.size) { HALFTONE_RADIUS * sqrt((1 - levels[it] / WHITE).coerceAtLeast(0.0)) }
        val middle = cell / 2.0
        for (y in 0 until grid.height) {
            val dy = y % cell + PIXEL_MIDDLE - middle
            val row = y / cell * columns
            for (x in 0 until grid.width) {
                val dx = x % cell + PIXEL_MIDDLE - middle
                val ink = (radii[row + x / cell] + PIXEL_MIDDLE - sqrt(dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                grid.pixels[y * grid.width + x] = QuoteCardPixels.mix(PAPER, INK, ink)
            }
        }
    }

    /**
     * Desaturated by 40% (each channel 40% of the way to L), then the same noise n added to R, G and
     * B: n in −28..28 from a fixed-seed generator, pixel by pixel in row order, so it is the same every time.
     */
    fun grain(pixels: IntArray) {
        var seed = GRAIN_SEED
        for (index in pixels.indices) {
            seed = (seed * LCG_MULTIPLIER + LCG_INCREMENT) and LCG_MASK
            val noise = (seed / LCG_MODULUS * GRAIN_SPAN - GRAIN_REACH).roundToInt()
            val pixel = pixels[index]
            val level = QuoteCardPixels.luminance(pixel)
            pixels[index] = QuoteCardPixels.rgb(
                red = lerp(pixel.red.toDouble(), level, GRAIN_DESATURATION) + noise,
                green = lerp(pixel.green.toDouble(), level, GRAIN_DESATURATION) + noise,
                blue = lerp(pixel.blue.toDouble(), level, GRAIN_DESATURATION) + noise,
            )
        }
    }

    /** Grey from L, its contrast raised 1.15 times around 128. */
    fun mono(pixels: IntArray) {
        for (index in pixels.indices) {
            val grey = (QuoteCardPixels.luminance(pixels[index]) - MID_GREY) * MONO_CONTRAST + MID_GREY
            pixels[index] = QuoteCardPixels.rgb(grey, grey, grey)
        }
    }

    /** Each pixel between the shadow colour and the highlight by its L: black is all shadow, white all highlight. */
    fun duotone(pixels: IntArray) {
        for (index in pixels.indices) {
            pixels[index] = QuoteCardPixels.mix(DUOTONE_SHADOW, DUOTONE_HIGHLIGHT, QuoteCardPixels.luminance(pixels[index]) / WHITE)
        }
    }

    /** 36 px blocks (30 across a card), each filled with its pixels' mean colour; a short last row or column takes what's left. */
    fun pixelate(grid: PixelGrid) {
        QuoteCardPixels.expand(QuoteCardPixels.blockMeans(grid, PIXEL_BLOCK), PIXEL_BLOCK, grid)
    }

    /** Dither's and Halftone's two inks: a near-black and a warm paper. */
    const val INK = 0xFF1B1B1F.toInt()
    const val PAPER = 0xFFEFE9DC.toInt()
    const val DUOTONE_SHADOW = 0xFF1D2B45.toInt()
    const val DUOTONE_HIGHLIGHT = 0xFFF0C48A.toInt()
    private const val WHITE = 255.0
    private const val DITHER_SCALE = 3
    private const val DITHER_THRESHOLD = 128.0
    private const val ATKINSON_PARTS = 8.0

    /** Atkinson's six neighbours as (dx, dy) pairs: two to the right, three on the next row, one two rows down. */
    private val ATKINSON_STEPS = intArrayOf(1, 0, 2, 0, -1, 1, 0, 1, 1, 1, 0, 2)
    private const val HALFTONE_CELL = 12
    private const val HALFTONE_RADIUS = 6.0
    private const val PIXEL_MIDDLE = 0.5
    private const val GRAIN_SEED = 1_234_567L
    private const val LCG_MULTIPLIER = 1_103_515_245L
    private const val LCG_INCREMENT = 12_345L

    /** The generator works modulo 2^31: masking the low 31 bits is the same as the remainder, and quicker on a 32-bit device. */
    private const val LCG_MASK = 0x7FFF_FFFFL
    private const val LCG_MODULUS = 2_147_483_648.0
    private const val GRAIN_SPAN = 56.0
    private const val GRAIN_REACH = 28.0
    private const val GRAIN_DESATURATION = 0.4
    private const val MID_GREY = 128.0
    private const val MONO_CONTRAST = 1.15
    private const val PIXEL_BLOCK = 36
}

/** Per-pixel helpers for [QuoteCardEffects]: luminance, colours from channels, and a picture averaged in square blocks. */
internal object QuoteCardPixels {
    /** L = 0.299 R + 0.587 G + 0.114 B, 0 to 255. */
    fun luminance(pixel: Int): Double = RED_WEIGHT * pixel.red + GREEN_WEIGHT * pixel.green + BLUE_WEIGHT * pixel.blue

    /** An opaque colour from three channels, each rounded (halves up) and held to 0..255. */
    fun rgb(red: Double, green: Double, blue: Double): Int =
        OPAQUE or (channel(red) shl RED_SHIFT) or (channel(green) shl GREEN_SHIFT) or channel(blue)

    /** [from] moved [amount] (0 to 1) of the way to [to], channel by channel. */
    fun mix(from: Int, to: Int, amount: Double): Int = rgb(
        red = lerp(from.red.toDouble(), to.red.toDouble(), amount),
        green = lerp(from.green.toDouble(), to.green.toDouble(), amount),
        blue = lerp(from.blue.toDouble(), to.blue.toDouble(), amount),
    )

    /** How many [block]-long blocks cover [length], a short last one included. */
    fun blocksAcross(length: Int, block: Int): Int = (length + block - 1) / block

    /** [grid] in [block] × [block] squares, row by row, each the mean colour of its pixels; the last ones may be short. */
    fun blockMeans(grid: PixelGrid, block: Int): PixelGrid {
        val columns = blocksAcross(grid.width, block)
        val rows = blocksAcross(grid.height, block)
        val sums = IntArray(columns * rows * SUMS)
        for (y in 0 until grid.height) {
            val row = y / block * columns
            for (x in 0 until grid.width) {
                val pixel = grid.pixels[y * grid.width + x]
                val at = (row + x / block) * SUMS
                sums[at + SUM_RED] += pixel.red
                sums[at + SUM_GREEN] += pixel.green
                sums[at + SUM_BLUE] += pixel.blue
                sums[at + SUM_COUNT]++
            }
        }
        val means = IntArray(columns * rows) { cell ->
            val at = cell * SUMS
            val count = sums[at + SUM_COUNT].toDouble()
            rgb(sums[at + SUM_RED] / count, sums[at + SUM_GREEN] / count, sums[at + SUM_BLUE] / count)
        }
        return PixelGrid(columns, rows, means)
    }

    /** The mean luminance of each [block] × [block] square of [grid], row by row; the last ones may be short. */
    fun cellLuminance(grid: PixelGrid, block: Int): DoubleArray {
        val columns = blocksAcross(grid.width, block)
        val sums = DoubleArray(columns * blocksAcross(grid.height, block))
        val counts = IntArray(sums.size)
        for (y in 0 until grid.height) {
            val row = y / block * columns
            for (x in 0 until grid.width) {
                val cell = row + x / block
                sums[cell] += luminance(grid.pixels[y * grid.width + x])
                counts[cell]++
            }
        }
        return DoubleArray(sums.size) { sums[it] / counts[it] }
    }

    /** [small] drawn back over [into] with no smoothing, each of its pixels a [block] × [block] square. */
    fun expand(small: PixelGrid, block: Int, into: PixelGrid) {
        for (y in 0 until into.height) {
            val row = (y / block).coerceAtMost(small.height - 1) * small.width
            for (x in 0 until into.width) {
                into.pixels[y * into.width + x] = small.pixels[row + (x / block).coerceAtMost(small.width - 1)]
            }
        }
    }

    private fun channel(value: Double): Int = value.roundToInt().coerceIn(0, CHANNEL_MAX)

    private const val RED_WEIGHT = 0.299
    private const val GREEN_WEIGHT = 0.587
    private const val BLUE_WEIGHT = 0.114
    private const val OPAQUE = 0xFF000000.toInt()
    private const val SUMS = 4
    private const val SUM_RED = 0
    private const val SUM_GREEN = 1
    private const val SUM_BLUE = 2
    private const val SUM_COUNT = 3
}

private fun lerp(from: Double, to: Double, amount: Double): Double = from + (to - from) * amount

private val Int.red: Int get() = (this ushr RED_SHIFT) and CHANNEL_MAX
private val Int.green: Int get() = (this ushr GREEN_SHIFT) and CHANNEL_MAX
private val Int.blue: Int get() = this and CHANNEL_MAX

private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MAX = 0xFF
