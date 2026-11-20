package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.runtime.Composable
import com.example.unifiedreader.feature.annotations.MarkStepsCopy
import com.example.unifiedreader.feature.annotations.PageBookmarks
import com.example.unifiedreader.feature.reader.core.readerStepDockStrip
import com.example.unifiedreader.ui.components.AppIcons

/**
 * The dock's strip after a mark was opened from the Bookmarks page, the book's page or the marks panel: Previous
 * and Next go to the page of the mark before or after it ([onGo]), a PDF's highlights and bookmarks together (the
 * steps hold both, [PageBookmarks.steps]); null while none was opened.
 */
@Composable
internal fun PageBookmarks.pageMarkDockStrip(onGo: (pageIndex: Int) -> Unit): (@Composable () -> Unit)? =
    readerStepDockStrip(
        icon = if (steps.shown?.isBookmark == false) AppIcons.CollectionsBookmark else AppIcons.Bookmark,
        title = steps.shown?.label,
        position = steps.position,
        previousLabel = MarkStepsCopy.Previous,
        nextLabel = MarkStepsCopy.Next,
        closeLabel = MarkStepsCopy.Close,
        onStep = { forward -> stepPage(forward)?.let(onGo) },
        onClose = steps::close,
    )
