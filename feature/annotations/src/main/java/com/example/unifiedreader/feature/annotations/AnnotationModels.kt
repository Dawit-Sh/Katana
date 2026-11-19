package com.example.unifiedreader.feature.annotations

/** How a highlight marks its words. Stored by [name]. */
enum class HighlightStyle {
    /** The words on a coloured background. */
    Highlight,

    /** A coloured line under the words. */
    Underline,

    /** A coloured line through the words. */
    Strikethrough,

    /** A wavy coloured line under the words. */
    Squiggly,
    ;

    companion object {
        val Default: HighlightStyle = Highlight

        fun fromStored(value: String?): HighlightStyle = entries.firstOrNull { it.name == value } ?: Default
    }
}

/**
 * A highlight's colour. [argb] is the full colour (lines and the list's swatch); the reader lays a
 * filled highlight at [fillAlpha] over the page, so the text keeps its own colour on every page
 * colour, light or dark. Stored by [name].
 */
enum class HighlightColor(val argb: Long) {
    Yellow(YELLOW_ARGB),
    Green(GREEN_ARGB),
    Blue(BLUE_ARGB),
    Pink(PINK_ARGB),
    Orange(ORANGE_ARGB),
    ;

    val fillAlpha: Float get() = FILL_ALPHA

    companion object {
        val Default: HighlightColor = Yellow

        fun fromStored(value: String?): HighlightColor = entries.firstOrNull { it.name == value } ?: Default
    }
}

private const val FILL_ALPHA = 0.38f
private const val YELLOW_ARGB = 0xFFF2C230
private const val GREEN_ARGB = 0xFF5DBB63
private const val BLUE_ARGB = 0xFF4F8EDB
private const val PINK_ARGB = 0xFFE2649A
private const val ORANGE_ARGB = 0xFFEE8A3A

/** The book a mark belongs to, as an export names it. */
data class AnnotatedBook(
    val itemId: String,
    val title: String,
    val author: String?,
)

/**
 * Where in a book a mark sits: the reader's own locator (Readium JSON, which finds the words again
 * at any text size), the chapter file and title, and how far through the book (0..1), which orders
 * marks in reading order.
 */
data class AnnotationPlace(
    val href: String,
    val locatorJson: String,
    val chapterTitle: String?,
    val progression: Double?,
)

/** A highlight made on a book's text. */
data class ReaderHighlight(
    val id: String,
    val book: AnnotatedBook,
    val place: AnnotationPlace,
    val text: String,
    val style: HighlightStyle,
    val color: HighlightColor,
    val note: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/**
 * A word saved from a book to look up later, with the sentence it came from. [reviewLevel] counts
 * the flashcard reviews it was known in a row; it is next due at [reviewDueAtEpochMillis] (null:
 * never reviewed, so due now).
 */
data class SavedWord(
    val id: String,
    val book: AnnotatedBook,
    val word: String,
    val sentence: String?,
    val place: AnnotationPlace?,
    val note: String?,
    val createdAtEpochMillis: Long,
    val reviewLevel: Int = 0,
    val reviewDueAtEpochMillis: Long? = null,
    val reviewedAtEpochMillis: Long? = null,
)

/**
 * A page bookmarked in a book: where it starts ([place]), which page of how many it was when it
 * was made, and the page's first words ([text]) to recognise it by; the reader's own name for it
 * ([title]) and [note], when given. [isPlaced] is false for one from the server not yet found in
 * this device's copy of the book (it opens the book where it was left until then).
 */
data class ReaderBookmark(
    val id: String,
    val book: AnnotatedBook,
    val place: AnnotationPlace,
    val page: Int?,
    val pageCount: Int?,
    val text: String?,
    val createdAtEpochMillis: Long,
    val title: String? = null,
    val note: String? = null,
) {
    val isPlaced: Boolean
        get() = place.locatorJson.isNotEmpty()
}

/** What a new highlight gets when none is chosen: the style and colour of the last one made. */
data class HighlightLook(
    val style: HighlightStyle = HighlightStyle.Default,
    val color: HighlightColor = HighlightColor.Default,
)
