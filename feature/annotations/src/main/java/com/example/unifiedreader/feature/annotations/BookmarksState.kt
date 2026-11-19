package com.example.unifiedreader.feature.annotations

import androidx.compose.runtime.Stable
import com.example.unifiedreader.ui.components.AppScreenTransition
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Where the Bookmarks screen is: every book, one book's marks, or the word review. */
sealed interface BookmarksPage {
    data object Library : BookmarksPage

    data class Book(val itemId: String) : BookmarksPage

    /** The flashcards; Back returns to [from]. */
    data class Review(val from: BookmarksPage = Library) : BookmarksPage
}

/**
 * The Bookmarks screen's state: its page, the search, filter and order of the books, the filter
 * on a book's page, and a word review under way. Kept outside the screen, so it is still there
 * after a book opened from it closes.
 */
@Stable
class BookmarksState {
    var page: BookmarksPage by mutableStateOf(BookmarksPage.Library)
        private set
    var query: String by mutableStateOf("")
    var kind: MarkKind by mutableStateOf(MarkKind.All)
    var order: MarkedBookOrder by mutableStateOf(MarkedBookOrder.LastMarked)
    var bookKind: MarkKind by mutableStateOf(MarkKind.All)

    /** The word review under way, if any. */
    var round: WordReviewRound? by mutableStateOf(null)

    /** Whether anything has scrolled under the bar (it then needs its glass). */
    var scrolled: Boolean by mutableStateOf(false)

    fun openBook(itemId: String) {
        bookKind = MarkKind.All
        scrolled = false
        page = BookmarksPage.Book(itemId)
    }

    fun openReview() {
        round = null
        scrolled = false
        page = BookmarksPage.Review(from = page.takeUnless { it is BookmarksPage.Review } ?: BookmarksPage.Library)
    }

    /** One page back; false on the books, which have nothing behind them. */
    fun back(): Boolean {
        val current = page
        page = when (current) {
            BookmarksPage.Library -> return false
            is BookmarksPage.Book -> BookmarksPage.Library
            is BookmarksPage.Review -> current.from.also { round = null }
        }
        scrolled = false
        return true
    }

    /** Straight back to the books, as picking Bookmarks in the menu does. */
    fun home() {
        page = BookmarksPage.Library
        round = null
        scrolled = false
    }
}

/** Where Back goes from [page]: the page [BookmarksState.back] steps to, or null on the books. */
internal fun bookmarksBackTarget(page: BookmarksPage): BookmarksPage? = when (page) {
    BookmarksPage.Library -> null
    is BookmarksPage.Book -> BookmarksPage.Library
    is BookmarksPage.Review -> page.from
}

/** A deeper page (a book's marks, the review) slides in; back out slides the other way. */
internal fun bookmarksPageTransition(from: BookmarksPage, to: BookmarksPage): AppScreenTransition = when {
    to.depth > from.depth -> AppScreenTransition.Forward
    to.depth < from.depth -> AppScreenTransition.Backward
    else -> AppScreenTransition.Fade
}

private val BookmarksPage.depth: Int
    get() = when (this) {
        BookmarksPage.Library -> 0
        is BookmarksPage.Book -> 1
        is BookmarksPage.Review -> from.depth + 1
    }
