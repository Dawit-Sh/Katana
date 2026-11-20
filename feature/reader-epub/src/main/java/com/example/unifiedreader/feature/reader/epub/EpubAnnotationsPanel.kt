package com.example.unifiedreader.feature.reader.epub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.unifiedreader.feature.annotations.BookMarksPanelActions
import com.example.unifiedreader.feature.annotations.BookMarksPanelBody
import com.example.unifiedreader.feature.annotations.ReaderBookmark
import com.example.unifiedreader.feature.annotations.ReaderHighlight
import com.example.unifiedreader.feature.annotations.SavedWord
import com.example.unifiedreader.feature.reader.core.ReaderChromeDefaults
import com.example.unifiedreader.feature.reader.core.ReaderPanel
import com.example.unifiedreader.feature.reader.core.ReaderPanelSlot
import com.example.unifiedreader.ui.components.AppFloatingPanelPlacement
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * This book's marks: its bookmarks and highlights (in reading order, under their chapters; a tap goes there) and
 * saved words (Look up, remove), with Export highlights (this book, Markdown) at the foot, as every reader's
 * marks panel ([BookMarksPanelBody]). It opens at the highlights around [here]. Every book's export is on the
 * Bookmarks page.
 */
@Composable
internal fun EpubAnnotationsPanel(
    isOpen: Boolean,
    slot: ReaderPanelSlot,
    highlights: List<ReaderHighlight>,
    words: List<SavedWord>,
    controller: EpubAnnotationController,
    onDismissRequest: () -> Unit,
    onJump: (Locator) -> Unit,
    bookmarks: List<ReaderBookmark> = emptyList(),
    here: Double? = null,
) {
    ReaderPanel(
        visible = isOpen,
        placement = AppFloatingPanelPlacement.End,
        width = ReaderChromeDefaults.contentsWidth,
        slot = slot,
        onDismissRequest = onDismissRequest,
    ) {
        val actions = remember(controller, onJump) {
            BookMarksPanelActions(
                onJump = { locatorJson -> locatorJson.toLocatorOrNull()?.let(onJump) },
                onDeleteBookmark = { bookmark -> controller.bookmarks.delete(bookmark.id) },
                onNameBookmark = { bookmark, title, note -> controller.bookmarks.name(bookmark.id, title, note) },
                onDeleteWord = { word -> controller.deleteWord(word.id) },
                export = controller.export,
            )
        }
        BookMarksPanelBody(
            highlights = highlights,
            words = words,
            bookmarks = bookmarks,
            actions = actions,
            emptyText = EpubAnnotationCopy.Empty,
            onDismissRequest = onDismissRequest,
            here = here,
        )
    }
}

private fun String.toLocatorOrNull(): Locator? = try {
    Locator.fromJSON(JSONObject(this))
} catch (_: JSONException) {
    null
}
