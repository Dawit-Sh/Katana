package com.example.unifiedreader.feature.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMarksPanelTest {
    private val book = AnnotatedBook("book-1", "Field Notes", null)

    @Test
    fun `highlights sit under their chapters, a heading each time the chapter changes`() {
        val rows = bookMarksRows(
            bookmarks = listOf(bookmark("b1", 0.1)),
            highlights = listOf(
                highlight("h3", "Chapter 2", 0.6),
                highlight("h1", "Chapter 1", 0.2),
                highlight("h2", "Chapter 1", 0.3),
            ),
            words = emptyList(),
        )

        assertEquals(
            listOf("t-Bookmarks · 1", "b-b1", "t-Highlights", "c-Chapter 1", "h-h1", "h-h2", "c-Chapter 2", "h-h3"),
            rows.map { row -> if (row is BookMarksRow.Chapter) "c-${row.text}" else row.key },
        )
    }

    @Test
    fun `a PDF's highlights sit under their pages`() {
        // As pageHighlightPlace names it (its locator needs Android's JSON, so it's left out here).
        val rows = bookMarksRows(
            bookmarks = emptyList(),
            highlights = listOf(highlightAt("p", AnnotationPlace("page:4", "{}", pageIndexTitle(4), 0.4))),
            words = emptyList(),
        )

        assertTrue(rows.any { it is BookMarksRow.Chapter && it.text == "Page 5" })
    }

    @Test
    fun `the list opens at the highlights around the reader's place, its chapter heading showing`() {
        val rows = bookMarksRows(
            bookmarks = listOf(bookmark("b1", 0.1)),
            highlights = listOf(
                highlight("h1", "Chapter 1", 0.2),
                highlight("h2", "Chapter 2", 0.5),
                highlight("h3", "Chapter 2", 0.7),
            ),
            words = emptyList(),
        )

        val start = bookMarksStartRow(rows, here = 0.45)

        assertEquals("c-Chapter 2", (rows[start] as BookMarksRow.Chapter).let { "c-${it.text}" })
        assertEquals(0, bookMarksStartRow(rows, here = null))
        // Past the last highlight: the last one.
        assertEquals("h-h3", rows[bookMarksStartRow(rows, here = 0.9)].key)
    }

    @Test
    fun `without highlights the list opens at the bookmarks around the reader's place`() {
        val rows = bookMarksRows(
            bookmarks = listOf(bookmark("b1", 0.1), bookmark("b2", 0.5), bookmark("b3", 0.8)),
            highlights = emptyList(),
            words = emptyList(),
        )

        assertEquals("b-b2", rows[bookMarksStartRow(rows, here = 0.4)].key)
    }

    private fun highlight(id: String, chapter: String, progression: Double) =
        highlightAt(id, AnnotationPlace("c.xhtml", "{}", chapter, progression))

    private fun highlightAt(id: String, place: AnnotationPlace) = ReaderHighlight(
        id = id,
        book = book,
        place = place,
        text = "words $id",
        style = HighlightStyle.Highlight,
        color = HighlightColor.Yellow,
        note = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun bookmark(id: String, progression: Double) = ReaderBookmark(
        id = id,
        book = book,
        place = AnnotationPlace("c.xhtml", "{}", null, progression),
        page = null,
        pageCount = null,
        text = null,
        createdAtEpochMillis = 1L,
    )
}
