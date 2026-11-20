package com.example.unifiedreader

import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.feature.annotations.BookmarkPlaces

/**
 * A book on this device whose highlights and bookmarks sync with Grimmory's book [bookId]: one of
 * Grimmory's own, or a file here that is the very same file as one ([GrimmoryLocalCopies] says which).
 * Its marks stay under its own [item] id here.
 */
data class GrimmoryMarksBook(val item: ReadableItem, val bookId: String)

/**
 * The formats whose bookmarks Grimmory shares: EPUBs by CFI, PDFs and comics by page. DjVu isn't a
 * Grimmory format, and a MOBI, AZW3 or FB2 is read here as an EPUB Katana makes from it, whose places the
 * web reader (reading the original) can't use.
 */
fun FormatType.sharesBookmarksWithGrimmory(): Boolean = when (this) {
    FormatType.EPUB, FormatType.PDF, FormatType.CBZ, FormatType.CBR -> true
    else -> false
}

/** A Grimmory book whose bookmarks sync with it ([sharesBookmarksWithGrimmory]). */
fun ReadableItem.syncsBookmarksWithGrimmory(): Boolean =
    sourceType == SourceType.GRIMMORY && format.sharesBookmarksWithGrimmory() && !remoteId.isNullOrBlank()

/** This Grimmory book as its marks sync, or null when it isn't one. */
internal fun ReadableItem.asGrimmoryMarksBook(): GrimmoryMarksBook? =
    remoteId?.takeIf { sourceType == SourceType.GRIMMORY && it.isNotBlank() }?.let { bookId -> GrimmoryMarksBook(this, bookId) }

/** How Grimmory keeps this book's bookmark places: CFIs in an EPUB, pages in a PDF or comic. */
internal fun GrimmoryMarksBook.bookmarkPlaces(): BookmarkPlaces =
    if (item.format == FormatType.EPUB) BookmarkPlaces.EpubCfi else BookmarkPlaces.Pages(item.pageCount)

/** Grimmory books of the active server, and the copies here already checked against it. */
internal fun List<GrimmoryMarksBook>.onActiveGrimmoryServer(session: GrimmoryStoredSession): List<GrimmoryMarksBook> {
    val active = map(GrimmoryMarksBook::item).filter { it.sourceType == SourceType.GRIMMORY }
        .forActiveGrimmoryServer(session).mapTo(mutableSetOf(), ReadableItem::id)
    return filter { book -> book.item.sourceType != SourceType.GRIMMORY || book.item.id in active }
}
