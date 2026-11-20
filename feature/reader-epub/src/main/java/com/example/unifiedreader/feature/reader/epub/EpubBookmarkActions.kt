package com.example.unifiedreader.feature.reader.epub

import androidx.compose.runtime.Stable
import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.ReaderBookmark
import com.example.unifiedreader.feature.annotations.ReaderBookmarkStore
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppToastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

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
                    onPage.forEach { bookmark -> store.deleteBookmark(bookmark.id) }
                    toasts()?.show(AppToastMessage(text = EpubAnnotationCopy.BookmarkRemoved, icon = AppToastIcon.Removed))
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

    fun delete(bookmarkId: String) {
        scope.launch {
            store.deleteBookmark(bookmarkId)
            toasts()?.show(AppToastMessage(text = EpubAnnotationCopy.BookmarkRemoved, icon = AppToastIcon.Removed))
        }
    }

    /** The reader's name and note for a bookmark; blank removes it. */
    fun name(bookmarkId: String, title: String, note: String) {
        scope.launch { store.nameBookmark(bookmarkId, title, note) }
    }
}
