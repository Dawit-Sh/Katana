package com.example.unifiedreader

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.feature.annotations.BookmarksCopy
import com.example.unifiedreader.feature.annotations.AnnotationJumps
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.ReaderBookmark
import com.example.unifiedreader.feature.annotations.bookPageBookmarks
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A book's bookmarks for its page in the library, and opening the book at one. */
@HiltViewModel
class AppBookPageBookmarksViewModel @Inject constructor(
    private val store: ReaderAnnotationStore,
    private val jumps: AnnotationJumps,
    private val localCopies: GrimmoryLocalCopies,
) : ViewModel() {
    fun bookmarks(itemId: String): Flow<List<ReaderBookmark>> = store.bookmarks(itemId)

    /** For a book that is a file here: where its marks live, as last checked; null for a server's book. */
    fun marksHome(item: ReadableItem): Flow<String?> =
        if (item.sourceType != SourceType.LOCAL) {
            flowOf(null)
        } else {
            localCopies.linkStates.map { links -> localCopies.marksHome(item, links[item.id]).copy(item.format) }.distinctUntilChanged()
        }

    /** Checks a file here against Grimmory now, so its page says where its marks live. */
    fun checkLocalCopy(item: ReadableItem) {
        if (item.sourceType == SourceType.LOCAL) viewModelScope.launch { localCopies.copyOf(item) }
    }

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
    val marksHome by remember(viewModel, item) { viewModel.marksHome(item) }.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(viewModel, item) { viewModel.checkLocalCopy(item) }
    return remember<LazyListScope.(dismiss: () -> Unit) -> Unit>(item, bookmarks, marksHome, onOpen, viewModel) {
        { dismiss ->
            bookmarks?.let { loaded ->
                bookPageBookmarks(loaded, marksHome = marksHome) { bookmark ->
                    viewModel.openAt(bookmark)
                    onOpen(item)
                    dismiss()
                }
            }
        }
    }
}

// The page's line for where a local book's marks live; an EPUB's highlights sync too, a PDF's or comic's bookmarks only.
private fun LocalMarksHome.copy(format: FormatType): String = when (this) {
    LocalMarksHome.SyncsWithGrimmory ->
        if (format == FormatType.EPUB) BookmarksCopy.MarksSyncWithGrimmory else BookmarksCopy.BookmarksSyncWithGrimmory
    LocalMarksHome.NotOnGrimmory -> BookmarksCopy.MarksNotOnGrimmory
    LocalMarksHome.OnThisDevice -> BookmarksCopy.MarksOnThisDevice
}
