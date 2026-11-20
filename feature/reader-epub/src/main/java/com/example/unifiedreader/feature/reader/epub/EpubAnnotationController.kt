package com.example.unifiedreader.feature.reader.epub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.AnnotationExports
import com.example.unifiedreader.feature.annotations.AnnotationPlace
import com.example.unifiedreader.feature.annotations.HighlightColor
import com.example.unifiedreader.feature.annotations.HighlightStyle
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.ReaderHighlight
import com.example.unifiedreader.feature.annotations.removeHighlightWithUndo
import com.example.unifiedreader.feature.translation.ReaderTextSelection
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppToastState
import com.example.unifiedreader.ui.components.LocalAppToastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.Selection
import org.readium.r2.shared.publication.Locator

/** The highlight card open over the page: which highlight, and where it is on screen (window pixels). */
internal data class EpubHighlightCardState(val highlightId: String, val anchor: Rect?)

/**
 * The reader's side of highlights, bookmarks and saved words: makes them from a selection or the
 * page on screen, opens the card on a highlight (and its share card), and changes or removes them.
 * Everything is saved at once; nothing waits for the book to close.
 */
@Stable
internal class EpubAnnotationController(
    private val store: ReaderAnnotationStore,
    private val book: AnnotatedBook,
    private val scope: CoroutineScope,
    private val toasts: () -> AppToastState?,
    private val chapterTitleOf: (Locator) -> String?,
) {
    var card by mutableStateOf<EpubHighlightCardState?>(null)
        private set

    /** The highlight whose note is being written, if any. */
    var editingNoteOf by mutableStateOf<String?>(null)
        private set

    /** The highlight being shared as an image, if any. */
    val sharing = EpubQuoteSharing()

    /** The page bookmarks: the top bar's button and the panel's rows. */
    val bookmarks = EpubBookmarkActions(store = store, book = book, scope = scope, toasts = toasts, chapterTitleOf = chapterTitleOf)

    /** This book's marks as Markdown; every book's is on the Bookmarks page. */
    val export = EpubMarkExport(exports = AnnotationExports(store), book = book, toasts = toasts)

    fun highlight(selection: Selection, anchor: Rect?, withNote: Boolean) {
        val text = selection.locator.text.highlight?.trim().orEmpty()
        if (text.isEmpty()) return
        scope.launch {
            val made = store.addHighlight(
                book = book,
                place = selection.locator.toAnnotationPlace(chapterTitleOf),
                text = text,
                look = store.lastLook(),
            )
            if (withNote) editingNoteOf = made.id else card = EpubHighlightCardState(made.id, anchor)
        }
    }

    fun saveWord(selection: Selection) {
        val read = ReaderTextSelection(
            text = selection.locator.text.highlight.orEmpty(),
            before = selection.locator.text.before.orEmpty(),
            after = selection.locator.text.after.orEmpty(),
        )
        val word = read.text.trim().trim { it in WordEdgePunctuation }
        if (word.isEmpty()) return
        scope.launch {
            store.saveWord(book = book, word = word, sentence = read.sentence, place = selection.locator.toAnnotationPlace(chapterTitleOf))
            toasts()?.show(AppToastMessage(text = EpubAnnotationCopy.wordSaved(word), icon = AppToastIcon.Added))
        }
    }

    fun open(highlightId: String, anchor: Rect?) {
        card = EpubHighlightCardState(highlightId, anchor)
    }

    fun closeCard() {
        card = null
    }

    /** A new style or colour for a highlight; null keeps that part. */
    fun restyle(highlightId: String, style: HighlightStyle? = null, color: HighlightColor? = null) {
        scope.launch { store.updateHighlight(highlightId, style = style, color = color) }
    }

    fun editNote(highlightId: String) {
        card = null
        editingNoteOf = highlightId
    }

    fun saveNote(highlightId: String, note: String) {
        editingNoteOf = null
        scope.launch { store.updateHighlight(highlightId, note = note) }
    }

    fun cancelNote() {
        editingNoteOf = null
    }

    /** Removes a highlight; the toast's Undo brings it back as it was. */
    fun delete(highlight: ReaderHighlight) {
        card = null
        scope.launch { store.removeHighlightWithUndo(highlight.id, toasts(), scope, EpubAnnotationCopy.Removed) }
    }

    fun deleteWord(wordId: String) {
        scope.launch {
            store.deleteWord(wordId)
            toasts()?.show(AppToastMessage(text = EpubAnnotationCopy.WordRemoved, icon = AppToastIcon.Removed))
        }
    }

    private companion object {
        /** Kept off a saved word's ends: a selection often takes the comma or the quote mark after it. */
        val WordEdgePunctuation = charArrayOf(
            '.', ',', ';', ':', '!', '?', '"', '\'', '(', ')', '[', ']',
            '“', '”', '‘', '’', '…', '—', '–',
        )
    }
}

internal fun Locator.toAnnotationPlace(chapterTitleOf: (Locator) -> String?): AnnotationPlace = AnnotationPlace(
    href = href.toString(),
    locatorJson = toJSON().toString(),
    chapterTitle = chapterTitleOf(this),
    progression = locations.totalProgression,
)

@Composable
internal fun rememberEpubAnnotationController(
    store: ReaderAnnotationStore,
    book: AnnotatedBook,
    chapterTitleOf: (Locator) -> String?,
): EpubAnnotationController {
    val scope: CoroutineScope = rememberCoroutineScope()
    val toastState = LocalAppToastState.current
    val latestToasts by rememberUpdatedState(toastState)
    val latestChapterTitleOf by rememberUpdatedState(chapterTitleOf)
    return remember(store, book) {
        EpubAnnotationController(
            store = store,
            book = book,
            scope = scope,
            toasts = { latestToasts },
            chapterTitleOf = { locator -> latestChapterTitleOf(locator) },
        )
    }
}
