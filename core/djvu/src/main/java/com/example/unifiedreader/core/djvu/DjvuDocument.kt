package com.example.unifiedreader.core.djvu

import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * An open DjVu document, read with DjVuLibre. Open one with [open] and [close] it when done: it holds
 * native memory and decoder threads.
 *
 * Calls on one document are thread-safe; they run one at a time. Once closed, every call except [close]
 * throws [IllegalStateException].
 */
class DjvuDocument private constructor(
    private var handle: Long,
    /** The number of pages, at least 1. */
    val pageCount: Int,
) : Closeable {
    private val lock = Any()
    private val pageSizes = arrayOfNulls<DjvuPageSize>(pageCount)

    /**
     * The size of page [pageIndex] (from 0) in pixels at the page's own resolution, with the page's
     * rotation applied, so a page stored sideways reports its upright size.
     */
    fun pageSize(pageIndex: Int): DjvuPageSize = synchronized(lock) {
        val live = liveHandle()
        checkPageIndex(pageIndex)
        pageSizes[pageIndex] ?: readPageSize(live, pageIndex).also { pageSizes[pageIndex] = it }
    }

    /**
     * Draws page [pageIndex] (from 0) over the whole of [bitmap], scaled to the bitmap's size. The bitmap must
     * be mutable and [Bitmap.Config.ARGB_8888]; every pixel comes out opaque. Throws [DjvuException] when the
     * page can't be decoded.
     */
    fun renderPage(pageIndex: Int, bitmap: Bitmap) {
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "DjVu pages render into ARGB_8888 bitmaps only" }
        require(bitmap.isMutable && !bitmap.isRecycled) { "The bitmap must be mutable and not recycled" }
        synchronized(lock) {
            val live = liveHandle()
            checkPageIndex(pageIndex)
            DjvuNative.nativeRenderPage(live, pageIndex, bitmap)
        }
    }

    /**
     * The words of page [pageIndex] (from 0) in the book's hidden text layer (the OCR text of most scanned
     * books), or null when the page has none or it can't be read.
     */
    fun pageText(pageIndex: Int): DjvuPageText? {
        val raw = synchronized(lock) {
            val live = liveHandle()
            checkPageIndex(pageIndex)
            DjvuNative.nativePageText(live, pageIndex)
        }
        return raw?.let(::djvuPageTextOf)
    }

    /** Frees the document. Calling it again does nothing. */
    override fun close() {
        synchronized(lock) {
            if (handle != CLOSED) {
                DjvuNative.nativeClose(handle)
                handle = CLOSED
            }
        }
    }

    private fun liveHandle(): Long {
        check(handle != CLOSED) { "This DjVu document is closed" }
        return handle
    }

    private fun checkPageIndex(pageIndex: Int) {
        if (pageIndex !in 0 until pageCount) {
            throw IndexOutOfBoundsException("Page $pageIndex of $pageCount")
        }
    }

    private fun readPageSize(live: Long, pageIndex: Int): DjvuPageSize {
        val (width, height, dpi) = DjvuNative.nativePageInfo(live, pageIndex)
        return DjvuPageSize(width = width, height = height, dpi = dpi)
    }

    /** Opening documents. */
    companion object {
        private const val CLOSED = 0L

        /**
         * Opens the DjVu document in [file] (a single page or a bundled or indirect multi-page document) and
         * reads its page directory. Throws [DjvuException] with a readable message when the file is missing,
         * isn't DjVu, or can't be decoded.
         */
        fun open(file: File): DjvuDocument {
            checkIsDjvuFile(file)
            DjvuNative.ensureLoaded()
            val handle = DjvuNative.nativeOpen(file.path.toByteArray(Charsets.UTF_8))
            return DjvuDocument(handle, DjvuNative.nativePageCount(handle))
        }

        private fun checkIsDjvuFile(file: File) {
            if (!file.isFile) throw DjvuException("There is no file at ${file.path}")
            if (!isDjvuSignature(readHeader(file))) throw DjvuException("${file.name} is not a DjVu file")
        }

        private fun readHeader(file: File): ByteArray = try {
            file.inputStream().use { readUpTo(it, DJVU_SIGNATURE_SIZE) }
        } catch (e: IOException) {
            throw DjvuException("Can't read ${file.name}: ${e.message}", e)
        }

        private fun readUpTo(input: InputStream, size: Int): ByteArray {
            val bytes = ByteArray(size)
            var filled = 0
            while (filled < size) {
                val read = input.read(bytes, filled, size - filled)
                if (read < 0) break
                filled += read
            }
            return bytes.copyOf(filled)
        }
    }
}

/** A DjVu page's size in pixels at its own resolution of [dpi] dots per inch. */
data class DjvuPageSize(val width: Int, val height: Int, val dpi: Int)

/** A DjVu file that can't be opened or a page that can't be decoded; the message says why. */
class DjvuException(message: String, cause: Throwable?) : IOException(message, cause) {
    constructor(message: String) : this(message, null)
}
