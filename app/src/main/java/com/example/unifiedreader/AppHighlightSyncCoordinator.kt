package com.example.unifiedreader

import com.example.unifiedreader.core.common.ApplicationScope
import com.example.unifiedreader.core.database.entity.WaitingHighlightKey
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceConnectivityState
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.data.repository.ReadableItemRepository
import com.example.unifiedreader.feature.annotations.HighlightSyncStore
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
 * When highlights sync with Grimmory ([GrimmoryHighlightSync] says how), so a change made offline
 * goes up later and is never lost:
 * - a change made here goes a few seconds after the last one (more often follow), when Grimmory is
 *   online, and as soon as it is online again;
 * - a book opened brings its highlights down (and sends its waiting changes);
 * - a library sync brings every book's down;
 * - the retry timer (two minutes, then longer, as for reading progress) tries what still waits.
 */
@Singleton
class AppHighlightSyncCoordinator @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val sync: GrimmoryHighlightSync,
    private val store: HighlightSyncStore,
    private val catalog: ReadableItemRepository,
    private val readerWork: ReaderWorkCoordinator,
    private val connectivity: RemoteSourceConnectivityPolicy,
) : HighlightSyncTriggers {
    private val waiting = MutableStateFlow<List<WaitingHighlightKey>>(emptyList())
    private val started = AtomicBoolean(false)

    @OptIn(FlowPreview::class)
    override fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { store.observeWaiting().collect { keys -> waiting.value = keys } }
        scope.launch {
            combine(waiting, connectivity.reachabilityFlow) { keys, reachability ->
                keys to (reachability[SourceType.GRIMMORY] == SourceConnectivityState.Online)
            }
                .distinctUntilChanged()
                .debounce(CHANGE_DEBOUNCE_MILLIS)
                .collect { (keys, online) -> if (online && keys.isNotEmpty()) syncWaitingBooks() }
        }
        scope.launch {
            var openItemIds = emptySet<String>()
            readerWork.activeReaderItemIds.collect { itemIds ->
                val opened = itemIds - openItemIds
                openItemIds = itemIds
                if (opened.isEmpty() || connectivity.isUnavailable(SourceType.GRIMMORY)) return@collect
                catalog.getItems()
                    .filter { item -> item.id in opened && item.syncsHighlightsWithGrimmory() }
                    .takeIf(List<ReadableItem>::isNotEmpty)
                    ?.let { books -> sync.syncBooks(books) }
            }
        }
        scope.launchPendingSyncRetries(hasWork = ::hasWaitingUploads, retry = ::retry)
    }

    override fun hasWaitingUploads(): Boolean = waitingBooks().isNotEmpty()

    override fun retry() {
        scope.launch { syncWaitingBooks() }
    }

    override fun afterLibrarySync() {
        scope.launch { sync.syncLibrary(catalog.getItems(), store.booksOnServer()) }
    }

    private suspend fun syncWaitingBooks() {
        val books = waitingBooks()
        if (books.isNotEmpty()) sync.syncBooks(books)
    }

    private fun waitingBooks(): List<ReadableItem> {
        val itemIds = waiting.value.mapTo(mutableSetOf(), WaitingHighlightKey::itemId)
        if (itemIds.isEmpty()) return emptyList()
        return catalog.getItems().filter { item -> item.id in itemIds && item.syncsHighlightsWithGrimmory() }
    }

    private companion object {
        const val CHANGE_DEBOUNCE_MILLIS = 4_000L
    }
}
