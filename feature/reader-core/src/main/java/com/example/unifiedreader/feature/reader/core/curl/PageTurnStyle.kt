package com.example.unifiedreader.feature.reader.core.curl

/** How a paged reader turns pages; saved by name. [None] changes the page at once, with no animation. */
enum class PageTurnStyle(
    val label: String,
) {
    Slide("Slide"),
    Curl("Curl"),
    None("None"),
}
