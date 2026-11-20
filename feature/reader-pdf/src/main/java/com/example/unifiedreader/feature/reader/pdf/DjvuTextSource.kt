package com.example.unifiedreader.feature.reader.pdf

import android.util.Log
import com.example.unifiedreader.core.djvu.DjvuPageText
import com.example.unifiedreader.feature.annotations.PageTextRect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The words on a DjVu book's pages, from its hidden text layer (the OCR text most scanned DjVu books carry), read
 * through the document the reader already has open. A page's text is read the first time it is asked for, off
 * the main thread, one page at a time, and kept while it is one of the last few. A page or a book without the
 * layer gives no text, as a scanned PDF does; nothing here ever throws.
 */
internal class DjvuTextSource(
    /** Reads a page's text from the open document (blocking); null when the page has none or the book isn't open. */
    private val read: (pageIndex: Int) -> DjvuPageText?,
) : PageTextSource {
    private val lock = Any()

    @Volatile
    private var closed = false

    // One page read at a time: the open document runs its calls one by one anyway.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val thread: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val pages = object : LinkedHashMap<Int, PdfPageText>(CachedPages, LoadFactor, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPageText>): Boolean = size > CachedPages
    }

    override suspend fun page(pageIndex: Int): PdfPageText? =
        cached(pageIndex) ?: if (closed) null else withContext(thread) { readKept(pageIndex) }

    override fun cached(pageIndex: Int): PdfPageText? = synchronized(lock) { pages[pageIndex] }

    override fun close() {
        synchronized(lock) {
            closed = true
            pages.clear()
        }
    }

    private fun readKept(pageIndex: Int): PdfPageText? {
        val read = cached(pageIndex) ?: readQuietly(pageIndex)
        if (read != null) synchronized(lock) { if (!closed) pages[pageIndex] = read }
        return read
    }

    private fun readQuietly(pageIndex: Int): PdfPageText? = if (closed) {
        null
    } else {
        runCatching { read(pageIndex)?.let { text -> djvuPdfPageText(pageIndex, text) } }
            .onFailure { error -> warn("Couldn't read the text of page ${pageIndex + 1}", error) }
            .getOrNull()
    }

    // Logging never stops a read (on the JVM in tests Log isn't there).
    private fun warn(message: String, error: Throwable) {
        runCatching { Log.w(LogTag, message, error) }
    }

    private companion object {
        const val LogTag = "DjvuText"
        const val CachedPages = 6
        const val LoadFactor = 0.75f
    }
}

/**
 * A DjVu page's words as page text: words on a line joined by spaces, lines by line breaks (neither has a box),
 * each letter a share of its word's box (DjVu keeps boxes per word), turned the way the page is shown.
 */
internal fun djvuPdfPageText(pageIndex: Int, page: DjvuPageText): PdfPageText? {
    if (page.width <= 0 || page.height <= 0 || page.words.isEmpty()) return null
    // DjVu turns a page anticlockwise; the page text counts clockwise.
    val turn = quarterTurn(-page.rotation)
    val text = StringBuilder()
    val boxes = ArrayList<Float>()
    var line = page.words.first().line
    page.words.forEach { word ->
        if (text.isNotEmpty()) {
            text.append(if (word.line != line) '\n' else ' ')
            repeat(PdfPageText.BoxSize) { boxes += Float.NaN }
        }
        line = word.line
        // The word's box as fractions of the stored page from its top left (DjVu counts from the bottom left).
        val box = PageTextRect(
            left = word.left.toFloat() / page.width,
            top = 1f - word.top.toFloat() / page.height,
            right = word.right.toFloat() / page.width,
            bottom = 1f - word.bottom.toFloat() / page.height,
        )
        word.text.forEachIndexed { index, char ->
            text.append(char)
            boxes += if (char.isWhitespace()) NoBox else letterBox(box, index, word.text.length, turn)
        }
    }
    val aspect = if (turn % 2 == 0) page.height.toFloat() / page.width else page.width.toFloat() / page.height
    return PdfPageText(pageIndex = pageIndex, text = text.toString(), boxes = boxes.toFloatArray(), aspect = aspect, pageTurn = turn)
}

/** Letter [index] of [count]'s share of its word's [box], on the page as shown. */
private fun letterBox(box: PageTextRect, index: Int, count: Int, turn: Int): List<Float> {
    val width = (box.right - box.left) / count
    val share = PageTextRect(box.left + width * index, box.top, box.left + width * (index + 1), box.bottom)
    val shown = drawn(share, turn)
    return listOf(shown.left, shown.top, shown.right, shown.bottom)
}

private val NoBox = List(PdfPageText.BoxSize) { Float.NaN }
