package com.example.unifiedreader.feature.reader.pdf

/** A drawn page in the cache: page [pageIndex] drawn [widthPx] wide. */
internal data class PdfPageImageKey(
    val pageIndex: Int,
    val widthPx: Int,
)

/**
 * The drawn pages of a scrolling book, kept within [budgetBytes]. A page on screen holds its image
 * ([acquire], [put], then [release] when it leaves), and a held image is never recycled; the rest go,
 * least recently used first, once the cache is over its budget. Used from the main thread only.
 *
 * Generic over the image so it can be tested without bitmaps: [sizeOf] gives an image's bytes and
 * [recycle] frees it.
 */
internal class PdfPageImageCache<T : Any>(
    private val budgetBytes: Long,
    private val sizeOf: (T) -> Long,
    private val recycle: (T) -> Unit,
) {
    private class Entry<T>(val image: T, val bytes: Long) {
        var holds: Int = 0
    }

    // Access order: the first entry is the least recently used.
    private val entries = LinkedHashMap<PdfPageImageKey, Entry<T>>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var closed = false

    /** The bytes held by every cached image, held or not. */
    var totalBytes: Long = 0L
        private set

    val size: Int
        get() = entries.size

    /** The image for [key], now held by the caller, or null when it isn't drawn yet. */
    fun acquire(key: PdfPageImageKey): T? {
        val entry = entries[key] ?: return null
        entry.holds++
        return entry.image
    }

    /** The widest image drawn for [pageIndex], held, for showing while a sharper one is drawn. */
    fun acquireWidest(pageIndex: Int): Pair<PdfPageImageKey, T>? = entries.keys
        .filter { it.pageIndex == pageIndex }
        .maxByOrNull { it.widthPx }
        ?.let { key -> acquire(key)?.let { image -> key to image } }

    fun contains(key: PdfPageImageKey): Boolean = entries.containsKey(key)

    /**
     * Adds a freshly drawn [image] for [key] and returns the image to show, held: [image] itself, or the
     * one already cached for [key] (then [image] is recycled). After [close], [image] is recycled and
     * null comes back.
     */
    fun put(key: PdfPageImageKey, image: T, hold: Boolean = true): T? {
        val existing = entries[key]
        val shown = when {
            closed -> null
            existing != null -> existing.image.also { if (hold) existing.holds++ }
            else -> {
                val entry = Entry(image, sizeOf(image))
                if (hold) entry.holds = 1
                entries[key] = entry
                totalBytes += entry.bytes
                image
            }
        }
        if (shown !== image) recycle(image) else trim()
        return shown
    }

    /** Lets go of an image [acquire] or [put] handed out; it stays cached until the budget needs the room. */
    fun release(key: PdfPageImageKey) {
        val entry = entries[key] ?: return
        if (entry.holds > 0) entry.holds--
        trim()
    }

    /** Recycles everything not held and stops taking new images; held ones go when released. */
    fun close() {
        closed = true
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            if (entry.holds == 0) {
                iterator.remove()
                totalBytes -= entry.bytes
                recycle(entry.image)
            }
        }
    }

    private fun trim() {
        val limit = if (closed) 0L else budgetBytes
        if (totalBytes <= limit) return
        val iterator = entries.entries.iterator()
        while (totalBytes > limit && iterator.hasNext()) {
            val (_, entry) = iterator.next()
            if (entry.holds == 0) {
                iterator.remove()
                totalBytes -= entry.bytes
                recycle(entry.image)
            }
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
