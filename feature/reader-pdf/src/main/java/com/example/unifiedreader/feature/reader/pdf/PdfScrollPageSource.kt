package com.example.unifiedreader.feature.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.runSuspendCatching
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The book behind the scrolling page view, kept open while it shows: each page's shape and the page
 * drawn at a width. Drawing runs off the main thread, one page at a time.
 */
internal interface PdfScrollPageSource {
    /** Page [pageIndex]'s width over its height, or null when it can't be read. */
    suspend fun pageAspect(pageIndex: Int): Float?

    /**
     * Page [pageIndex] drawn [widthPx] wide (its height follows its shape) on white, or null when it
     * can't be drawn or the caller stopped waiting. The caller owns the bitmap.
     */
    suspend fun render(pageIndex: Int, widthPx: Int): Bitmap?

    /** Lets the book go; a page being drawn finishes first. */
    fun close()
}

/** PdfRenderer for a PDF, DjVuLibre for a DjVu book, as in turn-pages mode. */
internal fun pdfScrollPageSource(
    context: Context,
    item: ReadableItem,
    documentUri: Uri,
): PdfScrollPageSource = if (item.format == FormatType.DJVU) {
    DjvuScrollPageSource(context, item, documentUri)
} else {
    PdfRendererScrollPageSource(context, documentUri)
}

/**
 * A PDF kept open with [PdfRenderer] for a few pages at a time (turn-pages mode opens it again for every
 * page). PdfRenderer takes one page at a time, so every call waits its turn on [lock]. PDFium keeps what it
 * read of each page (a scanned page's image data, about a megabyte) until the document closes, so the
 * renderer is opened again every [PdfRendererPagesPerOpen] pages: memory stays flat through a long book.
 */
private class PdfRendererScrollPageSource(
    private val context: Context,
    private val documentUri: Uri,
) : PdfScrollPageSource {
    private val lock = Mutex()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pagesSinceOpen = 0

    @Volatile
    private var closed = false

    override suspend fun pageAspect(pageIndex: Int): Float? = withRenderer { renderer ->
        if (pageIndex !in 0 until renderer.pageCount) return@withRenderer null
        renderer.openPage(pageIndex).use { page ->
            if (page.width > 0 && page.height > 0) page.width.toFloat() / page.height else null
        }
    }

    override suspend fun render(pageIndex: Int, widthPx: Int): Bitmap? = withRenderer(drawsPage = true) { renderer ->
        if (pageIndex !in 0 until renderer.pageCount || widthPx <= 0) return@withRenderer null
        renderer.openPage(pageIndex).use { page ->
            val height = (widthPx.toFloat() * page.height / page.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
            val drawn = try {
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                true
            } catch (_: RuntimeException) {
                false
            }
            bitmap.takeIf { drawn } ?: run {
                bitmap.recycle()
                null
            }
        }
    }

    override fun close() {
        closed = true
        // A page being drawn holds the lock; it lets the renderer go when it finishes.
        if (lock.tryLock()) {
            try {
                release()
            } finally {
                lock.unlock()
            }
        }
    }

    // Only drawn pages count toward reopening: reading a page's size doesn't load its images.
    private suspend fun <T : Any> withRenderer(
        drawsPage: Boolean = false,
        block: (PdfRenderer) -> T?,
    ): T? = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                if (closed || !currentCoroutineContext().isActive) return@withLock null
                val opened = openRenderer()
                if (drawsPage && ++pagesSinceOpen >= PdfRendererPagesPerOpen) closeAfterThis = true
                runSuspendCatching { block(opened) }.getOrNull()?.let { result ->
                    // A bitmap nobody waits for any more goes now rather than at the next collection.
                    if (!currentCoroutineContext().isActive && result is Bitmap) {
                        result.recycle()
                        null
                    } else {
                        result
                    }
                }
            } finally {
                if (closed || closeAfterThis) release()
                closeAfterThis = false
            }
        }
    }

    private fun openRenderer(): PdfRenderer = renderer ?: run {
        val opened = requireNotNull(context.contentResolver.openFileDescriptor(documentUri, "r")) {
            "The PDF at $documentUri can't be opened"
        }
        descriptor = opened
        PdfRenderer(opened).also { renderer = it }
    }

    private var closeAfterThis = false

    private fun release() {
        try {
            renderer?.close()
            descriptor?.close()
        } catch (_: java.io.IOException) {
            // Closing a file that is already gone: nothing is left to free.
        } finally {
            renderer = null
            descriptor = null
            pagesSinceOpen = 0
        }
    }
}

private const val PdfRendererPagesPerOpen = 12
