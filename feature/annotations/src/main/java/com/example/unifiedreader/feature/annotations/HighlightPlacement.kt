package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.dao.ReaderHighlightPlacementDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A highlight its reader has to place: [locatorJson] is empty for one from the server, which has
 * [serverPlace] (its EPUB CFI) instead; a new one from this device has its locator and no server place yet.
 */
data class HighlightToPlace(
    val id: String,
    val locatorJson: String,
    val serverPlace: String?,
    val text: String,
    val chapterTitle: String?,
)

/**
 * Where an open book's reader works out its highlights' places for their server sync: the
 * server's way (an EPUB CFI) for a new one made here, so it can be sent, and in this device's copy
 * of the book for one that came from the server, so it shows on its page.
 */
interface HighlightPlacementStore {
    /** The book's highlights to place; each change is a new list. */
    fun highlightsToPlace(itemId: String): Flow<List<HighlightToPlace>>

    /** The highlight's place the server's way; it can go to the server now. */
    suspend fun setServerPlace(highlightId: String, cfi: String)

    /** A new highlight whose place can't be put the server's way: it stays on this device. */
    suspend fun keepOnDevice(highlightId: String)

    /** Where a highlight from the server is in this device's copy of the book. */
    suspend fun place(highlightId: String, place: AnnotationPlace)

    /** The same for the book's bookmarks (nothing to place for a book whose marks don't sync). */
    val bookmarks: BookmarkPlacementStore
        get() = BookmarkPlacementStore.None

    /** For a book whose highlights don't sync: nothing to place. */
    data object None : HighlightPlacementStore {
        override fun highlightsToPlace(itemId: String): Flow<List<HighlightToPlace>> = flowOf(emptyList())

        override suspend fun setServerPlace(highlightId: String, cfi: String) = Unit

        override suspend fun keepOnDevice(highlightId: String) = Unit

        override suspend fun place(highlightId: String, place: AnnotationPlace) = Unit
    }
}

class RoomHighlightPlacementStore(
    private val dao: ReaderHighlightPlacementDao,
    override val bookmarks: BookmarkPlacementStore = BookmarkPlacementStore.None,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HighlightPlacementStore {
    override fun highlightsToPlace(itemId: String): Flow<List<HighlightToPlace>> =
        dao.observeHighlightsToPlace(itemId)
            .map { rows -> rows.map { row -> HighlightToPlace(row.id, row.locatorJson, row.remoteCfi, row.text, row.chapterTitle) } }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override suspend fun setServerPlace(highlightId: String, cfi: String) = io { dao.setHighlightCfi(highlightId, cfi) }

    override suspend fun keepOnDevice(highlightId: String) = io { dao.keepHighlightOnDevice(highlightId) }

    override suspend fun place(highlightId: String, place: AnnotationPlace) = io {
        dao.placeHighlight(highlightId, place.href, place.locatorJson, place.progression, place.chapterTitle)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }
}

/** The same store, whose reader places highlights through [placement] (for their server sync). */
fun ReaderAnnotationStore.withPlacement(placement: HighlightPlacementStore): ReaderAnnotationStore =
    PlacingAnnotationStore(this, placement)

/** The same store, with nothing to place: for a book whose highlights stay on this device. */
fun ReaderAnnotationStore.withoutPlacing(): ReaderAnnotationStore = withPlacement(HighlightPlacementStore.None)

// A data class, so the reader sees the same store from one composition to the next.
private data class PlacingAnnotationStore(
    private val store: ReaderAnnotationStore,
    override val placement: HighlightPlacementStore,
) : ReaderAnnotationStore by store
