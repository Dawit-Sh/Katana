package com.example.unifiedreader.feature.annotations

import com.example.unifiedreader.core.database.dao.ReaderBookmarkDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * A bookmark its reader has to place: [locatorJson] is empty for one from the server, which has
 * [serverPlace] (its EPUB CFI) instead; a new one made here has its locator and its page's first
 * words ([text]), and no server place yet.
 */
data class BookmarkToPlace(
    val id: String,
    val locatorJson: String,
    val serverPlace: String?,
    val text: String?,
    val chapterTitle: String?,
)

/** Where a bookmark from the server is in this device's copy of the book, with the page and first words there. */
data class BookmarkPlace(
    val place: AnnotationPlace,
    val page: Int?,
    val pageCount: Int?,
    val text: String?,
)

/**
 * Where an open book's reader works out its bookmarks' places for their server sync, as for its
 * highlights ([HighlightPlacementStore.bookmarks]).
 */
interface BookmarkPlacementStore {
    fun bookmarksToPlace(itemId: String): Flow<List<BookmarkToPlace>>

    suspend fun setServerPlace(bookmarkId: String, cfi: String)

    suspend fun keepOnDevice(bookmarkId: String)

    suspend fun place(bookmarkId: String, place: BookmarkPlace)

    data object None : BookmarkPlacementStore {
        override fun bookmarksToPlace(itemId: String): Flow<List<BookmarkToPlace>> = flowOf(emptyList())

        override suspend fun setServerPlace(bookmarkId: String, cfi: String) = Unit

        override suspend fun keepOnDevice(bookmarkId: String) = Unit

        override suspend fun place(bookmarkId: String, place: BookmarkPlace) = Unit
    }
}

class RoomBookmarkPlacementStore(
    private val dao: ReaderBookmarkDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BookmarkPlacementStore {
    override fun bookmarksToPlace(itemId: String): Flow<List<BookmarkToPlace>> =
        dao.observeBookmarksToPlace(itemId)
            .map { rows -> rows.map { row -> BookmarkToPlace(row.id, row.locatorJson, row.remoteCfi, row.text, row.chapterTitle) } }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override suspend fun setServerPlace(bookmarkId: String, cfi: String) = io { dao.setBookmarkCfi(bookmarkId, cfi) }

    override suspend fun keepOnDevice(bookmarkId: String) = io { dao.keepBookmarkOnDevice(bookmarkId) }

    override suspend fun place(bookmarkId: String, place: BookmarkPlace) = io {
        dao.placeBookmark(
            id = bookmarkId,
            href = place.place.href,
            locatorJson = place.place.locatorJson,
            progression = place.place.progression,
            page = place.page,
            pageCount = place.pageCount,
            text = place.text,
            chapterTitle = place.place.chapterTitle,
        )
    }

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }
}
