package com.example.unifiedreader.feature.annotations

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.example.unifiedreader.ui.components.AppToastState
import com.example.unifiedreader.ui.components.LocalAppToastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The highlight card open over the page: which highlight, and where it is on screen (window pixels). */
data class HighlightCardState(val highlightId: String, val anchor: Rect?)

/** The highlight being shared as an image, from its card; null while the share card is closed. */
@Stable
class QuoteSharing {
    var highlightId by mutableStateOf<String?>(null)
        private set

    fun open(highlightId: String) {
        this.highlightId = highlightId
    }

    fun close() {
        highlightId = null
    }
}

/**
 * A reader's side of its highlights, in any kind of book: makes one from selected words, opens the card on
 * a highlight (and its share card and note), and changes or removes it. Everything is saved at once; nothing
 * waits for the book to close. The card and the note are [HighlightCardOverlay] and [HighlightNoteDialog].
 */
@Stable
class HighlightEditor(
    private val store: ReaderAnnotationStore,
    private val scope: CoroutineScope,
    private val toasts: () -> AppToastState?,
) {
    var card by mutableStateOf<HighlightCardState?>(null)
        private set

    /** The highlight whose note is being written, if any. */
    var editingNoteOf by mutableStateOf<String?>(null)
        private set

    /** The highlight being shared as an image, if any. */
    val sharing = QuoteSharing()

    /**
     * Highlights [text] at [place] in the look of the last highlight, then opens its card at [anchor]
     * (window pixels), or its note when [withNote].
     */
    fun add(book: AnnotatedBook, place: AnnotationPlace, text: String, anchor: Rect?, withNote: Boolean) {
        val words = text.trim()
        if (words.isEmpty()) return
        scope.launch {
            val made = store.addHighlight(book = book, place = place, text = words, look = store.lastLook())
            if (withNote) editingNoteOf = made.id else card = HighlightCardState(made.id, anchor)
        }
    }

    fun open(highlightId: String, anchor: Rect?) {
        card = HighlightCardState(highlightId, anchor)
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
        scope.launch { store.removeHighlightWithUndo(highlight.id, toasts(), scope, HighlightCopy.Removed) }
    }
}

/** A [HighlightEditor] for the screen, saving with [store]. */
@Composable
fun rememberHighlightEditor(store: ReaderAnnotationStore): HighlightEditor {
    val scope = rememberCoroutineScope()
    val toastState = LocalAppToastState.current
    val latestToasts by rememberUpdatedState(toastState)
    return remember(store) { HighlightEditor(store = store, scope = scope, toasts = { latestToasts }) }
}

/** What a highlight, its card and its note say on screen, in every reader. */
object HighlightCopy {
    const val Highlight = "Highlight"
    const val Note = "Note"
    const val Copy = "Copy"
    const val Delete = "Delete"
    const val EditNote = "Edit note"
    const val AddNote = "Add note"
    const val NoteTitle = "Note"
    const val NotePlaceholder = "What this makes you think"
    const val Save = "Save"
    const val Cancel = "Cancel"
    const val ShareImage = "Share as an image"
    const val Removed = "Highlight removed"

    fun style(style: HighlightStyle): String = BookmarksCopy.style(style)

    fun color(color: HighlightColor): String = when (color) {
        HighlightColor.Yellow -> "Yellow"
        HighlightColor.Green -> "Green"
        HighlightColor.Blue -> "Blue"
        HighlightColor.Pink -> "Pink"
        HighlightColor.Orange -> "Orange"
    }
}

/** Puts [text] on the clipboard, as the readers' Copy does. */
fun Context.copyToClipboard(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
}
