package com.example.unifiedreader.feature.reader.preferences

/**
 * How a book's pages go by, the "Pages" choice every reader's settings start with: [Turn] one page
 * (or spread) at a time, or [Scroll] through the book as one continuous strip.
 */
enum class ReaderPagesMode(
    val label: String,
) {
    Turn("Turn"),
    Scroll("Scroll"),
}

/** The label of the "Pages" row in every reader's settings, and of its line in Settings. */
const val ReaderPagesLabel = "Pages"
