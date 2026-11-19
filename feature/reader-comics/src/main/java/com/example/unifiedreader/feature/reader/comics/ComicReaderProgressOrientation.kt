package com.example.unifiedreader.feature.reader.comics

import com.example.unifiedreader.feature.reader.preferences.ComicReaderMode
import com.example.unifiedreader.feature.reader.preferences.ComicReadingDirection

/**
 * Which way the dock's slider fills: the way the pages go. Turning pages or scrolling sideways, that is the
 * comic's reading direction; scrolling top to bottom, left to right.
 */
internal fun comicProgressBarDirection(
    readerMode: ComicReaderMode,
    readingDirection: ComicReadingDirection,
    scrollAxis: ComicScrollAxis = ComicScrollAxis.Vertical,
): ComicReadingDirection = when {
    readerMode == ComicReaderMode.Paged -> readingDirection
    scrollAxis == ComicScrollAxis.Horizontal -> readingDirection
    else -> ComicReadingDirection.LTR
}
