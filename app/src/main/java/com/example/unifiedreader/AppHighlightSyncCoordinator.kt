package com.example.unifiedreader

import com.example.unifiedreader.core.common.ApplicationScope
import com.example.unifiedreader.core.database.entity.WaitingBookmarkKey
import com.example.unifiedreader.core.database.entity.WaitingHighlightKey
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceConnectivityState
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.data.repository.ReadableItemRepository
import com.example.unifiedreader.feature.annotations.BookmarkSyncStore
import com.example.unifiedreader.feature.annotations.HighlightSyncStore
import com.example.unifiedreader.feature.annotations.MarkRemovalHolds
import com.example.unifiedreader.feature.annotations.settleMarkRemovalHold
import com.example.unifiedreader.feature.reader.core.ReaderWorkCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What the app shell asks of highlight sync. */
interface HighlightSyncTriggers {
    /** Starts following highlight changes, opened books and the retry timer; once, after the app starts. */
    fun start() = Unit

    /** Whether highlight changes wait to go to a server. */
    fun hasWaitingUploads(): Boolean = false

    /** Tries the waiting changes now (Retry pressed). */
    fun retry() = Unit

    /** A library sync finished: every book's highlights come down, and what waits goes up. */
    fun afterLibrarySync() = Unit

    data object None : HighlightSyncTriggers
}

/**
 * When highlights and bookmarks sync with Grimmory ([GrimmoryHighlightSync] and [GrimmoryBookmarkSync]
 * say how), so a change made offline goes up later and is never lost. Grimmory's books sync, and so do
 * files here that are copies of them ([GrimmoryLocalCopies], checked again before each sync):
 * - a change made here goes a few seconds after the last one (more often follow), when Grimmory is
 *   online, and as soon as it is online again;
 * - a book opened brings its marks down (and sends its waiting changes);
 * - a library sync brings every book's down;
 * - the retry timer (two minutes, then longer, as for reading progress) tries what still waits.
 */
@Singleton
class AppHighlightSyncCoordinator @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val sync: GrimmoryHighlightSync,
    private val store: HighlightSyncStore,
    private val bookmarkSync: GrimmoryBookmarkSync,
    private val bookmarkStore: BookmarkSyncStore,
    private val catalog: ReadableItemRepository,
    private val readerWork: ReaderWorkCoordinator,
    private val connectivity: RemoteSourceConnectivityPolicy,
    private val localCopies: GrimmoryLocalCopies,
) : HighlightSyncTriggers {
    private val waiting = MutableStateFlow<List<WaitingHighlightKey>>(emptyList())
    private val waitingBookmarks = MutableStateFlow<List<WaitingBookmarkKey>>(emptyList())
    private val started = AtomicBoolean(false)

    @OptIn(FlowPreview::class)
    override fun start() {
        if (!started.compareAndSet(false, true)) return
        localCopies.start()
        scope.launch { store.observeWaiting().collect { keys -> waiting.value = keys } }
        scope.launch { bookmarkStore.observeWaiting().collect { keys -> waitingBookmarks.value = keys } }
        scope.launch {
            combine(waiting, waitingBookmarks, connectivity.reachabilityFlow) { keys, bookmarkKeys, reachability ->
                Triple(keys, bookmarkKeys, reachability[SourceType.GRIMMORY] == SourceConnectivityState.Online)
            }
                .distinctUntilChanged()
                .debounce(CHANGE_DEBOUNCE_MILLIS)
                .collect { (keys, bookmarkKeys, online) ->
                    if (online && (keys.isNotEmpty() || bookmarkKeys.isNotEmpty())) syncWaitingBooks()
                }
        }
        scope.launch {
            var openItemIds = emptySet<String>()
            readerWork.activeReaderItemIds.collect { itemIds ->
                val opened = itemIds - openItemIds
                openItemIds = itemIds
                if (opened.isEmpty() || connectivity.isUnavailable(SourceType.GRIMMORY)) return@collect
                val books = marksBooks(itemsWithIds(opened, localFile = { true }))
                if (books.isNotEmpty()) {
                    sync.syncMarksBooks(books)
                    bookmarkSync.syncMarksBooks(books)
                }
            }
        }
        scope.launchPendingSyncRetries(hasWork = ::hasWaitingUploads, retry = ::retry)
    }

    override fun hasWaitingUploads(): Boolean =
        waitingItems(waiting.value.mapTo(mutableSetOf(), WaitingHighlightKey::itemId)).isNotEmpty() ||
            waitingItems(waitingBookmarks.value.mapTo(mutableSetOf(), WaitingBookmarkKey::itemId)).isNotEmpty()

    override fun retry() {
        scope.launch { syncWaitingBooks() }
    }

    override fun afterLibrarySync() {
        scope.launch {
            val items = catalog.getItems()
            val highlightBooks = store.booksOnServer()
            val bookmarkBooks = bookmarkStore.booksOnServer()
            val candidates = localCopies.linkedItemIds() + highlightBooks + bookmarkBooks
            val copies = localCopies.copiesAmong(localCopies.localItems().filter { it.id in candidates })
            sync.syncLibrary(items, highlightBooks, copies)
            bookmarkSync.syncLibrary(items, bookmarkBooks, copies)
        }
    }

    /**
     * The reader's answer to a book's marks held because Grimmory lost many at once ([MarkRemovalHolds]):
     * remove them here too, or put them back on Grimmory (they go up as new at once).
     */
    fun answerRemovalHold(itemId: String, removeHere: Boolean) {
        val hold = MarkRemovalHolds.current(itemId) ?: return
        scope.launch {
            settleMarkRemovalHold(hold, removeHere, highlights = store, bookmarks = bookmarkStore)
            if (!removeHere) syncWaitingBooks()
        }
    }

    private suspend fun syncWaitingBooks() {
        val highlightIds = waiting.value.mapTo(mutableSetOf(), WaitingHighlightKey::itemId)
        val bookmarkIds = waitingBookmarks.value.mapTo(mutableSetOf(), WaitingBookmarkKey::itemId)
        val books = marksBooks(waitingItems(highlightIds + bookmarkIds))
        books.filter { it.item.id in highlightIds }.takeIf(List<GrimmoryMarksBook>::isNotEmpty)?.let { sync.syncMarksBooks(it) }
        books.filter { it.item.id in bookmarkIds }.takeIf(List<GrimmoryMarksBook>::isNotEmpty)?.let { bookmarkSync.syncMarksBooks(it) }
    }

    // Grimmory's books as they are; files here only when a check now finds them still copies of one.
    private suspend fun marksBooks(items: List<ReadableItem>): List<GrimmoryMarksBook> =
        items.mapNotNull(ReadableItem::asGrimmoryMarksBook) +
            localCopies.copiesAmong(items.filter { it.sourceType == SourceType.LOCAL })

    // The books among [itemIds] whose marks may sync: Grimmory's, and files here linked at their last check.
    private fun waitingItems(itemIds: Set<String>): List<ReadableItem> = itemsWithIds(itemIds, localFile = localCopies::isLinked)

    // Grimmory's books among [itemIds] whose marks sync, and the files here among them that [localFile] lets in.
    private fun itemsWithIds(itemIds: Set<String>, localFile: (ReadableItem) -> Boolean): List<ReadableItem> {
        if (itemIds.isEmpty()) return emptyList()
        val books = catalog.getItems().filter { item -> item.id in itemIds && item.syncsBookmarksWithGrimmory() }
        val files = localCopies.localItems().filter { item ->
            item.id in itemIds && item.format.sharesBookmarksWithGrimmory() && localFile(item)
        }
        return books + files
    }

    private companion object {
        const val CHANGE_DEBOUNCE_MILLIS = 4_000L
    }
}
