package com.example.unifiedreader.feature.library

import com.example.unifiedreader.core.model.LibraryFileLocation
import com.example.unifiedreader.core.model.LibraryReadingStatus
import com.example.unifiedreader.core.model.LibrarySortMode
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.ReadableItemKind
import com.example.unifiedreader.core.model.comicVolumeLabel
import com.example.unifiedreader.core.model.isGeneratedComicVolumeTitle
import com.example.unifiedreader.core.model.libraryAlphabetKey
import com.example.unifiedreader.core.model.libraryPrimaryText
import com.example.unifiedreader.core.model.librarySecondaryText
import com.example.unifiedreader.core.model.ownIssueTitle
import com.example.unifiedreader.core.model.volumeNumber
import kotlin.math.floor
import kotlin.math.roundToInt

data class ItemProgressSummary(
    val label: String,
    val fraction: Float,
    val currentPage: Int? = null,
    val pageCount: Int? = null,
    val lastReadAtEpochMillis: Long = 0L,
    val isFinished: Boolean = false,
    // The chapter a book was left in (from 1), when its reader recorded one.
    val chapter: Int? = null,
    val chapterCount: Int? = null,
    val chapterTitle: String? = null,
    // Where each chapter starts, as a share of the book; empty when the reader didn't say.
    val chapterStarts: List<Float> = emptyList(),
) {
    /** Rounded, as the readers' own percentages are, so the card and the reader agree. */
    val percentLabel: String
        get() = "${(fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"

    /**
     * The page to show: the one the reader saved, else the page the share read falls on, as the readers
     * count pages (page n starts (n - 1) / pages of the way in, so 5/78 of the way is page 6). A place that
     * came from a server that keeps only a percentage has no saved page.
     */
    val shownPage: Int?
        get() {
            val count = pageCount?.takeIf { it > 0 } ?: return null
            val page = currentPage
                ?: (floor(fraction.coerceIn(0f, 1f).toDouble() * count + PAGE_EDGE_SLACK).toInt() + 1)
            return page.coerceIn(1, count)
        }

    val pageProgressLabel: String?
        get() = shownPage?.let { page -> "$page/$pageCount" }

    val detailLabel: String
        get() = listOfNotNull(percentLabel, pageProgressLabel).joinToString(" · ")

    /**
     * Opened but not yet begun: under 1%, or still on the first page (a comic counts the page on
     * screen as read, so page 1 of 8 would say 13%). It isn't In progress yet, and tiles say
     * "Just started" without a bar.
     */
    val isJustStarted: Boolean
        get() = !isFinished && fraction < CompleteFraction &&
            (fraction < JustStartedFraction || (currentPage == 1 && (pageCount ?: 0) > 1))

    /** What a tile says about progress: the percentage, "Just started", or nothing once read. */
    val tileLabel: String?
        get() = when {
            isFinished || fraction >= CompleteFraction -> null
            isJustStarted -> "Just started"
            else -> percentLabel
        }

    /** How far the bar on a tile's cover fills, while the tile shows a percentage. */
    val tileBarFraction: Float?
        get() = fraction.takeIf { tileLabel != null && !isJustStarted }

    private companion object {
        const val CompleteFraction = 0.999f
        const val JustStartedFraction = 0.01f
    }
}

enum class LibraryViewMode {
    Grid,
}

internal val LibraryViewMode.label: String
    get() = when (this) {
        LibraryViewMode.Grid -> "Grid"
    }

internal fun itemPrimaryText(
    item: ReadableItem,
): String = item.libraryPrimaryText()

internal fun itemSeriesBrowsePrimaryText(
    item: ReadableItem,
): String = itemIssueBrowsePrimaryText(item, includeSeries = false)

internal fun itemMixedIssueBrowsePrimaryText(
    item: ReadableItem,
): String = itemIssueBrowsePrimaryText(item, includeSeries = true)

private fun itemIssueBrowsePrimaryText(
    item: ReadableItem,
    includeSeries: Boolean,
): String {
    if (item.kind != ReadableItemKind.COMIC) return itemPrimaryText(item)
    val metadata = item.comicMetadata ?: return itemPrimaryText(item)
    val series = metadata.series?.trim()?.takeIf(String::isNotBlank)
    val issueNumber = metadata.issueNumber?.trim()?.takeIf(String::isNotBlank)
    // A Kavita volume ("Chapter v1", number "v1") reads "Volume 1".
    val issueNumberLabel = metadata.volumeNumber()?.let(::comicVolumeLabel)
        ?: issueNumber?.let { number -> "Issue $number" }
    val issueTitle = metadata.ownIssueTitle()
        ?.withoutSeriesPrefix(series)
        ?.withoutIssueNumberPrefix(issueNumber)
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { title -> series != null && title.equals(series, ignoreCase = true) }
        ?.takeUnless { title -> issueNumberLabel != null && title.equals(issueNumberLabel, ignoreCase = true) }
    val fallbackTitle = item.title
        .trim()
        .takeIf(String::isNotBlank)
        ?.takeUnless(::isGeneratedComicVolumeTitle)
        ?.withoutSeriesPrefix(series)
        ?.withoutIssueNumberPrefix(issueNumber)
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { title -> series != null && title.equals(series, ignoreCase = true) }
    val title = issueTitle ?: fallbackTitle
    val label = listOfNotNull(series.takeIf { includeSeries }, issueNumberLabel, title)
        .distinctBy { part -> part.lowercase() }
        .joinToString(" - ")
    return label.ifBlank {
        title ?: itemPrimaryText(item)
    }
}

internal fun itemSecondaryText(
    item: ReadableItem,
): String? = item.librarySecondaryText()

internal val LibrarySortMode.label: String
    get() = when (this) {
        LibrarySortMode.DATE_ADDED -> "Date added"
        LibrarySortMode.LAST_READ -> "Last read"
        LibrarySortMode.AUTHOR -> "Author"
        LibrarySortMode.TITLE -> "Title"
        LibrarySortMode.SERIES -> "Series"
        LibrarySortMode.ISSUE_COUNT -> "Number of issues"
        LibrarySortMode.PAGES -> "Pages"
    }

internal val LibrarySortMode.usesAlphabetRail: Boolean
    get() = when (this) {
        LibrarySortMode.AUTHOR,
        LibrarySortMode.TITLE,
        LibrarySortMode.SERIES -> true
        // Every cover says its length, and nobody jumps to "150 pages": no rail, as by date.
        LibrarySortMode.PAGES,
        LibrarySortMode.DATE_ADDED,
        LibrarySortMode.LAST_READ,
        LibrarySortMode.ISSUE_COUNT -> false
    }

internal val LibraryFileLocation.label: String
    get() = when (this) {
        LibraryFileLocation.SERVER -> "Server"
        LibraryFileLocation.LOCAL -> "Local"
    }

internal val LibraryReadingStatus.label: String
    get() = when (this) {
        LibraryReadingStatus.NOT_STARTED -> "Not started"
        LibraryReadingStatus.IN_PROGRESS -> "In progress"
        LibraryReadingStatus.FINISHED -> "Finished"
    }

internal fun ReadableItem.sortFilterLetter(
    sortMode: LibrarySortMode,
): String? = libraryAlphabetKey(sortMode)?.filterLetter { it }

private fun String.withoutSeriesPrefix(
    series: String?,
): String {
    if (series.isNullOrBlank() || !startsWith(series, ignoreCase = true)) return this
    return drop(series.length)
        .trim()
        .trimStart('-', ':', '.', '#')
        .trim()
}

private fun String.withoutIssueNumberPrefix(
    issueNumber: String?,
): String {
    val number = issueNumber?.trim()?.takeIf(String::isNotBlank) ?: return this
    val escapedNumber = Regex.escape(number)
    val patterns = listOf(
        Regex("""(?i)^issue\s+#?$escapedNumber\b\s*[-:.\s]*"""),
        Regex("""^#?$escapedNumber\b\s*[-:.\s]*"""),
    )
    return patterns.fold(this) { value, pattern ->
        value.replaceFirst(pattern, "").trim()
    }
}

internal fun <T> Set<T>.toggle(value: T): Set<T> =
    if (value in this) this - value else this + value

internal fun String.filterLetter(
    letterKey: (String) -> String,
): String? {
    val first = letterKey(this).trim().firstOrNull() ?: return null
    return if (first.isLetter()) {
        first.uppercaseChar().toString()
    } else {
        "#"
    }
}

// A share read stored as a Float lands a hair before the page it starts (5/78 x 78 = 4.9999995).
private const val PAGE_EDGE_SLACK = 0.001
