package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppSearchField
import com.example.unifiedreader.ui.components.AppSheetDivider
import com.example.unifiedreader.ui.components.AppSheetLabel
import com.example.unifiedreader.ui.components.AppSheetSectionEnd

/** "Bookmarks · 3": a list's heading with how many it holds. */
fun bookmarksHeading(count: Int): String = if (count > 0) "${BookmarksCopy.Bookmarks} · $count" else BookmarksCopy.Bookmarks

/**
 * The search field over a book's marks in a panel: shown once there is something to search, and
 * kept while a search is under way even if it finds nothing.
 */
@Composable
fun MarkSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    AppSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = placeholder,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The book page's (the library's details sheet) bookmarks section: "Bookmarks · 3", then every
 * bookmark of the book in reading order, a tap opening the book there. With none, one quiet line.
 */
fun LazyListScope.bookPageBookmarks(
    bookmarks: List<ReaderBookmark>,
    onOpen: (ReaderBookmark) -> Unit,
) {
    item(key = "bookmarks-label") {
        Column(modifier = Modifier.padding(top = AppLayout.gridGap)) {
            AppSheetDivider()
            AppSheetLabel(text = bookmarksHeading(bookmarks.size))
        }
    }
    if (bookmarks.isEmpty()) {
        item(key = "bookmarks-empty") {
            Text(
                text = BookmarksCopy.BookPageEmpty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppLayout.inset),
            )
        }
    }
    if (bookmarks.isNotEmpty()) {
        // A box of its own that scrolls once there are more than about four, so a book with many
        // bookmarks doesn't push About and the rest of the page far down.
        item(key = "bookmarks-list") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = BookPageListMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                bookmarks.forEach { bookmark ->
                    key(bookmark.id) {
                        BookmarkLine(
                            bookmark = bookmark,
                            onClick = { onOpen(bookmark) },
                            onDelete = null,
                            modifier = Modifier.padding(horizontal = AppLayout.inset),
                            showWhen = true,
                        )
                    }
                }
            }
        }
    }
    item(key = "bookmarks-end") { AppSheetSectionEnd() }
}

/**
 * One bookmark: the amber ribbon, its name or "Page 8 of 78 · 9%", its first words or chapter, its
 * note, then name-or-note ([onEdit]) and delete when given.
 */
@Composable
fun BookmarkLine(
    bookmark: ReaderBookmark,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
    showWhen: Boolean = false,
    onEdit: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LineMinHeight)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.BookmarkFilled,
            contentDescription = null,
            tint = MarkTint.Bookmark,
            modifier = Modifier.size(LineIcon),
        )
        BookmarkLineText(bookmark, showWhen, Modifier.weight(1f))
        onEdit?.let { edit ->
            AppIconButton(onClick = edit) {
                Icon(imageVector = AppIcons.Edit, contentDescription = BookmarkNamingCopy.Edit)
            }
        }
        onDelete?.let { delete ->
            AppIconButton(onClick = delete) {
                Icon(imageVector = AppIcons.Delete, contentDescription = BookmarksCopy.Delete)
            }
        }
    }
}

/** A bookmark's lines: its name or place, its first words or chapter, its note, and when ([showWhen]). */
@Composable
private fun BookmarkLineText(bookmark: ReaderBookmark, showWhen: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = bookmarkHeadline(bookmark),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        bookmarkSubline(bookmark)?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        bookmark.note?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = NOTE_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showWhen) {
            MetaLine(
                listOfNotNull(
                    chapterOf(bookmark.place.chapterTitle, bookmark.book)?.takeIf { bookmark.text != null || bookmark.title != null },
                    BookmarksCopy.moment(bookmark.createdAtEpochMillis),
                ),
            )
        }
    }
}

private const val NOTE_LINES = 2
private val LineIcon = 18.dp
private val LineMinHeight = 48.dp

// About four and a half bookmarks, so the next one peeks out and the box reads as one that scrolls.
private val BookPageListMaxHeight = 330.dp
