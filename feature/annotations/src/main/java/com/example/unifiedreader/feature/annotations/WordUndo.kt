package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppToastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Undo for a removed saved word: words don't sync, so it goes for good and Undo writes it back whole.

/**
 * Removes [word] and says [text] with Undo, which brings it back exactly (its sentence, place, note
 * and review state). Suspends until it is removed; Undo runs in [scope].
 */
suspend fun ReaderAnnotationStore.removeWordWithUndo(
    word: SavedWord,
    toasts: AppToastState?,
    scope: CoroutineScope,
    text: String = BookmarksCopy.WordRemoved,
) {
    val toast = removeWordForUndo(word, scope, text)
    toasts?.show(toast)
}

/** Removes [word]; the toast that says [text], its Undo bringing it back in [scope]. */
internal suspend fun ReaderAnnotationStore.removeWordForUndo(
    word: SavedWord,
    scope: CoroutineScope,
    text: String,
): AppToastMessage {
    deleteWord(word.id)
    return undoToast(text, canUndo = true) { scope.launch { restoreWord(word) } }
}
