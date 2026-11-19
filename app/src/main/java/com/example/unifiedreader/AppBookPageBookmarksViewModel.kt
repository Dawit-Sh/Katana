package com.example.unifiedreader

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.annotations.AnnotationJumps
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.ReaderBookmark
import com.example.unifiedreader.feature.annotations.bookPageBookmarks
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** A book's bookmarks for its page in the library, and opening the book at one. */
@HiltViewModel
class AppBookPageBookmarksViewModel @Inject constructor(
    private val store: ReaderAnnotationStore,
    private val jumps: AnnotationJumps,
) : ViewModel() {
    fun bookmarks(itemId: String): Flow<List<ReaderBookmark>> = store.bookmarks(itemId)

    /** The reader opens [itemId] at this bookmark next, instead of where it was left. */
    fun openAt(bookmark: ReaderBookmark) = jumps.request(bookmark.book.itemId, bookmark.place.locatorJson)
}

/**
 * The book page's bookmarks section for [item]: "Bookmarks · 3" and each one in reading order; a tap
 * opens the book there ([onOpen] opens it, as Continue does). Read only while the page shows; the
 * section waits for the first read, so the page doesn't show "No bookmarks yet" before its list.
 */
@Composable
internal fun rememberBookPageBookmarks(
    item: ReadableItem,
    onOpen: (ReadableItem) -> Unit,
    viewModel: AppBookPageBookmarksViewModel = hiltViewModel(),
): LazyListScope.(dismiss: () -> Unit) -> Unit {
    val bookmarks by remember(viewModel, item.id) { viewModel.bookmarks(item.id) }
        .collectAsStateWithLifecycle<List<ReaderBookmark>?>(initialValue = null)
    return remember<LazyListScope.(dismiss: () -> Unit) -> Unit>(item, bookmarks, onOpen, viewModel) {
        { dismiss ->
            bookmarks?.let { loaded ->
                bookPageBookmarks(loaded) { bookmark ->
                    viewModel.openAt(bookmark)
                    onOpen(item)
                    dismiss()
                }
            }
        }
    }
}
