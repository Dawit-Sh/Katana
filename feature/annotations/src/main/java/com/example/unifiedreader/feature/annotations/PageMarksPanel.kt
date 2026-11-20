package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * The marks panel of a book read as page images (PDF, DjVu, comics), inside the reader's panel: its page bookmarks
 * (go, name, remove) and, when its pages have text, its highlights under their pages, with Export highlights. A
 * row goes to its page ([onJumpToPage]); the list opens around page [pageIndex] of [pageCount].
 */
@Composable
fun ColumnScope.PageMarksPanelBody(
    bookmarks: PageBookmarks,
    export: BookMarksExport,
    highlights: List<ReaderHighlight>,
    emptyText: String,
    // Whether the book's pages have text to highlight (a PDF); a comic or a DjVu book counts only bookmarks.
    hasText: Boolean,
    pageIndex: Int,
    pageCount: Int,
    onJumpToPage: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val latestJump by rememberUpdatedState(onJumpToPage)
    val actions = remember(bookmarks, export) {
        BookMarksPanelActions(
            onJump = { locatorJson ->
                pageIndexOfBookmarkLocator(locatorJson)?.let { page ->
                    // The mark opened becomes the one the strip's Previous and Next go on from.
                    bookmarks.steps.opened { stop -> stop.locatorJson == locatorJson }
                    latestJump(page)
                }
            },
            onDeleteBookmark = { bookmark -> bookmarks.delete(bookmark.id) },
            onNameBookmark = { bookmark, title, note -> bookmarks.name(bookmark.id, title, note) },
            onDeleteWord = {},
            export = export,
        )
    }
    BookMarksPanelBody(
        highlights = highlights,
        words = emptyList(),
        bookmarks = bookmarks.all,
        actions = actions,
        emptyText = emptyText,
        onDismissRequest = onDismissRequest,
        here = if (pageCount > 0) pageIndex.toDouble() / pageCount else null,
        subtitle = listOfNotNull(
            MarkCounts.highlights(highlights.size).takeIf { hasText },
            MarkCounts.bookmarks(bookmarks.all.size),
        ).joinToString(" · "),
    )
}
