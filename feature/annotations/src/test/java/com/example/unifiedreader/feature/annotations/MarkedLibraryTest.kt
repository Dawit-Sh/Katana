package com.example.unifiedreader.feature.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkedLibraryTest {
    private val hole = AnnotatedBook("grimmory:item:2694", "The Hole", "Hiroko Oyamada")
    private val suspect = AnnotatedBook("grimmory:item:7", "The Devotion of Suspect X", "Keigo Higashino")
    private val trees = AnnotatedBook("local:item:3", "The Trees", "Percival Everett")

    private val library = MarkedLibrary.of(
        highlights = listOf(
            highlight("h1", hole, "fragments", at = 0.2, updated = 50),
            highlight("h2", suspect, "the perfect alibi", at = 0.5, updated = 30).copy(note = "Ishigami again"),
            highlight("h3", hole, "the", at = 0.1, updated = 40),
        ),
        bookmarks = listOf(bookmark("b1", hole, "home looked like", at = 0.09, made = 60)),
        words = listOf(
            word("w1", trees, "lynching", "the record of lynchings", made = 10),
            word("w2", hole, "solar", "solar panels on the roof", made = 45),
        ),
    )

    @Test
    fun `every book with a mark is there once, with its marks in reading order and the totals`() {
        assertEquals(listOf(hole, suspect, trees), library.books.map(MarkedBook::book))
        val theHole = library.book(hole.itemId)!!
        assertEquals(listOf("h3", "h1"), theHole.highlights.map(ReaderHighlight::id))
        assertEquals(listOf("b1"), theHole.bookmarks.map(ReaderBookmark::id))
        assertEquals(listOf("w2"), theHole.words.map(SavedWord::id))
        assertEquals(60L, theHole.lastMarkedAtEpochMillis)
        assertEquals(3, library.highlightCount)
        assertEquals(1, library.bookmarkCount)
        assertEquals(2, library.wordCount)
        assertNull(library.book("nothing"))
    }

    @Test
    fun `the filter keeps the books with that kind of mark`() {
        assertEquals(listOf(hole), library.shown(MarkKind.Bookmarks, "", MarkedBookOrder.LastMarked).map(MarkedBook::book))
        assertEquals(listOf(hole, trees), library.shown(MarkKind.Words, "", MarkedBookOrder.LastMarked).map(MarkedBook::book))
        assertEquals(listOf(hole, suspect), library.shown(MarkKind.Highlights, "", MarkedBookOrder.LastMarked).map(MarkedBook::book))
    }

    @Test
    fun `search finds a book by its name, its author, a quote, a note, a word or a bookmark's words`() {
        fun found(query: String, kind: MarkKind = MarkKind.All) =
            library.shown(kind, query, MarkedBookOrder.LastMarked).map { it.book.itemId }
        assertEquals(listOf(hole.itemId), found("hole"))
        assertEquals(listOf(trees.itemId), found("EVERETT"))
        assertEquals(listOf(suspect.itemId), found("alibi"))
        assertEquals(listOf(suspect.itemId), found("ishigami"))
        assertEquals(listOf(hole.itemId), found("solar"))
        assertEquals(listOf(hole.itemId), found("looked like"))
        // Within a filter, only that kind of mark is searched.
        assertTrue(found("solar", MarkKind.Highlights).isEmpty())
        assertTrue(found("nothing like this").isEmpty())
    }

    @Test
    fun `a bookmark is found by its page and its chapter too, so a scan's bookmark turns up`() {
        val scan = bookmark("b2", trees, text = null, at = 0.5, made = 70).copy(page = 120, pageCount = 300)
        val chaptered = bookmark("b3", suspect, "a quiet morning", at = 0.2, made = 80)
            .let { it.copy(place = it.place.copy(chapterTitle = "Chapter Four")) }

        assertTrue(MarkSearch.matches(scan, "page 120"))
        assertTrue(MarkSearch.matches(scan, "120 of 300"))
        assertTrue(MarkSearch.matches(chaptered, "chapter four"))
        assertTrue(MarkSearch.matches(chaptered, "  QUIET "))
        assertTrue(MarkSearch.matches(scan, ""))
        assertTrue(!MarkSearch.matches(scan, "chapter four"))
    }

    @Test
    fun `a book's page shows only the marks a search finds, and every mark without one`() {
        val theHole = library.book(hole.itemId)!!

        val found = theHole.only("looked")
        assertEquals(listOf("b1"), found.bookmarks.map(ReaderBookmark::id))
        assertTrue(found.highlights.isEmpty() && found.words.isEmpty())
        assertEquals(listOf("h3", "h1"), theHole.only("").highlights.map(ReaderHighlight::id))
        assertEquals(theHole, theHole.only("  "))
    }

    @Test
    fun `a book opened from a search starts with that search`() {
        val state = BookmarksState()
        state.query = "looked"

        state.openBook(hole.itemId)

        assertEquals("looked", state.bookQuery)
    }

    @Test
    fun `books come in the order of their last mark, or by title`() {
        assertEquals(listOf(hole, suspect, trees), library.shown(MarkKind.All, "", MarkedBookOrder.LastMarked).map(MarkedBook::book))
        assertEquals(listOf(suspect, hole, trees), library.shown(MarkKind.All, "", MarkedBookOrder.Title).map(MarkedBook::book))
    }

    @Test
    fun `a book takes the name its latest mark gave it`() {
        val renamed = hole.copy(title = "The Hole (new edition)")
        val marked = MarkedLibrary.of(
            highlights = listOf(highlight("h1", hole, "fragments", at = 0.2, updated = 5)),
            bookmarks = listOf(bookmark("b1", renamed, "page", at = 0.3, made = 9)),
            words = emptyList(),
        )
        assertEquals(renamed, marked.books.single().book)
    }

    @Test
    fun `the summary counts books and every kind of mark`() {
        assertEquals("3 books · 3 highlights · 1 bookmark · 2 saved words", BookmarksCopy.librarySummary(library))
        assertEquals("2 highlights · 1 bookmark · 1 word", BookmarksCopy.bookSummary(library.book(hole.itemId)!!))
        assertTrue(MarkedLibrary.Empty.isEmpty)
    }

    private fun highlight(id: String, book: AnnotatedBook, text: String, at: Double, updated: Long) =
        ReaderHighlight(
            id = id,
            book = book,
            place = AnnotationPlace(href = "c.xhtml", locatorJson = "{}", chapterTitle = null, progression = at),
            text = text,
            style = HighlightStyle.Highlight,
            color = HighlightColor.Yellow,
            note = null,
            createdAtEpochMillis = updated,
            updatedAtEpochMillis = updated,
        )

    private fun bookmark(id: String, book: AnnotatedBook, text: String?, at: Double, made: Long) = ReaderBookmark(
        id = id,
        book = book,
        place = AnnotationPlace(href = "c.xhtml", locatorJson = "{}", chapterTitle = null, progression = at),
        page = 8,
        pageCount = 78,
        text = text,
        createdAtEpochMillis = made,
    )

    private fun word(id: String, book: AnnotatedBook, word: String, sentence: String, made: Long) = SavedWord(
        id = id,
        book = book,
        word = word,
        sentence = sentence,
        place = null,
        note = null,
        createdAtEpochMillis = made,
    )
}
