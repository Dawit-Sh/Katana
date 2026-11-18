package com.example.unifiedreader.feature.reader.pdf

import android.content.Context
import android.net.Uri
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem

/** Draws one page of the book the page reader shows, for the page itself and the page strip. */
internal typealias PageImageRenderer = suspend (documentUri: Uri, pageIndex: Int, targetLongEdgePx: Int) -> PdfPageRenderState

/** PdfRenderer for a PDF, DjVuLibre for a DjVu book. */
internal fun pageImageRenderer(
    context: Context,
    item: ReadableItem,
): PageImageRenderer = if (item.format == FormatType.DJVU) {
    { documentUri, pageIndex, targetLongEdgePx -> renderDjvuPage(context, item, documentUri, pageIndex, targetLongEdgePx) }
} else {
    { documentUri, pageIndex, targetLongEdgePx -> renderPdfPage(context, documentUri, pageIndex, targetLongEdgePx) }
}
