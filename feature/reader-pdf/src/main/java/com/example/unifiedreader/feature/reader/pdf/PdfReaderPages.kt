package com.example.unifiedreader.feature.reader.pdf

import com.example.unifiedreader.feature.reader.core.ReaderSessionSnapshot
import com.example.unifiedreader.feature.reader.preferences.ReaderPagesMode
import com.example.unifiedreader.feature.reader.preferences.readerPagesModeOrNull
import java.util.Locale
import kotlin.math.abs

/** The book's own "Pages" choice, kept in its reader session: "Turn" or "Scroll". */
internal const val PdfPagesKey = "pdf.pages"

/** Where a scrolling book was, finer than its page: "page:share", the page at the top and how far down it. */
internal const val PdfScrollAnchorKey = "pdf.scrollAnchor"

/**
 * How a PDF or DjVu book opens: its own choice once it has one, else the one last picked in any of
 * them ([lastPick]), else turning pages.
 */
internal fun restorePdfPagesMode(
    session: ReaderSessionSnapshot?,
    lastPick: ReaderPagesMode?,
): ReaderPagesMode = readerPagesModeOrNull(session?.preferences?.get(PdfPagesKey)) ?: lastPick ?: ReaderPagesMode.Turn

/** The page at the top of a scrolling book ([pageIndex], from 0) and how far into it the top edge is (0 to 1). */
internal data class PdfScrollAnchor(
    val pageIndex: Int,
    val offsetRatio: Float,
)

internal fun encodePdfScrollAnchor(anchor: PdfScrollAnchor): String =
    "${anchor.pageIndex}:${String.format(Locale.US, "%.4f", anchor.offsetRatio.coerceIn(0f, 1f))}"

internal fun decodePdfScrollAnchor(value: String?): PdfScrollAnchor? {
    val parts = value?.split(':')?.takeIf { it.size == 2 }
    val page = parts?.get(0)?.toIntOrNull()?.takeIf { it >= 0 }
    val ratio = parts?.get(1)?.toFloatOrNull()?.takeIf { it.isFinite() }
    return if (page != null && ratio != null) PdfScrollAnchor(pageIndex = page, offsetRatio = ratio.coerceIn(0f, 1f)) else null
}

/**
 * Where a scrolling book opens: the finer place it was left at, while it still agrees with the saved
 * page ([progressPageIndex], the page in view then), else the top of that page. A page saved since on
 * another device, or in turn-pages mode, wins over an old anchor.
 */
internal fun pdfScrollStart(
    progressPageIndex: Int,
    anchor: PdfScrollAnchor?,
    pageCount: Int,
): PdfScrollAnchor {
    val lastPage = (pageCount - 1).coerceAtLeast(0)
    val page = progressPageIndex.coerceIn(0, lastPage)
    return anchor
        ?.takeIf { saved -> saved.pageIndex in 0..lastPage && abs(saved.pageIndex - page) <= 1 }
        ?: PdfScrollAnchor(pageIndex = page, offsetRatio = 0f)
}
