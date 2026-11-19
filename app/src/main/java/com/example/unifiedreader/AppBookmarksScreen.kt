package com.example.unifiedreader

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.AnnotationExports
import com.example.unifiedreader.feature.annotations.AnnotationJumps
import com.example.unifiedreader.feature.annotations.BookmarksActions
import com.example.unifiedreader.feature.annotations.BookmarksBar
import com.example.unifiedreader.feature.annotations.BookmarksCopy
import com.example.unifiedreader.feature.annotations.BookmarksScreen
import com.example.unifiedreader.feature.annotations.BookmarksState
import com.example.unifiedreader.feature.annotations.MarkdownExport
import com.example.unifiedreader.feature.annotations.MarkedBookCover
import com.example.unifiedreader.feature.annotations.MarkedBookPlaceholderCover
import com.example.unifiedreader.feature.annotations.MarkedLibrary
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.markedLibrary
import com.example.unifiedreader.feature.library.ItemProgressSummary
import com.example.unifiedreader.feature.library.LibraryCoverAspectRatio
import com.example.unifiedreader.feature.library.LibraryCoverImageVariant
import com.example.unifiedreader.feature.library.VisibleResolvedLibraryCover
import com.example.unifiedreader.feature.library.libraryWindowGridMetrics
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppToastState
import com.example.unifiedreader.ui.components.LocalAppToastState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Keeps the Bookmarks screen (its state and its marks) across screens and the books opened from it. */
@HiltViewModel
class AppBookmarksViewModel @Inject constructor(
    store: ReaderAnnotationStore,
    jumps: AnnotationJumps,
) : ViewModel() {
    val screen = AppBookmarksScreen(store = store, jumps = jumps, scope = viewModelScope)
}

/**
 * Every book's marks for the Bookmarks screen, and its changes through the store. The marks are
 * read only while the screen is on (and five seconds after).
 */
class AppBookmarksScreen(
    private val store: ReaderAnnotationStore,
    private val jumps: AnnotationJumps,
    private val scope: CoroutineScope,
) {
    /** Where the screen is; kept here so it is still there after a book opened from it closes. */
    val state = BookmarksState()

    val library: StateFlow<MarkedLibrary?> =
        store.markedLibrary().stateIn(scope, SharingStarted.WhileSubscribed(KEEP_MILLIS), null)

    private val exports = AnnotationExports(store)

    /** The reader opens [itemId] at this place next, instead of where it was left. */
    fun openAt(itemId: String, locatorJson: String) = jumps.request(itemId, locatorJson)

    fun deleteBookmark(id: String, toasts: AppToastState?) {
        scope.launch {
            store.deleteBookmark(id)
            toasts?.show(AppToastMessage(text = BookmarksCopy.BookmarkRemoved, icon = AppToastIcon.Removed))
        }
    }

    fun deleteWord(id: String, toasts: AppToastState?) {
        scope.launch {
            store.deleteWord(id)
            toasts?.show(AppToastMessage(text = BookmarksCopy.WordRemoved, icon = AppToastIcon.Removed))
        }
    }

    fun setWordNote(id: String, note: String) {
        scope.launch { store.setWordNote(id, note) }
    }

    fun reviewWord(id: String, known: Boolean) {
        scope.launch { store.reviewWord(id, known) }
    }

    suspend fun exportBook(book: AnnotatedBook): MarkdownExport = exports.book(book)

    suspend fun exportAll(): MarkdownExport = exports.library()

    private companion object {
        const val KEEP_MILLIS = 5_000L
    }
}

/** The Bookmarks screen: the marked books as covers, a book's marks, and the word review. */
@Composable
internal fun BookmarksRoute(
    bookmarks: AppBookmarksScreen,
    uiState: AppUiState,
    coverImageUrlFor: (ReadableItem, LibraryCoverImageVariant) -> String?,
    onCoverVisible: (ReadableItem, LibraryCoverImageVariant) -> Unit,
    onOpenBook: (ReadableItem) -> Unit,
    contentTopInset: Dp,
    modifier: Modifier = Modifier,
) {
    val library by bookmarks.library.collectAsStateWithLifecycle()
    val itemsById = rememberMarkedItems(library, uiState.items)
    val actions = rememberBookmarksActions(bookmarks, itemsById, uiState.progressByItemId, onOpenBook)
    val cover: MarkedBookCover = remember(itemsById, coverImageUrlFor, onCoverVisible) {
        { book, coverModifier ->
            val item = itemsById[book.itemId]
            if (item == null) {
                MarkedBookPlaceholderCover(book = book, modifier = coverModifier.aspectRatio(LibraryCoverAspectRatio))
            } else {
                VisibleResolvedLibraryCover(
                    item = item,
                    coverImageUrlProvider = coverImageUrlFor,
                    onCoverVisible = onCoverVisible,
                    imageVariant = LibraryCoverImageVariant.Grid,
                    modifier = coverModifier.aspectRatio(LibraryCoverAspectRatio),
                )
            }
        }
    }
    BookmarksScreen(
        state = bookmarks.state,
        library = library,
        actions = actions,
        cover = cover,
        gridCells = libraryWindowGridMetrics(uiState.libraryGridCellSizeDp).gridCells,
        contentTopInset = contentTopInset,
        modifier = modifier,
    )
}

/** The Bookmarks screen's bar: the menu button and Export all, or Back and the page's own actions. */
@Composable
internal fun BookmarksTopBar(
    bookmarks: AppBookmarksScreen,
    uiState: AppUiState,
    onOpenBook: (ReadableItem) -> Unit,
    onDrawerClick: (() -> Unit)?,
) {
    val library by bookmarks.library.collectAsStateWithLifecycle()
    val itemsById = rememberMarkedItems(library, uiState.items)
    val actions = rememberBookmarksActions(bookmarks, itemsById, uiState.progressByItemId, onOpenBook)
    ThemedTopBarContainer(surfaceVisible = bookmarks.state.scrolled) {
        BookmarksBar(
            state = bookmarks.state,
            library = library,
            actions = actions,
            menuButton = onDrawerClick?.let { onClick -> { DrawerMenuButton(onClick = onClick) } },
        )
    }
}

/** The library's books that have marks, by id; only those, not a map of the whole catalog. */
@Composable
private fun rememberMarkedItems(library: MarkedLibrary?, items: List<ReadableItem>): Map<String, ReadableItem> =
    remember(library, items) {
        val wanted = library?.books?.mapTo(hashSetOf()) { it.book.itemId }.orEmpty()
        if (wanted.isEmpty()) emptyMap() else items.filter { item -> item.id in wanted }.associateBy(ReadableItem::id)
    }

@Composable
private fun rememberBookmarksActions(
    bookmarks: AppBookmarksScreen,
    itemsById: Map<String, ReadableItem>,
    progress: Map<String, ItemProgressSummary>,
    onOpenBook: (ReadableItem) -> Unit,
): BookmarksActions {
    val toasts = LocalAppToastState.current
    return remember(bookmarks, itemsById, progress, onOpenBook, toasts) {
        AppBookmarksActions(bookmarks, itemsById, progress, onOpenBook, toasts)
    }
}

/** The screen's actions: books open through the library's own opening, marks change in the store. */
private class AppBookmarksActions(
    private val bookmarks: AppBookmarksScreen,
    private val itemsById: Map<String, ReadableItem>,
    private val progress: Map<String, ItemProgressSummary>,
    private val onOpenBook: (ReadableItem) -> Unit,
    private val toasts: AppToastState?,
) : BookmarksActions {
    override fun continueReading(book: AnnotatedBook) = open(book) {}

    override fun openAt(book: AnnotatedBook, locatorJson: String) = open(book) { bookmarks.openAt(book.itemId, locatorJson) }

    override fun progressLabel(itemId: String): String? = progress[itemId]?.let { summary ->
        val page = summary.currentPage?.takeIf { it > 0 }
        val pages = summary.pageCount?.takeIf { it > 0 }
        when {
            page != null && pages != null -> "p. $page of $pages"
            summary.fraction > 0f -> "${(summary.fraction * PERCENT).toInt()}%"
            else -> null
        }
    }

    override fun deleteBookmark(id: String) = bookmarks.deleteBookmark(id, toasts)

    override fun deleteWord(id: String) = bookmarks.deleteWord(id, toasts)

    override fun setWordNote(id: String, note: String) = bookmarks.setWordNote(id, note)

    override fun reviewWord(id: String, known: Boolean) = bookmarks.reviewWord(id, known)

    override suspend fun exportBook(book: AnnotatedBook): MarkdownExport = bookmarks.exportBook(book)

    override suspend fun exportAll(): MarkdownExport = bookmarks.exportAll()

    private fun open(book: AnnotatedBook, before: () -> Unit) {
        val item = itemsById[book.itemId]
        if (item == null) {
            toasts?.show(AppToastMessage(text = NOT_IN_LIBRARY, icon = AppToastIcon.Error))
        } else {
            before()
            onOpenBook(item)
        }
    }

    private companion object {
        const val PERCENT = 100
        const val NOT_IN_LIBRARY = "This book isn't in your library now"
    }
}
