package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.entity.ReaderBookmarkEntity

/**
 * How a book's server keeps its bookmarks' places: an EPUB CFI for a book read as text, the page (from 1,
 * written as a number) for one read as page images (a PDF or a comic). A page bookmark's place is known
 * the moment it is made, so it needs no reader to work it out, and one from the server shows at once.
 */
sealed interface BookmarkPlaces {
    data object EpubCfi : BookmarkPlaces

    /** By the page; [pageCount], when known, places a page from the server in the run of the book. */
    data class Pages(val pageCount: Int?) : BookmarkPlaces
}

/** The name the server has, or would have, for [this]: its own, else its chapter's, else its page's. */
internal fun ReaderBookmarkEntity.serverTitle(): String? = title ?: defaultServerTitle()

// What Grimmory's web reader names a bookmark it makes: by its chapter in an EPUB, "Page 12" by the page.
internal fun ReaderBookmarkEntity.defaultServerTitle(): String? =
    chapterTitle?.trim()?.takeIf(String::isNotEmpty) ?: pageNumberOfHref(href)?.let(::pageTitle)

internal fun pageTitle(pageNumber: Int): String = "Page $pageNumber"

/**
 * Where [this] bookmark is the server's way: its CFI in an EPUB (worked out by the reader, null until
 * then), or its page from 1 in a book read by the page.
 */
internal fun ReaderBookmarkEntity.serverPlace(places: BookmarkPlaces): String? = when (places) {
    BookmarkPlaces.EpubCfi -> remoteCfi
    is BookmarkPlaces.Pages -> pageNumberOfHref(href)?.toString()
}

internal fun BookmarkPlaces.holds(place: String): Boolean = when (this) {
    BookmarkPlaces.EpubCfi -> place.toPageNumberOrNull() == null
    is BookmarkPlaces.Pages -> place.toPageNumberOrNull() != null
}

internal fun BookmarkPlaces.same(here: String?, there: String): Boolean = when (this) {
    BookmarkPlaces.EpubCfi -> sameCfiPlace(here, there)
    is BookmarkPlaces.Pages -> here?.toPageNumberOrNull()?.let { page -> page == there.toPageNumberOrNull() } ?: false
}

// A page bookmark's href is "page:N", N from 0 (PageBookmarks.kt); its page from 1.
internal fun pageNumberOfHref(href: String): Int? =
    href.takeIf { it.startsWith(PAGE_HREF_PREFIX) }?.removePrefix(PAGE_HREF_PREFIX)?.toIntOrNull()?.takeIf { it >= 0 }?.plus(1)

/** A page number from 1 written as digits alone, as a page place is; null for anything else (a CFI). */
fun String.toPageNumberOrNull(): Int? = trim().takeIf { text -> text.isNotEmpty() && text.all(Char::isDigit) }
    ?.toIntOrNull()?.takeIf { it > 0 }

internal const val PAGE_HREF_PREFIX = "page:"
