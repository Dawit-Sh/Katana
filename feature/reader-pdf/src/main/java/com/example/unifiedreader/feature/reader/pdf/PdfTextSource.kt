package com.example.unifiedreader.feature.reader.pdf

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfTextPage
import io.legere.pdfiumandroid.PdfiumCore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The words on a PDF's pages, read with PDFium (the book's own drawing stays with Android's PdfRenderer). Nothing
 * runs until a page's text is first asked for (a long press): then the file is opened once, and each page's text
 * is read once and kept while it is one of the last few asked for.
 *
 * PDFium is not safe to use from two threads, so every call into it runs on this source's one thread, in order;
 * [close] queues the closing behind whatever is reading and nothing runs after it. A file PDFium can't read (locked,
 * damaged) or a page it fails on gives no text, never a crash: the page simply has nothing to select.
 */
internal class PdfTextSource(
    context: Context,
    private val uri: Uri,
) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var executor: ExecutorService? = null
    private var dispatcher: CoroutineDispatcher? = null

    @Volatile
    private var closed = false

    // Touched only on the source's thread.
    private var opened: OpenedPdf? = null
    private var unreadable = false

    // Read on any thread, so behind the lock.
    private val pages = object : LinkedHashMap<Int, PdfPageText>(CachedPages, LoadFactor, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPageText>): Boolean = size > CachedPages
    }

    /** Page [pageIndex]'s text, read now if it isn't kept; null when the page or the file has none to give. */
    suspend fun page(pageIndex: Int): PdfPageText? =
        cached(pageIndex) ?: thread()?.let { thread -> withContext(thread) { readKept(pageIndex) } }

    /** Page [pageIndex]'s text if it was read already. */
    fun cached(pageIndex: Int): PdfPageText? = synchronized(lock) { pages[pageIndex] }

    /** Closes the file once whatever is reading has finished. Safe to call more than once. */
    fun close() {
        val running = synchronized(lock) {
            val wasOpen = !closed
            closed = true
            pages.clear()
            val running = executor.takeIf { wasOpen }
            executor = null
            dispatcher = null
            running
        } ?: return
        try {
            running.execute { closeDocument() }
        } catch (_: RejectedExecutionException) {
            // Already shut down: nothing is open.
        }
        running.shutdown()
    }

    private fun thread(): CoroutineDispatcher? = synchronized(lock) {
        when {
            closed -> null
            else -> dispatcher ?: Executors.newSingleThreadExecutor { task -> Thread(task, ThreadName).apply { isDaemon = true } }
                .also { executor = it }
                .asCoroutineDispatcher()
                .also { dispatcher = it }
        }
    }

    /** On the source's thread. */
    private fun readKept(pageIndex: Int): PdfPageText? {
        val read = cached(pageIndex) ?: openDocument()?.let { readPageQuietly(it, pageIndex) }
        if (read != null) synchronized(lock) { if (!closed) pages[pageIndex] = read }
        return read
    }

    private fun readPageQuietly(document: PdfDocument, pageIndex: Int): PdfPageText? =
        runCatching { readPage(document, pageIndex) }
            .onFailure { error -> Log.w(LogTag, "Couldn't read the text of page ${pageIndex + 1}", error) }
            .getOrNull()

    /** The open document, opened now if this is the first page asked for; null when closed or PDFium can't read it. */
    private fun openDocument(): PdfDocument? {
        if (opened == null && !unreadable && !closed) {
            opened = tryOpen()
            unreadable = opened == null
        }
        return opened?.document
    }

    private fun tryOpen(): OpenedPdf? = runCatching {
        // PDFium's own loader waits forever when its library can't load (a device without it), so load it here
        // first: that fails at once, as an UnsatisfiedLinkError, which runCatching takes too.
        PdfiumLibraries.forEach(System::loadLibrary)
        val descriptor = requireNotNull(appContext.contentResolver.openFileDescriptor(uri, "r")) { "No file" }
        val document = runCatching { PdfiumCore(appContext).newDocument(descriptor) }
            .onFailure { descriptor.closeQuietly() }
            .getOrThrow()
        OpenedPdf(descriptor, document)
    }.onFailure { error -> Log.w(LogTag, "Couldn't open the PDF for its text", error) }.getOrNull()

    private fun closeDocument() {
        val open = opened ?: return
        opened = null
        runCatching { open.document.close() }.onFailure { error -> Log.w(LogTag, "Couldn't close the PDF", error) }
        open.descriptor.closeQuietly()
    }

    private class OpenedPdf(val descriptor: ParcelFileDescriptor, val document: PdfDocument)

    private companion object {
        const val LogTag = "PdfText"
        val PdfiumLibraries = listOf("pdfium", "pdfiumandroid")
        const val ThreadName = "katana-pdf-text"

        /** A few pages either side of the one being read: a PDF page's text and boxes are tens of kilobytes. */
        const val CachedPages = 6
        const val LoadFactor = 0.75f
    }
}

/** Page [pageIndex]'s characters and their boxes, as fractions of the page as PdfRenderer draws it. */
private fun readPage(document: PdfDocument, pageIndex: Int): PdfPageText? {
    if (pageIndex !in 0 until document.getPageCount()) return null
    return document.openPage(pageIndex).use { page ->
        page.openTextPage().use { textPage -> readText(page, textPage, pageIndex) }
    }
}

private fun readText(page: PdfPage, textPage: PdfTextPage, pageIndex: Int): PdfPageText? {
    val count = textPage.textPageCountChars()
    val width = page.getPageWidthPoint()
    val height = page.getPageHeightPoint()
    if (count <= 0 || width <= 0 || height <= 0) return null
    val read: String? = textPage.textPageGetText(0, count)
    val text = read?.takeIf { it.length == count } ?: String(CharArray(count) { index -> textPage.textPageGetUnicode(index) })
    val mapping = PdfPageMapping.of { x, y ->
        val point = page.mapPageCoordsToDevice(0, 0, PdfPageMapping.DeviceSize, PdfPageMapping.DeviceSize, 0, x, y)
        point.x to point.y
    }
    val boxes = FloatArray(count * PdfPageText.BoxSize)
    val source = FloatArray(PdfPageText.BoxSize)
    for (index in 0 until count) {
        val box: RectF? = textPage.textPageGetCharBox(index)
        // PDFium's box is in page space: left, top (the higher y), right, bottom. Made-up characters have none.
        source[PdfPageText.Left] = box?.left ?: 0f
        source[PdfPageText.Top] = box?.top ?: 0f
        source[PdfPageText.Right] = box?.right ?: 0f
        source[PdfPageText.Bottom] = box?.bottom ?: 0f
        mapping.writeBox(source, into = boxes, at = index * PdfPageText.BoxSize)
    }
    return PdfPageText(pageIndex = pageIndex, text = text, boxes = boxes, aspect = height.toFloat() / width)
}

private fun ParcelFileDescriptor.closeQuietly() {
    runCatching { close() }
}
