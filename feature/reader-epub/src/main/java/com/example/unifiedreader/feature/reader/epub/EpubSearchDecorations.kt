package com.example.unifiedreader.feature.reader.epub

import android.graphics.Color
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates

/** The decoration group the search matches are tinted in, apart from the highlights and the note flash. */
internal const val EpubSearchDecorationGroup = "search"

/** A search match: a see-through [tint] behind the words, stronger with a line under them when it is the [current] one. */
internal data class EpubSearchMatchStyle(
    @ColorInt override val tint: Int,
    val current: Boolean,
) : Decoration.Style, Decoration.Style.Tinted {
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tint)
        dest.writeInt(if (current) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<EpubSearchMatchStyle> {
        override fun createFromParcel(source: Parcel): EpubSearchMatchStyle =
            EpubSearchMatchStyle(tint = source.readInt(), current = source.readInt() == 1)

        override fun newArray(size: Int): Array<EpubSearchMatchStyle?> = arrayOfNulls(size)
    }
}

/** The match's tint: amber, which none of the highlight colours is, so a match never reads as one. */
@ColorInt
internal val EpubSearchTint: Int = Color.rgb(232, 163, 61)

/** Readium's templates plus the search match, drawn once per line of the words (BOXES), over the text. */
internal fun HtmlDecorationTemplates.withEpubSearchStyle(): HtmlDecorationTemplates = copy().apply {
    // On the box Readium places over each line itself: a child inside it didn't show on the Fire's WebView.
    set(
        EpubSearchMatchStyle::class,
        HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            width = HtmlDecorationTemplate.Width.WRAP,
            element = ::searchMatchElement,
        ),
    )
}

/** The decorations for [matches]: one per match, the one at [currentIndex] marked as current. */
internal fun epubSearchDecorations(
    matches: List<EpubSearchResult>,
    currentIndex: Int?,
): List<Decoration> = matches.mapIndexed { index, match ->
    Decoration(
        id = "search-$index",
        locator = match.locator,
        style = EpubSearchMatchStyle(tint = EpubSearchTint, current = index == currentIndex),
    )
}

internal fun searchMatchElement(decoration: Decoration): String {
    val style = decoration.style as? EpubSearchMatchStyle
    val tint = style?.tint ?: EpubSearchTint
    val rgb = "${Color.red(tint)}, ${Color.green(tint)}, ${Color.blue(tint)}"
    val css = if (style?.current == true) {
        "background-color: rgba($rgb, $CurrentMatchAlpha); box-shadow: inset 0 -2px 0 rgb($rgb); border-radius: 2px;"
    } else {
        "background-color: rgba($rgb, $OtherMatchAlpha); border-radius: 2px;"
    }
    return "<div style=\"$css\"></div>"
}

private const val CurrentMatchAlpha = 0.55
private const val OtherMatchAlpha = 0.22

/**
 * Tints the search matches on whichever navigator shows the book. The host makes a new navigator when
 * the layout changes, so [attach] gives each one the matches again; Readium puts them back on every
 * page it loads.
 */
internal class EpubSearchLayer(private val scope: CoroutineScope) {
    private var navigator: EpubNavigatorFragment? = null
    private var decorations: List<Decoration> = emptyList()
    private var applyJob: Job? = null

    fun attach(fragment: EpubNavigatorFragment) {
        navigator = fragment
        apply()
    }

    fun detach(fragment: EpubNavigatorFragment) {
        if (navigator === fragment) {
            navigator = null
            applyJob?.cancel()
        }
    }

    fun show(next: List<Decoration>) {
        if (next == decorations) return
        decorations = next
        apply()
    }

    private fun apply() {
        val fragment = navigator ?: return
        val shown = decorations
        applyJob?.cancel()
        applyJob = scope.launch { fragment.applyDecorations(shown, EpubSearchDecorationGroup) }
    }
}
