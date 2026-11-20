package com.example.unifiedreader.feature.annotations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppToastState
import com.example.unifiedreader.ui.components.LocalAppToastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Bookmarks in books read as page images: PDF, DjVu and comics. Their place is the page itself
 * (0-based [pageIndex] in the locator, "page:N" as the href), so a bookmark finds its page again at any
 * zoom, and the Bookmarks page and a book's page list open them like an EPUB's.
 */

private const val PageHrefPrefix = "page:"
private const val PageKey = "page"

/** Where a bookmark on page [pageIndex] (0-based) of [pageCount] sits. */
fun pageBookmarkPlace(pageIndex: Int, pageCount: Int): AnnotationPlace = AnnotationPlace(
    href = "$PageHrefPrefix$pageIndex",
    locatorJson = JSONObject().put(PageKey, pageIndex).toString(),
    chapterTitle = null,
    progression = if (pageCount > 1) pageIndex.toDouble() / (pageCount - 1) else 0.0,
)

/** The 0-based page a page bookmark's locator names, or null for any other place (an EPUB's). */
fun pageIndexOfBookmarkLocator(locatorJson: String): Int? = runCatching {
    JSONObject(locatorJson).takeIf { it.has(PageKey) }?.getInt(PageKey)
}.getOrNull()?.takeIf { it >= 0 }

/** The 0-based page [this] bookmark is on, if it is a page bookmark. */
val ReaderBookmark.pageIndex: Int?
    get() = place.href.removePrefix(PageHrefPrefix)
        .takeIf { place.href.startsWith(PageHrefPrefix) }
        ?.toIntOrNull()

/** What a page-image reader keeps bookmarks with: the store, and the places other pages ask it to open at. */
class PageBookmarkSources(
    val store: ReaderBookmarkStore,
    val jumps: AnnotationJumps?,
)

/** The open page-image book's bookmarks: the top bar's button and the ribbon. Saved at once. */
@Stable
class PageBookmarks internal constructor(
    private val sources: PageBookmarkSources,
    private val book: AnnotatedBook,
    private val scope: CoroutineScope,
    private val toasts: () -> AppToastState?,
    val all: List<ReaderBookmark>,
    /** Previous and Next through the book's bookmarks, after one is opened from a list. */
    val steps: MarkSteps = MarkSteps(),
) {
    private val store: ReaderBookmarkStore get() = sources.store
    internal val jumps: AnnotationJumps? get() = sources.jumps
    private var busy = false

    /** The page of the bookmark before ([forward] false) or after the one stepped from; null at either end. */
    fun stepPage(forward: Boolean): Int? = steps.step(forward)?.let { stop -> pageIndexOfBookmarkLocator(stop.locatorJson) }

    /** The bookmarks on page [pageIndex]. */
    fun onPage(pageIndex: Int): List<ReaderBookmark> = all.filter { it.pageIndex == pageIndex }

    /** Removes page [pageIndex]'s bookmarks when it has any, otherwise bookmarks it. */
    fun toggle(pageIndex: Int, pageCount: Int) {
        if (busy || pageCount <= 0) return
        val onPage = onPage(pageIndex)
        busy = true
        scope.launch {
            try {
                if (onPage.isNotEmpty()) {
                    store.removeBookmarksWithUndo(onPage.map(ReaderBookmark::id), toasts(), scope, PageBookmarkCopy.Removed)
                } else {
                    store.addBookmark(
                        book = book,
                        place = pageBookmarkPlace(pageIndex, pageCount),
                        page = pageIndex + 1,
                        pageCount = pageCount,
                        text = null,
                    )
                    toasts()?.show(AppToastMessage(text = PageBookmarkCopy.Added, icon = AppToastIcon.Added))
                }
            } finally {
                busy = false
            }
        }
    }
}

/** A page-image book's bookmarks, or null when the reader has no bookmark store. */
@Composable
fun rememberPageBookmarks(
    sources: PageBookmarkSources?,
    itemId: String,
    title: String,
    author: String?,
): PageBookmarks? {
    val scope = rememberCoroutineScope()
    val toasts = LocalAppToastState.current
    val latestToasts by rememberUpdatedState(toasts)
    val store = sources?.store
    val flow = remember(store, itemId) { store?.bookmarks(itemId) ?: flowOf(emptyList()) }
    val all by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val steps = remember(itemId) { MarkSteps() }
    LaunchedEffect(steps, all) { steps.update(markStops(highlights = emptyList(), bookmarks = all)) }
    if (sources == null) return null
    return remember(sources, itemId, title, author, all) {
        PageBookmarks(
            sources = sources,
            book = AnnotatedBook(itemId = itemId, title = title, author = author),
            scope = scope,
            toasts = { latestToasts },
            all = all,
            steps = steps,
        )
    }
}

/**
 * Goes to a page the Bookmarks page or the book's page asked for ([AnnotationJumps]), once the book
 * shows ([ready]): the reader opens where it was left, then moves to the bookmark's page, and the
 * bookmark there becomes the one [PageBookmarks.steps] go on from.
 */
@Composable
fun PageBookmarkJumpEffect(
    bookmarks: PageBookmarks?,
    itemId: String,
    ready: Boolean,
    onJump: (pageIndex: Int) -> Unit,
) {
    val latestOnJump by rememberUpdatedState(onJump)
    val jumps = bookmarks?.jumps
    LaunchedEffect(jumps, itemId, ready) {
        if (!ready || jumps == null) return@LaunchedEffect
        val pageIndex = jumps.take(itemId)?.let(::pageIndexOfBookmarkLocator) ?: return@LaunchedEffect
        bookmarks?.steps?.opened { stop -> pageIndexOfBookmarkLocator(stop.locatorJson) == pageIndex }
        latestOnJump(pageIndex)
    }
}

/** The ribbon hanging from the page's top edge while the page is bookmarked, as in an EPUB. */
@Composable
fun BoxScope.PageBookmarkRibbon(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.align(Alignment.TopEnd).padding(end = PageRibbonEndInset),
    ) {
        BookmarkRibbon(width = PageRibbonWidth, height = PageRibbonHeight)
    }
}

// The same ribbon as an EPUB page's (EpubBookmarks.kt).
private val PageRibbonEndInset = 72.dp
private val PageRibbonWidth = 26.dp
private val PageRibbonHeight = 46.dp

object PageBookmarkCopy {
    const val Added = "Page bookmarked"
    const val Removed = "Bookmark removed"
    const val Bookmark = "Bookmark this page"
    const val RemoveBookmark = "Remove this page's bookmark"
}
