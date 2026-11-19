package com.example.unifiedreader.feature.reader.preferences

/**
 * How a comic's pages go by, in the same words as every reader's "Pages" choice: turning one page (or
 * spread) at a time, or scrolling as one strip. Saved by name, so the names stay as they were.
 */
enum class ComicReaderMode(
    val label: String,
) {
    Paged(ReaderPagesMode.Turn.label),
    VerticalScroll(ReaderPagesMode.Scroll.label),
}
