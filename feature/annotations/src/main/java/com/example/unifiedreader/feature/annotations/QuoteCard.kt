package com.example.unifiedreader.feature.annotations

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation

/** The card's colours: its ground, the quote, the book line under it and the accent (quote mark, Katana). */
data class QuoteCardColors(
    val ground: Int,
    val ink: Int,
    val secondary: Int,
    val accent: Int,
)

/** The card's shape: square, or tall (4:5) for a phone's stories and feeds. */
enum class QuoteCardShape(val width: Int, val height: Int) {
    Square(CARD_WIDTH, CARD_WIDTH),
    Tall(CARD_WIDTH, CARD_TALL_HEIGHT),
}

/** The faces the card is set in: the quote's (Literata italic) and the captions' (the app's own). */
data class QuoteCardType(
    val quote: Typeface,
    val mark: Typeface,
    val captionBold: Typeface,
    val caption: Typeface,
)

/** What a card says: the quote, and the book and author it is from. */
data class QuoteCardText(
    val quote: String,
    val title: String,
    val author: String?,
)

/**
 * Draws a quote as an image card: a quote mark in the accent, the quote in Literata italic as large
 * as fits, then the book and its author on the left and the Katana mark (刀 Katana) on the right.
 * The ground is a colour, or a picture ([QuoteCardBackdrop]: the cover or a photo) darkened under white text.
 */
object QuoteCardRenderer {
    fun render(
        text: QuoteCardText,
        colors: QuoteCardColors,
        type: QuoteCardType,
        shape: QuoteCardShape = QuoteCardShape.Square,
        backdrop: QuoteCardBackdrop? = null,
    ): Bitmap {
        val width = shape.width
        val height = shape.height
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        if (backdrop == null) canvas.drawColor(colors.ground) else QuoteCardBackdrops.draw(canvas, backdrop, width, height)
        // Over a picture every line gets a soft shadow, so it reads on light patches too.
        val shadow = if (backdrop == null) 0f else width * SHADOW_RADIUS
        fun textPaint(face: Typeface, color: Int, size: Float): TextPaint = paint(face, color, size, shadow)
        val side = width * SIDE_INSET
        val top = width * TOP_INSET
        val bottom = width * BOTTOM_INSET
        val textWidth = (width - side * 2).toInt()

        // The quote mark.
        val markPaint = textPaint(type.mark, colors.accent, width * MARK_SIZE)
        val markTop = top - markPaint.ascent() * MARK_TOP_TRIM
        canvas.drawText("“", side, markTop, markPaint)

        // The footer, bottom up: the author, the title, and the mark on the right.
        val captionSize = width * CAPTION_SIZE
        val titlePaint = textPaint(type.captionBold, colors.ink, width * TITLE_SIZE)
        val authorPaint = textPaint(type.caption, colors.secondary, captionSize)
        val brandPaint = textPaint(type.captionBold, colors.accent, captionSize)
        val kanjiPaint = textPaint(type.captionBold, colors.accent, width * KANJI_SIZE)
        val baseline = height - bottom
        val brandWidth = brandPaint.measureText(BRAND) + kanjiPaint.measureText(KANJI) + width * BRAND_GAP
        val footerTextWidth = (textWidth - brandWidth - width * BRAND_GAP).coerceAtLeast(1f)
        var footerTop = baseline + authorPaint.descent()
        text.author?.takeIf(String::isNotBlank)?.let { author ->
            val line = TextUtils.ellipsize(author.oneLine(), authorPaint, footerTextWidth, TextUtils.TruncateAt.END).toString()
            canvas.drawText(line, side, baseline, authorPaint)
            footerTop = baseline + authorPaint.ascent()
        }
        val titleBaseline = if (text.author.isNullOrBlank()) baseline else footerTop - titlePaint.descent() - width * LINE_GAP
        val title = TextUtils.ellipsize(text.title.oneLine(), titlePaint, footerTextWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(title, side, titleBaseline, titlePaint)
        footerTop = titleBaseline + titlePaint.ascent()
        val brandX = width - side - brandWidth
        canvas.drawText(KANJI, brandX, baseline, kanjiPaint)
        canvas.drawText(BRAND, brandX + kanjiPaint.measureText(KANJI) + width * BRAND_GAP, baseline, brandPaint)

        // The quote, as large as fits between the mark and the footer.
        val quoteTop = markTop + markPaint.descent() + width * QUOTE_GAP
        val room = RectF(side, quoteTop, side + textWidth, footerTop - width * QUOTE_GAP)
        val quotePaint = { size: Float -> textPaint(type.quote, colors.ink, size) }
        val layout = fittedQuote(quoteBody(text.quote), quotePaint, width, textWidth, room.height())
        canvas.withTranslation(room.left, room.top) { layout.draw(this) }
        return bitmap
    }

    /** The quote's words, its line breaks kept but its runs of spaces made single. */
    internal fun quoteBody(quote: String): String =
        quote.trim().lines().joinToString("\n") { line -> line.oneLine() }.replace(Regex("\n{3,}"), "\n\n")

    /** The largest size, from 34 to 18 points of a 440-point card, at which the quote fits [height]; at the least it is cut. */
    private fun fittedQuote(quote: String, paintAt: (Float) -> TextPaint, cardWidth: Int, width: Int, height: Float): StaticLayout {
        var size = cardWidth * QUOTE_MAX
        val minSize = cardWidth * QUOTE_MIN
        while (true) {
            val paint = paintAt(size)
            val layout = layout(quote, paint, width, maxLines = Int.MAX_VALUE)
            if (layout.height <= height || size <= minSize) {
                if (layout.height <= height) return layout
                val lines = (height / (layout.height.toFloat() / layout.lineCount)).toInt().coerceAtLeast(1)
                return layout(quote, paint, width, maxLines = lines)
            }
            size -= cardWidth * QUOTE_STEP
        }
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, QUOTE_LINE_SPACING)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun paint(face: Typeface, color: Int, size: Float, shadow: Float): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            this.color = color
            textSize = size
            if (shadow > 0f) setShadowLayer(shadow, 0f, shadow / 2, SHADOW_COLOR)
        }

    private const val BRAND = "Katana"
    private const val KANJI = "刀"

    // Proportions of the card's width, from the 440-point design.
    private const val SIDE_INSET = 40f / 440f
    private const val TOP_INSET = 44f / 440f
    private const val BOTTOM_INSET = 34f / 440f
    private const val MARK_SIZE = 72f / 440f
    private const val MARK_TOP_TRIM = 0.62f
    private const val QUOTE_GAP = 16f / 440f
    private const val QUOTE_MAX = 34f / 440f
    private const val QUOTE_MIN = 18f / 440f
    private const val QUOTE_STEP = 1f / 440f
    private const val QUOTE_LINE_SPACING = 1.25f
    private const val TITLE_SIZE = 16f / 440f
    private const val CAPTION_SIZE = 14f / 440f
    private const val KANJI_SIZE = 20f / 440f
    private const val BRAND_GAP = 6f / 440f
    private const val LINE_GAP = 4f / 440f
    private const val SHADOW_RADIUS = 3f / 440f
    private const val SHADOW_COLOR = 0x8C000000.toInt()
}

private const val CARD_WIDTH = 1080
private const val CARD_TALL_HEIGHT = 1350
