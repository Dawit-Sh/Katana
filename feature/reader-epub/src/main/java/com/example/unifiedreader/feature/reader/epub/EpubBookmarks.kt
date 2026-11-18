package com.example.unifiedreader.feature.reader.epub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.feature.annotations.AnnotationJumps
import com.example.unifiedreader.feature.annotations.BookmarkRibbon
import com.example.unifiedreader.feature.annotations.ReaderBookmark
import kotlin.math.floor
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/** The navigator fragment's tag for a book, so the reader can find the navigator showing it. */
internal fun epubNavigatorTag(itemId: String): String = "readium-epub-$itemId"

/** The page on screen as the bookmark button sees it: its chapter file, its page in that file, and its position. */
internal data class EpubPageHere(
    val href: String?,
    val pageIndex: Int,
    val totalPages: Int?,
    val position: Int,
)

/** A bookmark's place, read once from its locator: the chapter file, how far into it, and its position. */
internal data class EpubBookmarkSpot(
    val href: String,
    val progression: Double?,
    val position: Int?,
)

/**
 * Whether the bookmark falls on the page on screen. Paged, it is on the page whose stretch of the
 * chapter holds its place (so a bookmark still finds its page after the text size changed);
 * scrolled, where pages have no edges, it is on the same position.
 */
internal fun EpubBookmarkSpot.isOn(here: EpubPageHere): Boolean {
    val total = here.totalPages?.takeIf { it > 0 && here.pageIndex >= 0 }
    return when {
        here.href == null || href != here.href -> false
        total != null && progression != null ->
            floor(progression * total + PAGE_EPSILON).toInt().coerceIn(0, total - 1) == here.pageIndex
        else -> position != null && position == here.position
    }
}

internal fun ReaderBookmark.spot(): EpubBookmarkSpot? = try {
    Locator.fromJSON(JSONObject(place.locatorJson))?.let { locator ->
        EpubBookmarkSpot(
            href = locator.href.removeFragment().toString(),
            progression = locator.locations.progression,
            position = locator.locations.position,
        )
    }
} catch (_: JSONException) {
    null
}

/** The book's bookmarks on the page on screen; the button is filled and the ribbon hangs while there is one. */
@Composable
internal fun rememberPageBookmarks(
    bookmarks: List<ReaderBookmark>,
    currentLocator: Locator?,
    framePage: EpubResourcePage,
    currentPage: Int,
): List<ReaderBookmark> {
    val spots = remember(bookmarks) { bookmarks.mapNotNull { bookmark -> bookmark.spot()?.let { bookmark to it } } }
    return remember(spots, currentLocator, framePage, currentPage) {
        val here = EpubPageHere(
            href = currentLocator?.href?.removeFragment()?.toString(),
            pageIndex = framePage.pageIndex,
            totalPages = framePage.totalPages,
            position = currentLocator?.locations?.position ?: currentPage,
        )
        spots.filter { (_, spot) -> spot.isOn(here) }.map { (bookmark, _) -> bookmark }
    }
}

/** The ribbon hanging from the page's top edge while the page is bookmarked. */
@Composable
internal fun BoxScope.EpubBookmarkRibbon(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopEnd).padding(end = RibbonEndInset),
    ) {
        BookmarkRibbon(width = RibbonWidth, height = RibbonHeight)
    }
}

/**
 * Goes to a place the Bookmarks page asked for ([AnnotationJumps]), once the book shows ([ready]):
 * the reader opens where it was left, then moves to the bookmark or highlight.
 */
@Composable
internal fun EpubPendingJumpEffect(
    jumps: AnnotationJumps?,
    itemId: String,
    ready: Boolean,
    onJump: (Locator) -> Unit,
) {
    val latestOnJump by rememberUpdatedState(onJump)
    LaunchedEffect(jumps, itemId, ready) {
        if (!ready || jumps == null) return@LaunchedEffect
        val json = jumps.take(itemId) ?: return@LaunchedEffect
        val locator = try {
            Locator.fromJSON(JSONObject(json))
        } catch (_: JSONException) {
            null
        }
        locator?.let(latestOnJump)
    }
}

/**
 * The first words on the page on screen, to name a bookmark by: the text from the page's top
 * left corner on. Failing that, the first element that shows (which may have started on the page
 * before).
 */
@OptIn(ExperimentalReadiumApi::class)
internal suspend fun FragmentActivity.epubFirstVisibleText(itemId: String): String? {
    val navigator = supportFragmentManager.findFragmentByTag(epubNavigatorTag(itemId)) as? EpubNavigatorFragment ?: return null
    val pageStart = runSuspendCatching { navigator.evaluateJavascript(PageStartTextScript) }.getOrNull()?.let(::jsonString)
    return pageStart ?: runSuspendCatching { navigator.firstVisibleElementLocator() }.getOrNull()?.text?.highlight
}

/** A string a script gave back, as WebView hands it over (JSON); null for anything else. */
internal fun jsonString(result: String): String? = try {
    (JSONTokener(result).nextValue() as? String)?.takeIf(String::isNotBlank)
} catch (_: JSONException) {
    null
}

/**
 * The text from the first caret position at the page's top left (the first line's start) on, up
 * to 300 characters. Readium lays a page out in columns, so the viewport is the page on screen.
 */
private const val PageStartTextScript = """
(function() {
  var w = window.innerWidth, h = window.innerHeight;
  for (var y = 4; y < h / 2; y += 8) {
    for (var x = 4; x < w / 2; x += 8) {
      var range = document.caretRangeFromPoint(x, y);
      var node = range && range.startContainer;
      if (node && node.nodeType === 3 && node.textContent.slice(range.startOffset).trim()) {
        var out = node.textContent.slice(range.startOffset);
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        walker.currentNode = node;
        while (out.length < 300 && walker.nextNode()) out += ' ' + walker.currentNode.textContent;
        return out.replace(/\s+/g, ' ').trim().slice(0, 300);
      }
    }
  }
  return null;
})()
"""

/** A page's stretch is its index over the chapter's page count; this keeps a place at a page's very start on it. */
private const val PAGE_EPSILON = 0.001
private val RibbonEndInset = 72.dp
private val RibbonWidth = 26.dp
private val RibbonHeight = 46.dp
