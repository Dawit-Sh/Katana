package com.example.unifiedreader

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.annotations.AnnotationExports
import com.example.unifiedreader.feature.annotations.AnnotationJumps
import com.example.unifiedreader.feature.annotations.KeptWord
import com.example.unifiedreader.feature.annotations.MarkdownExport
import com.example.unifiedreader.feature.annotations.MyWordsActions
import com.example.unifiedreader.feature.annotations.MyWordsBar
import com.example.unifiedreader.feature.annotations.MyWordsCopy
import com.example.unifiedreader.feature.annotations.MyWordsScreen
import com.example.unifiedreader.feature.annotations.MyWordsState
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.SavedWord
import com.example.unifiedreader.feature.annotations.WordMeanings
import com.example.unifiedreader.feature.annotations.WordOfTheDay
import com.example.unifiedreader.feature.annotations.meaningLine
import com.example.unifiedreader.feature.annotations.meaningsOf
import com.example.unifiedreader.feature.annotations.removeWordWithUndo
import com.example.unifiedreader.feature.dictionary.DictionaryRequest
import com.example.unifiedreader.feature.dictionary.ReaderDictionaryOverlay
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppToastState
import com.example.unifiedreader.ui.components.LocalAppToastState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    @ApplicationContext context: Context,
) : ViewModel() {
    val screen = AppMyWordsScreen(
        store = store,
        jumps = jumps,
        wordMeanings = meanings,
        scope = viewModelScope,
        wordOfTheDayStore = WordOfTheDayStore(context),
    )
}

/**
 * Every saved word for the My words page, their meanings when a source has any, and their changes
 * through the store. The words are read while the page or the menu's count is on (and five seconds after).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppMyWordsScreen(
    private val store: ReaderAnnotationStore,
    private val jumps: AnnotationJumps,
    private val wordMeanings: WordMeanings,
    private val scope: CoroutineScope,
    /** Keeps the side menu's word of the day for its day; null keeps it in memory only. */
    private val wordOfTheDayStore: WordOfTheDayStore? = null,
) {
    /** Where the page is; kept here so it is still there after a book opened from it closes. */
    val state = MyWordsState()

    val words: StateFlow<List<SavedWord>?> =
        store.allWords.stateIn(scope, SharingStarted.WhileSubscribed(KEEP_MILLIS), null)

    /** How many words are saved, for the menu. */
    val count: StateFlow<Int> =
        store.allWords.map { it.size }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(KEEP_MILLIS), 0)

    /**
     * A one-line meaning by word id from [WordMeanings] (the dictionary on this device), for the words
     * that keep none of their own. A saved word doesn't keep its book's language; the dictionary is
     * English, so it asks in English.
     */
    val meanings: StateFlow<Map<String, String>> =
        words.filterNotNull()
            .mapLatest { list -> wordMeanings.meaningsOf(list, language = MEANING_LANGUAGE) }
            .stateIn(scope, SharingStarted.WhileSubscribed(KEEP_MILLIS), emptyMap())

    private val exports = AnnotationExports(store)

    private val keptWordState = MutableStateFlow<KeptWord?>(null)
    private val keptWordReadState = MutableStateFlow(wordOfTheDayStore == null)

    /** The word kept as a day's word of the day. */
    val keptWord: StateFlow<KeptWord?> = keptWordState.asStateFlow()

    /** Whether [keptWord] has been read from its file; until then nothing is kept over it. */
    val keptWordRead: StateFlow<Boolean> = keptWordReadState.asStateFlow()

    init {
        wordOfTheDayStore?.let { kept ->
            scope.launch(Dispatchers.IO) {
                keptWordState.value = kept.read()
                keptWordReadState.value = true
            }
        }
    }

    /** Keeps [kept] as its day's word of the day, so it stays all day even as words come and go. */
    fun keepWordOfTheDay(kept: KeptWord) {
        if (!keptWordReadState.value || keptWordState.value == kept) return
        keptWordState.value = kept
        wordOfTheDayStore?.let { store -> scope.launch(Dispatchers.IO) { store.write(kept) } }
    }

    /** "Another word": the next in [date]'s order after [current], kept for the rest of the day. */
    fun nextWordOfTheDay(date: LocalDate, current: SavedWord) {
        val next = words.value?.let { list -> WordOfTheDay.next(list, date, current) } ?: return
        keepWordOfTheDay(KeptWord(date, next.id))
    }

    /** [word]'s meaning on one line: its own, else the dictionary's. */
    suspend fun meaningOf(word: SavedWord): String? =
        meaningLine(word, wordMeanings.meaningsOf(listOf(word), language = MEANING_LANGUAGE))

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

    /** The word on the Dictionary card, if any. */
    var lookingUp: DictionaryRequest? by mutableStateOf(null)

    /** Shows [word] on the Dictionary card; its Add to My words keeps the definition as the word's meaning. */
    fun lookUp(word: SavedWord) {
        lookingUp = DictionaryRequest(word = word.word) { meaning ->
            val kept = meaning?.trim()?.takeIf(String::isNotEmpty)
            // Already saved: the definition becomes its meaning, unless it keeps one of its own.
            if (kept != null && word.note.isNullOrBlank()) setWordNote(word.id, kept)
        }
    }

    private companion object {
        const val KEEP_MILLIS = 5_000L
        const val MEANING_LANGUAGE = "en"
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
    Box(modifier = modifier) {
        MyWordsScreen(
            state = myWords.state,
            words = words,
            meanings = meanings,
            actions = rememberMyWordsActions(myWords, words, uiState.items, onOpenBook),
            contentTopInset = contentTopInset,
            modifier = Modifier.fillMaxSize(),
        )
        // Under the app's bar, painted solid: the page itself is what the bar's glass shows.
        ReaderDictionaryOverlay(
            request = myWords.lookingUp,
            onDismiss = { myWords.lookingUp = null },
            modifier = Modifier.padding(top = contentTopInset),
            glass = false,
        )
    }
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

    override fun lookUp(word: SavedWord) = myWords.lookUp(word)

    override fun setWordNote(id: String, note: String) = myWords.setWordNote(id, note)

    override fun reviewWord(id: String, known: Boolean) = myWords.reviewWord(id, known)

    override suspend fun export(words: List<SavedWord>): MarkdownExport = myWords.export(words)
}
