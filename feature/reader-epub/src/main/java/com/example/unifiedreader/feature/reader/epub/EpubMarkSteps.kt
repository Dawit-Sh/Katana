package com.example.unifiedreader.feature.reader.epub

import androidx.compose.runtime.Composable
import com.example.unifiedreader.feature.annotations.MarkSteps
import com.example.unifiedreader.feature.annotations.MarkStepsCopy
import com.example.unifiedreader.feature.annotations.MarkStop
import com.example.unifiedreader.feature.reader.core.readerStepDockStrip
import com.example.unifiedreader.ui.components.AppIcons
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/*
 * The EPUB reader's strip for stepping through the book's marks ([MarkSteps]): it shows in the dock
 * after a highlight or bookmark is opened from the Bookmarks page, the book's page or the marks panel.
 */

/** The reader went to [locator] from a list: the highlight or bookmark there becomes the one stepped from. */
internal fun MarkSteps.openedAt(locator: Locator) {
    val key = locator.toJSON().toString()
    opened { stop -> stop.locatorKey() == key }
}

/** The dock's strip while a mark opened from a list is the current one, [onGo] going to the next; null otherwise. */
@Composable
internal fun MarkSteps.epubDockStrip(onGo: (Locator) -> Unit): (@Composable () -> Unit)? {
    val mark = shown
    return readerStepDockStrip(
        icon = if (mark?.isBookmark == true) AppIcons.Bookmark else AppIcons.CollectionsBookmark,
        title = mark?.label,
        position = position,
        previousLabel = MarkStepsCopy.Previous,
        nextLabel = MarkStepsCopy.Next,
        closeLabel = MarkStepsCopy.Close,
        onStep = { forward -> step(forward)?.toLocator()?.let(onGo) },
        onClose = this::close,
    )
}

// The same place written the way this reader writes it, so a list's and the panel's agree.
private fun MarkStop.locatorKey(): String? = toLocator()?.toJSON()?.toString()

private fun MarkStop.toLocator(): Locator? = try {
    Locator.fromJSON(JSONObject(locatorJson))
} catch (_: JSONException) {
    null
}
