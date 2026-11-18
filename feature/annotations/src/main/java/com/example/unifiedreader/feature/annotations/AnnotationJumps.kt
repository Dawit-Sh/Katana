package com.example.unifiedreader.feature.annotations

import java.util.concurrent.atomic.AtomicReference

/**
 * A place to open a book at, handed from the Bookmarks page to the reader: the page sets it and
 * opens the book, and the reader takes it once the book shows (and goes there), so a bookmark or a
 * highlight tapped outside the book lands on its page. One at a time; a newer one replaces it.
 */
class AnnotationJumps {
    private val pending = AtomicReference<Pending?>(null)

    fun request(itemId: String, locatorJson: String) {
        pending.set(Pending(itemId, locatorJson))
    }

    /** The place waiting for [itemId], taken so it is used once; null when there is none. */
    fun take(itemId: String): String? {
        val waiting = pending.get()?.takeIf { it.itemId == itemId } ?: return null
        return waiting.locatorJson.takeIf { pending.compareAndSet(waiting, null) }
    }

    /** Drops a place no book took (the book failed to open, or another was opened). */
    fun clear() {
        pending.set(null)
    }

    private data class Pending(val itemId: String, val locatorJson: String)
}
