package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.ui.components.AppScreenTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookmarksPageTransitionTest {
    private val book = BookmarksPage.Book("book-1")

    @Test
    fun `back steps out of a book and the review, and the books have nothing behind them`() {
        assertNull(bookmarksBackTarget(BookmarksPage.Library))
        assertEquals(BookmarksPage.Library, bookmarksBackTarget(book))
        assertEquals(BookmarksPage.Library, bookmarksBackTarget(BookmarksPage.Review(from = BookmarksPage.Library)))
        assertEquals(book, bookmarksBackTarget(BookmarksPage.Review(from = book)))
    }

    @Test
    fun `back goes where the state's own back goes`() {
        val state = BookmarksState()
        state.openBook("book-1")
        state.openReview()
        val target = bookmarksBackTarget(state.page)
        state.back()
        assertEquals(target, state.page)
        val next = bookmarksBackTarget(state.page)
        state.back()
        assertEquals(next, state.page)
    }

    @Test
    fun `deeper pages slide in and back out`() {
        assertEquals(AppScreenTransition.Forward, bookmarksPageTransition(BookmarksPage.Library, book))
        assertEquals(AppScreenTransition.Backward, bookmarksPageTransition(book, BookmarksPage.Library))
        assertEquals(AppScreenTransition.Forward, bookmarksPageTransition(book, BookmarksPage.Review(from = book)))
        assertEquals(AppScreenTransition.Backward, bookmarksPageTransition(BookmarksPage.Review(from = book), book))
    }
}
