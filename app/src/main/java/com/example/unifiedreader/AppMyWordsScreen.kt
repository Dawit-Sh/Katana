package com.example.unifiedreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.annotations.AnnotationExports
import com.example.unifiedreader.feature.annotations.AnnotationJumps
import com.example.unifiedreader.feature.annotations.MarkdownExport
import com.example.unifiedreader.feature.annotations.MyWordsActions
import com.example.unifiedreader.feature.annotations.MyWordsBar
import com.example.unifiedreader.feature.annotations.MyWordsCopy
import com.example.unifiedreader.feature.annotations.MyWordsScreen
import com.example.unifiedreader.feature.annotations.MyWordsState
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.SavedWord
import com.example.unifiedreader.feature.annotations.WordMeanings
import com.example.unifiedreader.feature.annotations.meaningsOf
import com.example.unifiedreader.feature.annotations.removeWordWithUndo
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppToastState
import com.example.unifiedreader.ui.components.LocalAppToastState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Keeps the My words page (its state and the words) across screens and the books opened from it. */
@HiltViewModel
class AppMyWordsViewModel @Inject constructor(
    store: ReaderAnnotationStore,
    jumps: AnnotationJumps,
    meanings: WordMeanings,
) : ViewModel() {
    val screen = AppMyWordsScreen(store = store, jumps = jumps, meanings = meanings, scope = viewModelScope)
}

/**
 * Every saved word for the My words page, their meanings when a source has any, and their changes
 * through the store. The words are read while the page or the menu's count is on (and five seconds after).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppMyWordsScreen(
    private val store: ReaderAnnotationStore,
    private val jumps: AnnotationJumps,
    meanings: WordMeanings,
    private val scope: CoroutineScope,
) {
    /** Where the page is; kept here so it is still there after a book opened from it closes. */
    val state = MyWordsState()

    val words: StateFlow<List<SavedWord>?> =
        store.allWords.stateIn(scope, SharingStarted.WhileSubscribed(KEEP_MILLIS), null)

    /** How many words are saved, for the menu. */
    val count: StateFlow<Int> =
        store.allWords.map { it.size }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(KEEP_MILLIS), 0)

    /**
     * A one-line meaning by word id, from [WordMeanings] (none until a source is plugged in). A saved
     * word doesn't keep its book's language, so it asks with none.
     */
    val meanings: StateFlow<Map<String, String>> =
        words.filterNotNull()
            .mapLatest { list -> meanings.meaningsOf(list, language = null) }
            .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_MILLIS), emptyMap())

    private val exports = AnnotationExports(store)

    /** The reader opens [itemId] at this place next, instead of where it was left. */
    fun openAt(itemId: String, locatorJson: String) = jumps.request(itemId, locatorJson)

    /** Removes a word; the toast's Undo brings it back as it was. */
    fun remove(word: SavedWord, toasts: AppToastState?) {
        scope.launch { store.removeWordWithUndo(word, toasts, scope) }
    }

    fun setWordNote(id: String, note: String) {
        scope.launch { store.setWordNote(id, note) }
    }

    fun reviewWord(id: String, known: Boolean) {
        scope.launch { store.reviewWord(id, known) }
    }

    fun export(words: List<SavedWord>): MarkdownExport = exports.words(words)

    private companion object {
        const val KEEP_MILLIS = 5_000L
    }
}

/** The My words page. */
@Composable
internal fun MyWordsRoute(
    myWords: AppMyWordsScreen,
    uiState: AppUiState,
    onOpenBook: (ReadableItem) -> Unit,
    contentTopInset: Dp,
    modifier: Modifier = Modifier,
) {
    val words by myWords.words.collectAsStateWithLifecycle()
    val meanings by myWords.meanings.collectAsStateWithLifecycle()
    MyWordsScreen(
        state = myWords.state,
        words = words,
        meanings = meanings,
        actions = rememberMyWordsActions(myWords, words, uiState.items, onOpenBook),
        contentTopInset = contentTopInset,
        modifier = modifier,
    )
}

/** The My words page's bar: the menu button and Export, or Back in the review. */
@Composable
internal fun MyWordsTopBar(
    myWords: AppMyWordsScreen,
    uiState: AppUiState,
    onOpenBook: (ReadableItem) -> Unit,
    onDrawerClick: (() -> Unit)?,
) {
    val words by myWords.words.collectAsStateWithLifecycle()
    ThemedTopBarContainer(surfaceVisible = myWords.state.scrolled) {
        MyWordsBar(
            state = myWords.state,
            words = words,
            actions = rememberMyWordsActions(myWords, words, uiState.items, onOpenBook),
            menuButton = onDrawerClick?.let { onClick -> { DrawerMenuButton(onClick = onClick) } },
        )
    }
}

@Composable
private fun rememberMyWordsActions(
    myWords: AppMyWordsScreen,
    words: List<SavedWord>?,
    items: List<ReadableItem>,
    onOpenBook: (ReadableItem) -> Unit,
): MyWordsActions {
    val toasts = LocalAppToastState.current
    // Only the books that have saved words, not a map of the whole catalog.
    val itemsById = remember(words, items) {
        val wanted = words?.mapTo(hashSetOf()) { it.book.itemId }.orEmpty()
        if (wanted.isEmpty()) emptyMap() else items.filter { item -> item.id in wanted }.associateBy(ReadableItem::id)
    }
    return remember(myWords, itemsById, onOpenBook, toasts) { AppMyWordsActions(myWords, itemsById, onOpenBook, toasts) }
}

/** The page's actions: books open through the library's own opening, words change in the store. */
private class AppMyWordsActions(
    private val myWords: AppMyWordsScreen,
    private val itemsById: Map<String, ReadableItem>,
    private val onOpenBook: (ReadableItem) -> Unit,
    private val toasts: AppToastState?,
) : MyWordsActions {
    override fun open(word: SavedWord) {
        val item = itemsById[word.book.itemId]
        if (item == null) {
            toasts?.show(AppToastMessage(text = MyWordsCopy.NotInLibrary, icon = AppToastIcon.Error))
            return
        }
        word.place?.locatorJson?.takeIf(String::isNotEmpty)?.let { locator -> myWords.openAt(item.id, locator) }
        onOpenBook(item)
    }

    override fun remove(word: SavedWord) = myWords.remove(word, toasts)

    override fun setWordNote(id: String, note: String) = myWords.setWordNote(id, note)

    override fun reviewWord(id: String, known: Boolean) = myWords.reviewWord(id, known)

    override suspend fun export(words: List<SavedWord>): MarkdownExport = myWords.export(words)
}
