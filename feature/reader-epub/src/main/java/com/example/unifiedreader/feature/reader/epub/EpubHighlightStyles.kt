package com.example.unifiedreader.feature.reader.epub

import android.graphics.Color
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
    // Drawn on the box Readium places over each line, as its own underline is: a child inside it didn't show on the Fire.
    set(
        EpubStrikethroughStyle::class,
        HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            width = HtmlDecorationTemplate.Width.WRAP,
            element = ::strikeElement,
        ),
    )
    set(
        EpubSquigglyStyle::class,
        HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            width = HtmlDecorationTemplate.Width.WRAP,
            element = ::squiggleElement,
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

/** A band of [Decoration.Style.Tinted.tint] through the middle of the line's box. */
private fun strikeElement(decoration: Decoration): String {
    val rgb = decoration.tintRgb()
    return "<div style=\"background: linear-gradient(to bottom, transparent calc(50% - 1px), $rgb calc(50% - 1px), " +
        "$rgb calc(50% + 1px), transparent calc(50% + 1px));\"></div>"
}

/** A wavy line of the tint along the bottom of the line's box, an SVG wave repeated across it. */
private fun squiggleElement(decoration: Decoration): String {
    val wave = Base64.getEncoder().encodeToString(
        (
            "<svg xmlns='http://www.w3.org/2000/svg' width='8' height='4' viewBox='0 0 8 4'>" +
                "<path d='M0 3 Q2 0 4 3 T8 3' fill='none' stroke='${decoration.tintRgb()}' stroke-width='1.4'/></svg>"
            ).toByteArray(),
    )
    return "<div style=\"background-image: url('data:image/svg+xml;base64,$wave'); background-repeat: repeat-x; " +
        "background-position: left bottom; background-size: 8px 4px;\"></div>"
}

private fun Decoration.tintRgb(): String {
    val tint = (style as? Decoration.Style.Tinted)?.tint ?: 0
    return "rgb(${Color.red(tint)}, ${Color.green(tint)}, ${Color.blue(tint)})"
}
