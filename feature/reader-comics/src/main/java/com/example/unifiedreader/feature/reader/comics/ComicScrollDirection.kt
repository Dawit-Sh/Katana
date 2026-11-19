package com.example.unifiedreader.feature.reader.comics

import com.example.unifiedreader.feature.reader.preferences.ComicReaderMode
import com.example.unifiedreader.feature.reader.preferences.ComicReadingDirection

/**
 * Which way a scrolling comic runs: [Vertical], top to bottom (webtoons), or [Horizontal], sideways in the
 * comic's reading direction (left to right, or right to left for manga). Kept per book.
 */
enum class ComicScrollAxis {
    Vertical,
    Horizontal,
}

/** The comics reader's "Direction" choice, in the words the settings show. */
internal enum class ComicDirectionChoice(
    val label: String,
) {
    TopToBottom("Top to bottom"),
    LeftToRight("Left to right"),
    RightToLeft("Right to left"),
}

/** What a [ComicDirectionChoice] sets: the scroll axis and the reading direction, or null to keep either as it is. */
internal data class ComicDirectionPick(
    val axis: ComicScrollAxis?,
    val direction: ComicReadingDirection?,
)

/** Turning pages goes left to right or right to left; scrolling can also go top to bottom. */
internal fun comicDirectionChoices(mode: ComicReaderMode): List<ComicDirectionChoice> = when (mode) {
    ComicReaderMode.Paged -> listOf(ComicDirectionChoice.LeftToRight, ComicDirectionChoice.RightToLeft)
    ComicReaderMode.VerticalScroll -> ComicDirectionChoice.entries
}

/** The choice the settings show as picked for a comic read in [mode] with [direction] and [axis]. */
internal fun comicDirectionChoice(
    mode: ComicReaderMode,
    direction: ComicReadingDirection,
    axis: ComicScrollAxis,
): ComicDirectionChoice = when {
    mode == ComicReaderMode.VerticalScroll && axis == ComicScrollAxis.Vertical -> ComicDirectionChoice.TopToBottom
    direction == ComicReadingDirection.RTL -> ComicDirectionChoice.RightToLeft
    else -> ComicDirectionChoice.LeftToRight
}

/**
 * What picking [choice] changes. Top to bottom only sets the axis, so the comic keeps its reading direction for
 * when it turns pages again; left or right sets the direction, and while scrolling also turns the strip sideways.
 */
internal fun comicDirectionPick(
    choice: ComicDirectionChoice,
    mode: ComicReaderMode,
): ComicDirectionPick {
    val scrolling = mode == ComicReaderMode.VerticalScroll
    return when (choice) {
        ComicDirectionChoice.TopToBottom -> ComicDirectionPick(axis = ComicScrollAxis.Vertical, direction = null)
        ComicDirectionChoice.LeftToRight -> ComicDirectionPick(
            axis = ComicScrollAxis.Horizontal.takeIf { scrolling },
            direction = ComicReadingDirection.LTR,
        )
        ComicDirectionChoice.RightToLeft -> ComicDirectionPick(
            axis = ComicScrollAxis.Horizontal.takeIf { scrolling },
            direction = ComicReadingDirection.RTL,
        )
    }
}

/** The "Two pages" row's words: two pages side by side when the screen is wide (Auto), never, or always. */
internal fun comicSpreadLabel(mode: ComicSpreadMode): String = when (mode) {
    ComicSpreadMode.Auto -> "Auto"
    ComicSpreadMode.Single -> "Off"
    ComicSpreadMode.Double -> "On"
}
