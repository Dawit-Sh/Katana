package com.example.unifiedreader.feature.reader.epub

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.AnnotationExports
import com.example.unifiedreader.feature.annotations.MarkdownExport
import com.example.unifiedreader.feature.annotations.ReaderBookmark
import com.example.unifiedreader.feature.annotations.ReaderBookmarkStore
import com.example.unifiedreader.feature.annotations.removeBookmarksWithUndo
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppToastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

/** The highlight being shared as an image, from its card; null while the share card is closed. */
@Stable
internal class EpubQuoteSharing {
    var highlightId by mutableStateOf<String?>(null)
        private set

    fun open(highlightId: String) {
        this.highlightId = highlightId
    }

    fun close() {
        highlightId = null
    }
}

/** The page bookmarks of the open book: the top bar's toggle and the panel's remove. Saved at once. */
@Stable
internal class EpubBookmarkActions(
    private val store: ReaderBookmarkStore,
    private val book: AnnotatedBook,
    private val scope: CoroutineScope,
    private val toasts: () -> AppToastState?,
    private val chapterTitleOf: (Locator) -> String?,
) {
    private var busy = false

    /**
     * Removes the page's bookmarks ([onPage]) when it has any, otherwise bookmarks the page at
     * [locator], page [page] of [pageCount], named by its first words.
     */
    fun toggle(
        onPage: List<ReaderBookmark>,
        locator: Locator?,
        page: Int,
        pageCount: Int,
        firstWords: suspend () -> String?,
    ) {
        if (busy || (onPage.isEmpty() && locator == null)) return
        busy = true
        scope.launch {
            try {
                if (onPage.isNotEmpty()) {
                    store.removeBookmarksWithUndo(onPage.map(ReaderBookmark::id), toasts(), scope, EpubAnnotationCopy.BookmarkRemoved)
                } else if (locator != null) {
                    store.addBookmark(
                        book = book,
                        place = locator.toAnnotationPlace(chapterTitleOf),
                        page = page,
                        pageCount = pageCount,
                        text = firstWords(),
                    )
                    toasts()?.show(AppToastMessage(text = EpubAnnotationCopy.Bookmarked, icon = AppToastIcon.Added))
                }
            } finally {
                busy = false
            }
        }
    }

    /** Removes a bookmark; the toast's Undo brings it back as it was. */
    fun delete(bookmarkId: String) {
        scope.launch {
            store.removeBookmarksWithUndo(listOf(bookmarkId), toasts(), scope, EpubAnnotationCopy.BookmarkRemoved)
        }
    }

    /** The reader's name and note for a bookmark; blank removes it. */
    fun name(bookmarkId: String, title: String, note: String) {
        scope.launch { store.nameBookmark(bookmarkId, title, note) }
    }
}

/** Export highlights: this book's marks as Markdown, and the toast once the file is written or not. */
@Stable
internal class EpubMarkExport(
    private val exports: AnnotationExports,
    private val book: AnnotatedBook,
    private val toasts: () -> AppToastState?,
) {
    suspend fun book(): MarkdownExport = exports.book(book)

    fun saved(succeeded: Boolean) {
        val message = if (succeeded) {
            AppToastMessage(text = EpubAnnotationCopy.Exported, icon = AppToastIcon.Done)
        } else {
            AppToastMessage(text = EpubAnnotationCopy.ExportFailed, icon = AppToastIcon.Error)
        }
        toasts()?.show(message)
    }
}
