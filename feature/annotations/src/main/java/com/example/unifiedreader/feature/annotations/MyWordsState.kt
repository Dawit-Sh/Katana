package com.example.unifiedreader.feature.annotations

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.unifiedreader.ui.components.AppScreenTransition

/** What the My words page asks of the app and the store. */
interface MyWordsActions : WordStudyActions {
    /** Opens the word's book at the sentence it came from (where it was left, for a word saved without a place). */
    fun open(word: SavedWord)

    /** Removes the word; the toast's Undo brings it back as it was. */
    fun remove(word: SavedWord)

    /** Shows the word on the Dictionary card. */
    fun lookUp(word: SavedWord)

    suspend fun export(words: List<SavedWord>): MarkdownExport
}

/** How the My words page shows the words: as a list, or as the graph of books and words. */
enum class MyWordsView { List, Graph }

/** Where the My words page is: the list, or the flashcards. */
sealed interface MyWordsPage {
    data object Words : MyWordsPage

    data object Review : MyWordsPage
}

/**
 * The My words page's state: its page, the search, the book filter, the word whose note is being
 * changed and a review under way. Kept outside the screen, so it is still there after a book opened
 * from it closes.
 */
@Stable
class MyWordsState {
    var page: MyWordsPage by mutableStateOf(MyWordsPage.Words)
        private set
    var query: String by mutableStateOf("")

    /** The book whose words are shown; null for every book. */
    var bookId: String? by mutableStateOf(null)

    /** The word whose note is being changed, if any. */
    var editing: SavedWord? by mutableStateOf(null)

    /** The flashcard round under way, if any. */
    var round: WordReviewRound? by mutableStateOf(null)

    /** The list or the graph. */
    var view: MyWordsView by mutableStateOf(MyWordsView.List)

    /** The graph's state filter: words that don't match fade. */
    var stateFilter: WordStateFilter by mutableStateOf(WordStateFilter.All)

    /** The word picked on the graph, its card open; null for none. */
    var graphSelected: String? by mutableStateOf(null)

    /** Whether anything has scrolled under the bar (it then needs its glass). */
    var scrolled: Boolean by mutableStateOf(false)

    fun openReview() {
        round = null
        scrolled = false
        page = MyWordsPage.Review
    }

    /** The review on [word] alone, as the side menu's word of the day opens it. */
    fun openReview(word: SavedWord) {
        round = WordReviewRound.of(listOf(word))
        scrolled = false
        page = MyWordsPage.Review
    }

    /** One page back; false on the list, which has nothing behind it. */
    fun back(): Boolean {
        if (page == MyWordsPage.Words) return false
        page = MyWordsPage.Words
        round = null
        scrolled = false
        return true
    }

    /** Straight back to the list, as picking My words in the menu does. */
    fun home() {
        back()
    }
}

/** The review slides in over the list and back out. */
internal fun myWordsPageTransition(from: MyWordsPage, to: MyWordsPage): AppScreenTransition = when {
    from == to -> AppScreenTransition.Fade
    to == MyWordsPage.Review -> AppScreenTransition.Forward
    else -> AppScreenTransition.Backward
}

/** What the My words page says on screen. */
object MyWordsCopy {
    const val Title = "My words"
    const val SearchPlaceholder = "Search words, sentences, books and notes"
    const val AllBooks = "All books"
    const val Review = "Review"
    const val Export = "Export"
    const val Back = "Back to My words"
    const val LookUp = "Look it up"
    const val EditNote = "Edit note"
    const val Remove = "Remove"
    const val WordActions = "Word actions"
    const val EmptyTitle = "No saved words yet"
    const val EmptyBody =
        "While you read, select a word, tap Dictionary, then Add to My words. It shows up here with the sentence you met it in."
    const val NothingFoundTitle = "No words match"
    const val NothingFoundBody = "Try other words, or All books."
    const val NotInLibrary = "This book isn't in your library now"

    /** "Review · 6 due", or plain "Review" when nothing is due (the review then offers every word). */
    fun review(due: Int): String = if (due > 0) "$Review · $due due" else Review

    /** "41 words from 3 books", "2 words from Hamlet". */
    fun summary(list: MyWordsList, book: MyWordsBook?): String = if (book != null) {
        "${MarkCounts.words(book.count)} from ${book.book.title}"
    } else {
        "${MarkCounts.words(list.words.size)} from ${MarkCounts.books(list.books.size)}"
    }

    /** "from every book", or "from Hamlet" while the list shows one book. */
    fun reviewFrom(book: MyWordsBook?): String = if (book == null) "from every book" else "from ${book.book.title}"
}
