package com.example.unifiedreader.feature.reader.pdf

import android.graphics.Bitmap
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf

/** A drawn page an item on screen holds; [image] is what it draws. */
internal class PdfHeldPage(
    val key: PdfPageImageKey,
    bitmap: Bitmap,
) {
    val image: ImageBitmap = bitmap.asImageBitmap()
}

/**
 * The scrolling view's pages: drawn by [source] when an item asks, kept in a cache of [budgetBytes], and
 * each page's shape as soon as it is known ([aspects]). Called from the main thread.
 */
@Stable
internal class PdfScrollPageImages(
    private val source: PdfScrollPageSource,
    budgetBytes: Long,
) {
    private val cache = PdfPageImageCache<Bitmap>(
        budgetBytes = budgetBytes,
        sizeOf = { bitmap -> bitmap.allocationByteCount.toLong() },
        recycle = { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() },
    )

    /** Width over height for each page whose shape is known. */
    val aspects = mutableStateMapOf<Int, Float>()

    /** The shape a page is laid out with until its own is known: the first one learned. */
    var typicalAspect by mutableFloatStateOf(PdfDefaultPageAspect)
        private set

    private var typicalLearned = false

    /** Whether a page has been drawn yet, and whether one couldn't be: what the opening screen waits for. */
    var drawnOnce by mutableStateOf(false)
        private set

    var failedOnce by mutableStateOf(false)
        private set

    /** The most pixels a page is drawn with when zoomed in. */
    val maxPagePixels: Long = pdfMaxPagePixels(budgetBytes)

    /** Page [pageIndex] drawn [widthPx] wide, held for the caller, drawing it first when it isn't cached. */
    suspend fun load(pageIndex: Int, widthPx: Int): PdfHeldPage? {
        val key = PdfPageImageKey(pageIndex, widthPx)
        val shown = cache.acquire(key) ?: draw(pageIndex, widthPx)?.let { bitmap -> cache.put(key, bitmap) }
        return shown?.let { bitmap -> PdfHeldPage(key, bitmap) }
    }

    private suspend fun draw(pageIndex: Int, widthPx: Int): Bitmap? {
        val bitmap = source.render(pageIndex, widthPx)
        if (bitmap == null) {
            failedOnce = true
        } else {
            learn(pageIndex, bitmap)
            drawnOnce = true
        }
        return bitmap
    }

    /** The sharpest drawing of [pageIndex] already cached, held, to show while the one asked for is drawn. */
    fun widest(pageIndex: Int): PdfHeldPage? = cache.acquireWidest(pageIndex)?.let { (key, bitmap) -> PdfHeldPage(key, bitmap) }

    fun release(page: PdfHeldPage) = cache.release(page.key)

    /** Draws [pageIndex] into the cache without holding it, for when the reader gets there. */
    suspend fun prefetch(pageIndex: Int, widthPx: Int) {
        val key = PdfPageImageKey(pageIndex, widthPx)
        if (cache.contains(key)) return
        val bitmap = source.render(pageIndex, widthPx) ?: return
        learn(pageIndex, bitmap)
        cache.put(key, bitmap, hold = false)
    }

    /** Reads [pageIndex]'s shape without drawing it, so the page is laid out at its size before it shows. */
    suspend fun learnAspect(pageIndex: Int) {
        if (pageIndex in aspects) return
        source.pageAspect(pageIndex)?.let { aspect -> record(pageIndex, aspect) }
    }

    fun close() {
        cache.close()
        source.close()
    }

    private fun learn(pageIndex: Int, bitmap: Bitmap) {
        if (pageIndex !in aspects && bitmap.height > 0) record(pageIndex, bitmap.width.toFloat() / bitmap.height)
    }

    private fun record(pageIndex: Int, aspect: Float) {
        aspects[pageIndex] = aspect
        if (!typicalLearned) {
            typicalLearned = true
            typicalAspect = aspect
        }
    }
}

/** What one page item shows; lets its drawing go when the item leaves the screen. */
internal class PdfShownPage(
    private val images: PdfScrollPageImages,
) : RememberObserver {
    var current by mutableStateOf<PdfHeldPage?>(null)
        private set

    fun show(page: PdfHeldPage?) {
        if (page == null) return
        val previous = current
        current = page
        if (previous != null && previous !== page) images.release(previous)
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = clear()

    override fun onAbandoned() = clear()

    private fun clear() {
        current?.let(images::release)
        current = null
    }
}
