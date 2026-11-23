package com.example.unifiedreader.feature.annotations

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A picture behind the quote: the book's cover or the reader's own photo, filling the card. [blurred]
 * softens it, so the cover's own words and a photo's detail don't fight the quote.
 */
data class QuoteCardBackdrop(val image: Bitmap, val blurred: Boolean)

/** Drawing a [QuoteCardBackdrop]: the picture cropped to fill the card, blurred if asked, then darkened so white text reads. */
internal object QuoteCardBackdrops {
    fun draw(canvas: Canvas, backdrop: QuoteCardBackdrop, width: Int, height: Int) {
        val crop = fillCrop(backdrop.image.width, backdrop.image.height, width, height)
        val card = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (backdrop.blurred) {
            val soft = softened(backdrop.image, crop, (width / BLUR_SCALE).coerceAtLeast(1), (height / BLUR_SCALE).coerceAtLeast(1))
            canvas.drawBitmap(soft, null, card, filter)
        } else {
            canvas.drawBitmap(backdrop.image, crop, card, filter)
        }
        canvas.drawColor(if (backdrop.blurred) SCRIM_BLURRED else SCRIM_SHARP)
        // Deeper at the foot, where the book line sits; deepest on a sharp picture, where a cover's own title often is.
        val footTop = height * FOOT_START
        val footShade = if (backdrop.blurred) FOOT_SHADE_BLURRED else FOOT_SHADE_SHARP
        val foot = Paint().apply {
            shader = LinearGradient(0f, footTop, 0f, height.toFloat(), TRANSPARENT, footShade, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, footTop, width.toFloat(), height.toFloat(), foot)
    }

    /** The part of a [sourceWidth] × [sourceHeight] picture that fills a [width] × [height] card: centred, as large as fits. */
    fun fillCrop(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): Rect {
        val scale = max(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val cropWidth = (width / scale).roundToInt().coerceIn(1, sourceWidth)
        val cropHeight = (height / scale).roundToInt().coerceIn(1, sourceHeight)
        val left = (sourceWidth - cropWidth) / 2
        val top = (sourceHeight - cropHeight) / 2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    /**
     * The sample size that reads a [width] × [height] photo no smaller than a card needs: halved while
     * its shorter side stays at least [CARD_SIDE] pixels, so a 12-megapixel photo decodes at a quarter.
     */
    fun photoSampleSize(width: Int, height: Int): Int {
        val shorter = min(width, height)
        var sample = 1
        while (shorter / (sample * 2) >= CARD_SIDE) sample *= 2
        return sample
    }

    /** [crop] of [image] at [width] × [height]: halved step by step (so no detail is skipped), then box-blurred. */
    private fun softened(image: Bitmap, crop: Rect, width: Int, height: Int): Bitmap {
        var current = Bitmap.createBitmap(image, crop.left, crop.top, crop.width(), crop.height())
        while (current.width / 2 >= width && current.height / 2 >= height) {
            current = current.scale(current.width / 2, current.height / 2, filter = true)
        }
        val small = current.scale(width, height, filter = true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        repeat(BLUR_PASSES) { boxBlur(pixels, width, height, BLUR_RADIUS) }
        return createBitmap(width, height).apply { setPixels(pixels, 0, width, 0, 0, width, height) }
    }

    /** One pass of a box blur of [radius], across and then down; three passes come close to a Gaussian. */
    fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        for (y in 0 until height) blurLine(pixels, BlurLine(start = y * width, stride = 1, count = width), radius)
        for (x in 0 until width) blurLine(pixels, BlurLine(start = x, stride = width, count = height), radius)
    }

    private class BlurLine(val start: Int, val stride: Int, val count: Int) {
        fun at(index: Int): Int = start + index.coerceIn(0, count - 1) * stride
    }

    private fun blurLine(pixels: IntArray, line: BlurLine, radius: Int) {
        val source = IntArray(line.count) { pixels[line.at(it)] }
        val window = radius * 2 + 1
        val sums = IntArray(CHANNELS)
        for (offset in -radius..radius) addPixel(sums, source[offset.coerceIn(0, line.count - 1)], 1)
        for (index in 0 until line.count) {
            pixels[line.at(index)] = CHANNEL_SHIFTS.indices.fold(0) { argb, channel ->
                argb or ((sums[channel] / window) shl CHANNEL_SHIFTS[channel])
            }
            addPixel(sums, source[(index - radius).coerceIn(0, line.count - 1)], -1)
            addPixel(sums, source[(index + radius + 1).coerceIn(0, line.count - 1)], 1)
        }
    }

    private fun addPixel(sums: IntArray, pixel: Int, sign: Int) {
        CHANNEL_SHIFTS.forEachIndexed { channel, shift -> sums[channel] += sign * ((pixel ushr shift) and CHANNEL_MASK) }
    }

    /** The card is blurred at 1/8 of its size, which three passes of radius 3 soften to a wash of the picture's colours. */
    private const val BLUR_SCALE = 8
    private const val BLUR_RADIUS = 3
    private const val BLUR_PASSES = 3
    private const val CARD_SIDE = 1350
    private const val SCRIM_BLURRED = 0x52000000
    private const val SCRIM_SHARP = 0x70000000
    private const val FOOT_START = 0.6f
    private const val FOOT_SHADE_BLURRED = 0x80000000.toInt()
    private const val FOOT_SHADE_SHARP = 0xC8000000.toInt()
    private const val TRANSPARENT = 0
    private const val CHANNELS = 4
    private const val CHANNEL_MASK = 0xFF
    private val CHANNEL_SHIFTS = intArrayOf(24, 16, 8, 0)
}
