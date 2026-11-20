package com.example.unifiedreader.feature.annotations

import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn

/** A Markdown export ready to be saved where the reader picks. */
data class MarkdownExport(val fileName: String, val text: String)

/** The Markdown exports: one book's marks, or every book's as one notebook. */
class AnnotationExports(
    private val store: ReaderAnnotationStore,
    private val today: () -> LocalDate = LocalDate::now,
) {
    /** One book's bookmarks, highlights and words as Markdown, with a file name for it. */
    suspend fun book(book: AnnotatedBook): MarkdownExport {
        val day = today()
        val text = AnnotationMarkdown.book(
            book = book,
            highlights = store.highlights(book.itemId).first(),
            words = store.words(book.itemId).first(),
            exportedOn = day,
            bookmarks = store.bookmarks(book.itemId).first(),
        )
        return MarkdownExport(fileName = AnnotationMarkdown.fileName(book, day), text = text)
    }

    /** [words] (My words' list) as Markdown under their books. */
    fun words(words: List<SavedWord>): MarkdownExport {
        val day = today()
        return MarkdownExport(fileName = MyWordsMarkdown.fileName(day), text = MyWordsMarkdown.words(words, day))
    }

    /** Every book's marks as one Markdown notebook. */
    suspend fun library(): MarkdownExport {
        val day = today()
        val text = AnnotationMarkdown.library(
            highlights = store.allHighlights.first(),
            words = store.allWords.first(),
            exportedOn = day,
            bookmarks = store.allBookmarks.first(),
        )
        return MarkdownExport(fileName = AnnotationMarkdown.fileName(null, day), text = text)
    }
}

/** Every book with a mark, kept up to date; grouped off the main thread. */
fun ReaderAnnotationStore.markedLibrary(): Flow<MarkedLibrary> =
    combine(allHighlights, allBookmarks, allWords, MarkedLibrary::of).flowOn(Dispatchers.Default)
