package com.example.unifiedreader.feature.annotations

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/*
 * Stepping through a book's marks: after a highlight or bookmark is opened from a list (the
 * Bookmarks page, the book's page or the marks panel), the reader shows a strip with Previous and
 * Next, which go to the mark before or after it in the book, highlights and bookmarks together.
 */

/** A highlight or bookmark as the steps go through them: where it is, and what the strip calls it. */
data class MarkStop(
    val id: String,
    val locatorJson: String,
    val progression: Double?,
    val createdAtEpochMillis: Long,
    val label: String,
    val isBookmark: Boolean,
)

/**
 * A book's highlights and bookmarks in reading order, as the lists have them: by place, those without
 * one last, then oldest first. A bookmark from the server not yet found in this copy has no place to go to,
 * so it is left out.
 */
fun markStops(highlights: List<ReaderHighlight>, bookmarks: List<ReaderBookmark>): List<MarkStop> {
    val stops = highlights.map { highlight ->
        MarkStop(
            id = highlight.id,
            locatorJson = highlight.place.locatorJson,
            progression = highlight.place.progression,
            createdAtEpochMillis = highlight.createdAtEpochMillis,
            label = "“${highlight.text.oneLine()}”",
            isBookmark = false,
        )
    } + bookmarks.filter { it.place.locatorJson.isNotEmpty() }.map { bookmark ->
        MarkStop(
            id = bookmark.id,
            locatorJson = bookmark.place.locatorJson,
            progression = bookmark.place.progression,
            createdAtEpochMillis = bookmark.createdAtEpochMillis,
            label = bookmark.stepLabel(),
            isBookmark = true,
        )
    }
    return stops.sortedWith(MarkStopOrder)
}

private val MarkStopOrder = compareBy<MarkStop>(
    { it.progression == null },
    { it.progression },
    { it.createdAtEpochMillis },
)

private fun ReaderBookmark.stepLabel(): String =
    title?.trim()?.takeIf(String::isNotEmpty)
        ?: chapterTitle()?.let { chapter -> page?.let { "$chapter · ${MarkStepsCopy.page(it)}" } ?: chapter }
        ?: page?.let(MarkStepsCopy::page)
        ?: text?.oneLine()?.takeIf(String::isNotEmpty)
        ?: MarkStepsCopy.Bookmark

private fun ReaderBookmark.chapterTitle(): String? = place.chapterTitle?.trim()?.takeIf(String::isNotEmpty)

/**
 * The marks of the open book ([stops], in reading order) and the one the reader opened from a list
 * ([current]); null [current] while no mark was opened, when the strip is hidden. A step moves
 * [current] on, so the next one goes from there.
 */
@Stable
class MarkSteps {
    var current by mutableStateOf<MarkStop?>(null)
        private set

    var stops by mutableStateOf<List<MarkStop>>(emptyList())
        private set

    // A place opened before the book's marks had loaded: found once they have.
    private var pending: ((MarkStop) -> Boolean)? = null

    /** [current] as the book has it now (a name or note may have changed since). */
    val shown: MarkStop?
        get() = current?.let { opened -> stops.firstOrNull { it.id == opened.id } ?: opened }

    /** "3 of 12", or null while the strip is hidden. */
    val position: String?
        get() = current?.let(stops::stepPosition)

    /** The book's marks as they are now. */
    fun update(stops: List<MarkStop>) {
        this.stops = stops
        pending?.let(::find)
    }

    /**
     * The reader went to a place from a list: the mark there ([isAt]) becomes the current one and the
     * strip shows; a place with no mark (a saved word) hides it.
     */
    fun opened(isAt: (MarkStop) -> Boolean) {
        current = null
        pending = isAt
        find(isAt)
    }

    /** The strip's Close: it goes, until another mark is opened from a list. */
    fun close() {
        current = null
        pending = null
    }

    /**
     * The mark before ([forward] false) or after [current], made the current one; null at either end
     * (or with no mark opened), which leaves the reader where it is.
     */
    fun step(forward: Boolean): MarkStop? {
        val next = current?.let { from -> stops.stepFrom(from, forward) }
        if (next != null) current = next
        return next
    }

    private fun find(isAt: (MarkStop) -> Boolean) {
        val stop = stops.firstOrNull(isAt)
        if (stop != null) current = stop
        if (stop != null || stops.isNotEmpty()) pending = null
    }
}

/** The steps through one book's marks ([key]), kept up with its [highlights] and [bookmarks]. */
@Composable
fun rememberMarkSteps(
    key: Any?,
    highlights: List<ReaderHighlight>,
    bookmarks: List<ReaderBookmark>,
): MarkSteps {
    val steps = remember(key) { MarkSteps() }
    val stops = remember(highlights, bookmarks) { markStops(highlights, bookmarks) }
    LaunchedEffect(steps, stops) { steps.update(stops) }
    return steps
}

/**
 * The mark before or after [from] among [this] (in reading order). One removed since it was opened
 * still has its place, so the steps go on from where it was.
 */
fun List<MarkStop>.stepFrom(from: MarkStop, forward: Boolean): MarkStop? {
    val index = indexOfFirst { it.id == from.id }
    if (index >= 0) return getOrNull(if (forward) index + 1 else index - 1)
    return if (forward) firstOrNull { MarkStopOrder.compare(it, from) > 0 } else lastOrNull { MarkStopOrder.compare(it, from) < 0 }
}

/** "3 of 12" for the strip, or the count alone when [current] is no longer among [stops]. */
fun List<MarkStop>.stepPosition(current: MarkStop): String {
    val index = indexOfFirst { it.id == current.id }
    return if (index >= 0) MarkStepsCopy.position(index + 1, size) else MarkCounts.count(size, "mark")
}

/** What the strip says. */
object MarkStepsCopy {
    const val Previous = "Previous mark"
    const val Next = "Next mark"
    const val Close = "Stop going through marks"
    const val Bookmark = "Bookmark"

    fun position(at: Int, of: Int): String = "$at of $of"

    fun page(page: Int): String = "Page $page"
}
