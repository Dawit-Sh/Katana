package com.example.unifiedreader.feature.reader.epub

import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import com.example.unifiedreader.feature.annotations.HighlightStyle
import com.example.unifiedreader.feature.annotations.ReaderHighlight
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.shared.publication.Locator

/** The decoration group the reader's highlights are drawn in (the note-jump flash has its own). */
internal const val EpubHighlightGroup = "highlights"

/** A line through the words. Readium draws only fills and underlines, so this one is ours. */
internal data class EpubStrikethroughStyle(@ColorInt override val tint: Int) : Decoration.Style, Decoration.Style.Tinted {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(tint)

    companion object CREATOR : Parcelable.Creator<EpubStrikethroughStyle> {
        override fun createFromParcel(source: Parcel): EpubStrikethroughStyle = EpubStrikethroughStyle(source.readInt())

        override fun newArray(size: Int): Array<EpubStrikethroughStyle?> = arrayOfNulls(size)
    }
}

/** A wavy line under the words, ours too. */
internal data class EpubSquigglyStyle(@ColorInt override val tint: Int) : Decoration.Style, Decoration.Style.Tinted {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) = dest.writeInt(tint)

    companion object CREATOR : Parcelable.Creator<EpubSquigglyStyle> {
        override fun createFromParcel(source: Parcel): EpubSquigglyStyle = EpubSquigglyStyle(source.readInt())

        override fun newArray(size: Int): Array<EpubSquigglyStyle?> = arrayOfNulls(size)
    }
}

/**
 * Readium's templates plus the two of ours. Each is drawn once per line of the marked words
 * (BOXES), over the text, so it moves with the text when the size changes.
 */
internal fun HtmlDecorationTemplates.withEpubHighlightStyles(): HtmlDecorationTemplates = copy().apply {
    set(
        EpubStrikethroughStyle::class,
        HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            width = HtmlDecorationTemplate.Width.WRAP,
            element = { decoration -> lineElement(StrikeClass, decoration) },
            stylesheet = StrikeStylesheet,
        ),
    )
    set(
        EpubSquigglyStyle::class,
        HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            width = HtmlDecorationTemplate.Width.WRAP,
            element = { decoration -> lineElement(SquiggleClass, decoration) },
            stylesheet = SquiggleStylesheet,
        ),
    )
}

/** The decoration that draws [this], or null when its stored place can't be read. */
internal fun ReaderHighlight.toEpubDecoration(): Decoration? {
    val locator = try {
        Locator.fromJSON(JSONObject(place.locatorJson))
    } catch (_: JSONException) {
        null
    } ?: return null
    val tint = color.argb.toInt()
    val decorationStyle = when (style) {
        HighlightStyle.Highlight -> Decoration.Style.Highlight(tint = tint)
        HighlightStyle.Underline -> Decoration.Style.Underline(tint = tint)
        HighlightStyle.Strikethrough -> EpubStrikethroughStyle(tint)
        HighlightStyle.Squiggly -> EpubSquigglyStyle(tint)
    }
    return Decoration(id = id, locator = locator, style = decorationStyle)
}

private fun lineElement(className: String, decoration: Decoration): String {
    val tint = (decoration.style as? Decoration.Style.Tinted)?.tint ?: 0
    return "<div><div class=\"$className\" style=\"background-color: ${tint.toCssRgb()}\"></div></div>"
}

private fun Int.toCssRgb(): String = "rgb(${(this shr 16) and 0xFF}, ${(this shr 8) and 0xFF}, ${this and 0xFF})"

private const val StrikeClass = "ysy-strike"
private const val SquiggleClass = "ysy-squiggle"

private const val StrikeStylesheet = """
.$StrikeClass {
    position: absolute;
    left: 0;
    right: 0;
    top: 50%;
    height: 2px;
    margin-top: -1px;
    border-radius: 1px;
}
"""

/** One wave of the squiggle, repeated along the line; the colour shows through it as a mask. */
private val SquiggleWave: String = Base64.getEncoder().encodeToString(
    (
        "<svg xmlns='http://www.w3.org/2000/svg' width='8' height='4' viewBox='0 0 8 4'>" +
            "<path d='M0 3 Q2 0 4 3 T8 3' fill='none' stroke='black' stroke-width='1.4'/></svg>"
        ).toByteArray(),
)

private val SquiggleStylesheet = """
.$SquiggleClass {
    position: absolute;
    left: 0;
    right: 0;
    bottom: -3px;
    height: 4px;
    -webkit-mask-image: url("data:image/svg+xml;base64,$SquiggleWave");
    -webkit-mask-repeat: repeat-x;
    -webkit-mask-size: 8px 4px;
    mask-image: url("data:image/svg+xml;base64,$SquiggleWave");
    mask-repeat: repeat-x;
    mask-size: 8px 4px;
}
"""
