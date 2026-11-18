package com.example.unifiedreader.feature.reader.epub

import androidx.compose.ui.geometry.Rect
import com.example.unifiedreader.feature.annotations.ReaderHighlight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.Selection
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * What the reader does with highlights and saved words: the book's highlights to draw, and what a
 * selection or a tap on a highlight starts. The reader screen makes it; the navigator host uses it.
 */
internal class EpubSelectionAnnotations(
    val highlights: List<ReaderHighlight>,
    /** [withNote]: the selection's Note, which opens the note editor on the new highlight. */
    val onHighlight: (selection: Selection, anchor: Rect?, withNote: Boolean) -> Unit,
    val onSaveWord: (selection: Selection) -> Unit,
    /** A highlight was tapped; [anchor] is where it is, in window pixels. */
    val onHighlightActivated: (highlightId: String, anchor: Rect?) -> Unit,
)

/**
 * Draws the book's highlights on whichever navigator is showing it. The host rebuilds the
 * navigator when the layout changes, so [attach] gives each new one the highlights again; Readium
 * keeps them and puts them back on every page it loads.
 */
internal class EpubHighlightLayer(private val scope: CoroutineScope) {
    private var navigator: EpubNavigatorFragment? = null
    private var highlights: List<ReaderHighlight> = emptyList()
    private var applyJob: Job? = null
    private var onActivated: ((String, Rect?) -> Unit)? = null

    private val listener = object : DecorableNavigator.Listener {
        override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
            val activated = onActivated
            val fragment = navigator
            if (activated == null || fragment == null) return false
            val origin = IntArray(2).also { fragment.view?.getLocationInWindow(it) }
            val anchor = event.rect?.let { rect ->
                Rect(rect.left + origin[0], rect.top + origin[1], rect.right + origin[0], rect.bottom + origin[1])
            }
            activated(event.decoration.id, anchor)
            return true
        }
    }

    fun attach(fragment: EpubNavigatorFragment, onHighlightActivated: (String, Rect?) -> Unit) {
        navigator = fragment
        onActivated = onHighlightActivated
        fragment.addDecorationListener(EpubHighlightGroup, listener)
        apply()
    }

    fun detach(fragment: EpubNavigatorFragment) {
        fragment.removeDecorationListener(listener)
        if (navigator === fragment) {
            navigator = null
            applyJob?.cancel()
        }
    }

    fun show(next: List<ReaderHighlight>) {
        if (next == highlights) return
        highlights = next
        apply()
    }

    private fun apply() {
        val fragment = navigator ?: return
        val decorations = highlights.mapNotNull(ReaderHighlight::toEpubDecoration)
        applyJob?.cancel()
        applyJob = scope.launch { fragment.applyDecorations(decorations, EpubHighlightGroup) }
    }
}

/**
 * Reads the selection with its Readium locator (the words, the text around them and their
 * chapter), which a highlight keeps to find them again, and its place in window pixels; then
 * clears it, as the toolbar's action is done with it.
 */
@OptIn(ExperimentalReadiumApi::class)
internal fun CoroutineScope.withSelectionForAnnotation(
    navigator: EpubNavigatorFragment?,
    block: (Selection, Rect?) -> Unit,
): Job? {
    val fragment = navigator ?: return null
    return launch {
        val selection = fragment.currentSelection() ?: return@launch
        if (selection.locator.text.highlight.isNullOrBlank()) return@launch
        val origin = IntArray(2).also { fragment.view?.getLocationInWindow(it) }
        val anchor = selection.rect?.let { rect ->
            Rect(rect.left + origin[0], rect.top + origin[1], rect.right + origin[0], rect.bottom + origin[1])
        }
        fragment.clearSelection()
        block(selection, anchor)
    }
}

/** The toolbar's Highlight, Note and Add to dictionary, reading the selection when tapped. */
internal fun epubSelectionAnnotationActions(
    scope: CoroutineScope,
    navigator: () -> EpubNavigatorFragment?,
    annotations: () -> EpubSelectionAnnotations?,
): EpubSelectionAnnotationActions = EpubSelectionAnnotationActions(
    isAvailable = { annotations() != null },
    onHighlight = {
        scope.withSelectionForAnnotation(navigator()) { selection, anchor ->
            annotations()?.onHighlight?.invoke(selection, anchor, false)
        }
    },
    onNote = {
        scope.withSelectionForAnnotation(navigator()) { selection, anchor ->
            annotations()?.onHighlight?.invoke(selection, anchor, true)
        }
    },
    onSaveWord = {
        scope.withSelectionForAnnotation(navigator()) { selection, _ ->
            annotations()?.onSaveWord?.invoke(selection)
        }
    },
)
