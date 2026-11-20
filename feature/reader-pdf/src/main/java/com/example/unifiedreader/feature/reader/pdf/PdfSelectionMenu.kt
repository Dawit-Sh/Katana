package com.example.unifiedreader.feature.reader.pdf

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import com.example.unifiedreader.feature.annotations.HighlightCopy
import kotlin.math.roundToInt

/**
 * The menu over selected words on a PDF page, once the finger lifts: Highlight, Note and Copy, in Android's own
 * floating text toolbar, as the EPUB reader's (the same labels, in the same order). It stays over the words while
 * the page zooms or moves ([bounds], in this view's pixels), closes when the selection goes, and Back clears the
 * selection before it would leave the book.
 */
@Composable
internal fun PdfSelectionMenu(
    marks: PdfTextMarks,
    bounds: () -> Rect?,
    onAction: (PdfSelectionAction) -> Unit,
) {
    val view = LocalView.current
    val selection = marks.selection
    val resting = selection != null && selection.dragging == null
    val latestBounds by rememberUpdatedState(bounds)
    val latestAction by rememberUpdatedState(onAction)
    val holder = remember { PdfMenuHolder() }
    BackHandler(enabled = selection != null) { marks.clear() }
    DisposableEffect(view, resting, selection?.pageIndex, selection?.start, selection?.end) {
        if (!resting) return@DisposableEffect onDispose { }
        val callback = PdfSelectionActionModeCallback(
            bounds = { latestBounds() },
            onAction = { action -> latestAction(action) },
            onClosedBySystem = marks::clear,
        )
        holder.mode = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        onDispose {
            holder.mode = null
            callback.finish()
        }
    }
    LaunchedEffect(resting) {
        if (resting) snapshotFlow { latestBounds() }.collect { holder.mode?.invalidateContentRect() }
    }
}

private class PdfMenuHolder {
    var mode: ActionMode? = null
}

private class PdfSelectionActionModeCallback(
    private val bounds: () -> Rect?,
    private val onAction: (PdfSelectionAction) -> Unit,
    private val onClosedBySystem: () -> Unit,
) : ActionMode.Callback2() {
    private var mode: ActionMode? = null
    private var closed = false

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        this.mode = mode
        menu.add(Menu.NONE, HighlightItemId, HighlightOrder, HighlightCopy.Highlight)
        menu.add(Menu.NONE, NoteItemId, NoteOrder, HighlightCopy.Note)
        menu.add(Menu.NONE, CopyItemId, CopyOrder, android.R.string.copy)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val action = when (item.itemId) {
            HighlightItemId -> PdfSelectionAction.Highlight
            NoteItemId -> PdfSelectionAction.Note
            CopyItemId -> PdfSelectionAction.Copy
            else -> return false
        }
        finish()
        onAction(action)
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        val bySystem = !closed
        closed = true
        this.mode = null
        if (bySystem) onClosedBySystem()
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
        val rect = bounds() ?: return
        outRect.set(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
    }

    /** Closes the toolbar from our side; once only. */
    fun finish() {
        if (closed) return
        closed = true
        mode?.finish()
        mode = null
    }

    private companion object {
        const val HighlightItemId = 0x7EAD11
        const val NoteItemId = 0x7EAD12
        const val CopyItemId = 0x7EAD13
        const val HighlightOrder = 0
        const val NoteOrder = 1
        const val CopyOrder = 2
    }
}
