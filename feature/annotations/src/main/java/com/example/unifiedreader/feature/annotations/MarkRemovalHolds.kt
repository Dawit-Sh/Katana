package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.entity.HIGHLIGHT_WAITING
import com.example.unifiedreader.core.database.entity.HighlightRowPurge
import com.example.unifiedreader.core.database.entity.HighlightRowWrite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One mark kept here though its server no longer has it, as it was when the sync read it. */
data class HeldMark(val id: String, val expectedUpdatedAt: Long)

/**
 * A book's marks its server no longer has, in numbers that look like the server lost them (a restored
 * backup, a wiped database) rather than someone removing a few: kept on this device, nothing changed on
 * the server, until the reader says "Remove here" or "Put back".
 */
data class MarkRemovalHold(
    val itemId: String,
    val bookTitle: String,
    val highlights: List<HeldMark> = emptyList(),
    val bookmarks: List<HeldMark> = emptyList(),
) {
    val count: Int get() = highlights.size + bookmarks.size
}

/** Highlights or bookmarks: each kind is weighed on its own. */
enum class HeldMarkKind { Highlight, Bookmark }

/**
 * Whether a sync removing [removed] of a book's [kept] marks of one kind (those not removed here, the
 * [removed] among them) looks like the server lost them: at least [MASS_REMOVAL_MIN_MARKS], and more than
 * half. One or two removed on the web still go here at once.
 */
fun isMassRemoval(removed: Int, kept: Int): Boolean = removed >= MASS_REMOVAL_MIN_MARKS && removed * 2 > kept

const val MASS_REMOVAL_MIN_MARKS = 5

/**
 * The books whose marks wait for an answer. Kept in memory: every sync of the book works the hold out
 * again from the server and this device (the marks are never removed meanwhile), so a restart or a
 * dismissed question loses nothing, and the question comes again.
 */
object MarkRemovalHolds {
    private val holdsByItemId = MutableStateFlow<Map<String, MarkRemovalHold>>(emptyMap())
    val holds: StateFlow<Map<String, MarkRemovalHold>> = holdsByItemId.asStateFlow()

    fun current(itemId: String): MarkRemovalHold? = holdsByItemId.value[itemId]

    /** [book]'s held marks of [kind] are now [marks]; none ends that kind's hold. */
    fun hold(book: AnnotatedBook, kind: HeldMarkKind, marks: List<HeldMark>) = holdsByItemId.update { holds ->
        val previous = holds[book.itemId] ?: MarkRemovalHold(book.itemId, book.title)
        val next = when (kind) {
            HeldMarkKind.Highlight -> previous.copy(highlights = marks)
            HeldMarkKind.Bookmark -> previous.copy(bookmarks = marks)
        }
        if (next.count == 0) holds - book.itemId else holds + (book.itemId to next)
    }

    fun withdraw(itemId: String) = holdsByItemId.update { holds -> holds - itemId }

    /** For tests. */
    fun clear() = holdsByItemId.update { emptyMap() }
}

/**
 * Of a book's sync plan's [purges], those that remove marks only because the server no longer has them
 * (marks [keptIds], not removed here) are held instead when there are many ([isMassRemoval]); the rest of
 * the plan goes ahead. Returns the purges to make now.
 */
internal fun <Purge> holdingMassRemoval(
    book: AnnotatedBook,
    kind: HeldMarkKind,
    keptIds: Set<String>,
    purges: List<Purge>,
    heldAs: (Purge) -> HeldMark,
): List<Purge> {
    val lost = purges.filter { purge -> heldAs(purge).id in keptIds }
    return if (isMassRemoval(lost.size, keptIds.size)) {
        MarkRemovalHolds.hold(book, kind, lost.map(heldAs))
        purges - lost.toSet()
    } else {
        MarkRemovalHolds.hold(book, kind, emptyList())
        purges
    }
}

/**
 * The reader's answer to [hold]: "Remove here" removes the marks here too; "Put back" makes them new
 * again here, so the next sync puts them back on the server. Either way only marks unchanged since the
 * sync held them.
 */
suspend fun settleMarkRemovalHold(
    hold: MarkRemovalHold,
    removeHere: Boolean,
    highlights: HighlightSyncStore,
    bookmarks: BookmarkSyncStore,
) {
    MarkRemovalHolds.withdraw(hold.itemId)
    if (removeHere) {
        val highlightPurges = hold.highlights.map { mark -> HighlightRowPurge(mark.id, mark.expectedUpdatedAt) }
        val bookmarkPurges = hold.bookmarks.map { mark -> BookmarkRowPurge(mark.id, mark.expectedUpdatedAt) }
        if (highlightPurges.isNotEmpty()) highlights.apply(emptyList(), highlightPurges)
        if (bookmarkPurges.isNotEmpty()) bookmarks.apply(emptyList(), bookmarkPurges)
        return
    }
    val heldHighlights = hold.highlights.associateBy(HeldMark::id)
    val highlightWrites = highlights.rowsOf(hold.itemId).mapNotNull { row ->
        heldHighlights[row.id]?.let { mark ->
            HighlightRowWrite(row.copy(remoteId = null, syncState = HIGHLIGHT_WAITING), expectedUpdatedAt = mark.expectedUpdatedAt)
        }
    }
    val heldBookmarks = hold.bookmarks.associateBy(HeldMark::id)
    val bookmarkWrites = bookmarks.rowsOf(hold.itemId).mapNotNull { row ->
        heldBookmarks[row.id]?.let { mark ->
            BookmarkRowWrite(row.copy(remoteId = null, syncState = HIGHLIGHT_WAITING), expectedUpdatedAt = mark.expectedUpdatedAt)
        }
    }
    if (highlightWrites.isNotEmpty()) highlights.apply(highlightWrites, emptyList())
    if (bookmarkWrites.isNotEmpty()) bookmarks.apply(bookmarkWrites, emptyList())
}
