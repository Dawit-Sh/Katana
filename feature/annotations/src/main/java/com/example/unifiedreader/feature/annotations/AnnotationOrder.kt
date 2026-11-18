package com.example.unifiedreader.feature.annotations

/** Highlights as the book has them: by place, those without one last, then oldest first. */
fun List<ReaderHighlight>.inReadingOrder(): List<ReaderHighlight> =
    sortedWith(
        compareBy<ReaderHighlight>(
            { it.place.progression == null },
            { it.place.progression },
            { it.createdAtEpochMillis },
        ),
    )

/** Saved words as the book has them: by place, those without one last, then oldest first. */
fun List<SavedWord>.inReadingOrderWords(): List<SavedWord> =
    sortedWith(
        compareBy<SavedWord>(
            { it.place?.progression == null },
            { it.place?.progression },
            { it.createdAtEpochMillis },
        ),
    )

/** Bookmarks as the book has them: by place, those without one last, then oldest first. */
fun List<ReaderBookmark>.inReadingOrderBookmarks(): List<ReaderBookmark> =
    sortedWith(
        compareBy<ReaderBookmark>(
            { it.place.progression == null },
            { it.place.progression },
            { it.createdAtEpochMillis },
        ),
    )

/** The text on one line, its runs of white space as single spaces. */
internal fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()
