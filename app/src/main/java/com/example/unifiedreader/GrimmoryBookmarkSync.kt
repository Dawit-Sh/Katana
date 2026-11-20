package com.example.unifiedreader

import android.util.Log
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.primaryAuthor
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.data.grimmory.GrimmoryApi
import com.example.unifiedreader.data.grimmory.GrimmoryBookmark
import com.example.unifiedreader.data.grimmory.GrimmoryBookmarkPlace
import com.example.unifiedreader.data.grimmory.GrimmoryBookmarkWrite
import com.example.unifiedreader.data.grimmory.GrimmoryBookmarksApi
import com.example.unifiedreader.data.grimmory.GrimmorySignedOutException
import com.example.unifiedreader.data.grimmory.GrimmoryUnauthorizedException
import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.BookmarkServer
import com.example.unifiedreader.feature.annotations.BookmarkServerAnswer
import com.example.unifiedreader.feature.annotations.BookmarkSync
import com.example.unifiedreader.feature.annotations.BookmarkSyncEngine
import com.example.unifiedreader.feature.annotations.BookmarkSyncStore
import com.example.unifiedreader.feature.annotations.BookmarkUpload
import com.example.unifiedreader.feature.annotations.RemoteBookmark
import com.example.unifiedreader.feature.annotations.toPageNumberOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bookmark sync with Grimmory, both ways, through `/api/v1/bookmarks` (its web readers'), for its EPUBs
 * (places are EPUB CFIs), PDFs and comics (places are pages: a PDF's in `pageNumber`, a comic's written in
 * `cfi`, as Grimmory's own readers keep them), and for files here that are copies of them.
 * Requests go through the saved sign-in, so a renewal goes through [GrimmoryTokenRenewals]. One sync
 * at a time.
 * - A book: one request for its bookmarks, then one per change waiting (a new one with a note takes two:
 *   Grimmory takes no note when a bookmark is made).
 * - Every book (after a library sync): one request for every book's bookmarks (the notebook, without
 *   places), then a book's own request only for a book whose list there or here changed.
 */
@Singleton
class GrimmoryBookmarkSync @Inject constructor(
    private val grimmoryApiFactory: GrimmoryApiFactory,
    private val credentialStore: GrimmoryCredentialStore,
    private val store: BookmarkSyncStore,
    private val connectivity: RemoteSourceConnectivityPolicy,
) {
    private val engine = BookmarkSyncEngine(store)
    private val sessionRequestRunner = GrimmoryStoredSessionRequestRunner(credentialStore)
    private val mutex = Mutex()

    /** [books]' bookmarks from the server, and their changes waiting here up to it. False when it failed. */
    suspend fun syncBooks(books: List<ReadableItem>): Boolean = syncMarksBooks(books.mapNotNull(ReadableItem::asGrimmoryMarksBook))

    /** The same for books and copies here of Grimmory's books. */
    suspend fun syncMarksBooks(books: List<GrimmoryMarksBook>): Boolean = run(books) { bookmarks, token, synced ->
        synced.eachBook { book -> syncBook(bookmarks, token, book) }
    }

    /**
     * After a library sync: the books whose bookmarks changed on the server ([catalog]'s, and [copies] here
     * of them), and those with bookmarks here waiting or on the server ([booksOnDevice]).
     */
    suspend fun syncLibrary(
        catalog: List<ReadableItem>,
        booksOnDevice: Set<String>,
        copies: List<GrimmoryMarksBook> = emptyList(),
    ): Boolean = run(catalog.mapNotNull(ReadableItem::asGrimmoryMarksBook) + copies) { bookmarks, token, synced ->
        val byBookId = synced.groupBy(GrimmoryMarksBook::bookId)
        val serverBooks = bookmarks.allBookmarks(token).mapNotNull { it.bookId }.toSet()
        val toSync = serverBooks.flatMap { bookId -> byBookId[bookId].orEmpty() } + synced.filter { it.item.id in booksOnDevice }
        toSync.distinctBy { it.item.id }.eachBook { book -> syncBook(bookmarks, token, book) }
    }

    private suspend fun run(
        books: List<GrimmoryMarksBook>,
        block: suspend (bookmarks: GrimmoryBookmarksApi, token: String, books: List<GrimmoryMarksBook>) -> Unit,
    ): Boolean {
        val syncing = books.filter { book -> book.item.format.sharesBookmarksWithGrimmory() }
        if (syncing.isEmpty()) return true
        return mutex.withLock {
            val session = credentialStore.sessionFlow.value ?: return@withLock false
            val synced = syncing.onActiveGrimmoryServer(session)
            if (synced.isEmpty()) return@withLock true
            runSuspendCatching {
                runWithStoredSessionAddressFailover(
                    sessionStore = credentialStore,
                    session = session,
                    createApi = grimmoryApiFactory::create,
                ) { api: GrimmoryApi, activeSession ->
                    sessionRequestRunner.run(api = api, session = activeSession) { token ->
                        block(api.annotations.bookmarks, token, synced)
                    }
                }
            }.fold(
                onSuccess = {
                    Log.i(LOG_TAG, "Bookmarks of ${synced.size} books in step with Grimmory.")
                    connectivity.markReachable(SourceType.GRIMMORY)
                    true
                },
                onFailure = { failure ->
                    Log.w(LOG_TAG, "Bookmark sync with Grimmory failed.", failure)
                    connectivity.markFromFailure(SourceType.GRIMMORY, failure)
                    false
                },
            )
        }
    }

    // One book failing doesn't stop the others; the server going away or the sign-in running out does.
    private suspend fun List<GrimmoryMarksBook>.eachBook(sync: suspend (GrimmoryMarksBook) -> Unit) {
        var firstFailure: Throwable? = null
        forEach { book ->
            runSuspendCatching { sync(book) }.onFailure { failure ->
                failure.rethrowIfServerAddressUnreachable()
                if (failure is GrimmoryUnauthorizedException || failure is GrimmorySignedOutException) throw failure
                Log.w(LOG_TAG, "Bookmark sync of ${book.item.id} failed.", failure)
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
    }

    private suspend fun syncBook(bookmarks: GrimmoryBookmarksApi, token: String, book: GrimmoryMarksBook) {
        val item = book.item
        val annotated = AnnotatedBook(itemId = item.id, title = item.title, author = item.bookMetadata?.primaryAuthor())
        val server = GrimmoryBookmarkServer(bookmarks, token, book.bookId, item.format)
        val places = book.bookmarkPlaces()
        repeat(MAX_ROUNDS) {
            val list = bookmarks.bookBookmarks(token, book.bookId).mapNotNull { it.toRemoteBookmark(item.format) }
            if (engine.syncBook(annotated, list, server, places) == BookmarkSync.Done) return
        }
    }

    private companion object {
        const val LOG_TAG = "BookmarkSync"

        // A book whose server list changes under each round waits for the next sync.
        const val MAX_ROUNDS = 3
    }
}

private class GrimmoryBookmarkServer(
    private val bookmarks: GrimmoryBookmarksApi,
    private val token: String,
    private val bookId: String,
    private val format: FormatType,
) : BookmarkServer {
    override suspend fun create(upload: BookmarkUpload.Create): BookmarkServerAnswer {
        val place = grimmoryPlaceOf(upload.place, format) ?: return BookmarkServerAnswer.Refused
        val answer = bookmarks.create(token, bookId, place, upload.title).toAnswer()
        // Grimmory takes no note when a bookmark is made: it follows in a change.
        val note = upload.note
        if (answer is BookmarkServerAnswer.Saved && !note.isNullOrBlank()) {
            bookmarks.update(token, answer.remoteId, title = upload.title.orEmpty(), notes = note)
        }
        return answer
    }

    override suspend fun update(upload: BookmarkUpload.Update): BookmarkServerAnswer =
        bookmarks.update(token, upload.remoteId, title = upload.title.orEmpty(), notes = upload.note.orEmpty()).toAnswer()

    override suspend fun delete(upload: BookmarkUpload.Delete): Boolean = bookmarks.delete(token, upload.remoteId)
}

/** Where Grimmory keeps a new bookmark at [place] in a book of [format]: a PDF's page apart, a comic's page as its CFI. */
internal fun grimmoryPlaceOf(place: String, format: FormatType): GrimmoryBookmarkPlace? = when (format) {
    FormatType.EPUB -> GrimmoryBookmarkPlace.Cfi(place)
    FormatType.PDF -> place.toPageNumberOrNull()?.let(GrimmoryBookmarkPlace::PdfPage)
    FormatType.CBZ, FormatType.CBR -> place.toPageNumberOrNull()?.let { page -> GrimmoryBookmarkPlace.Cfi(page.toString()) }
    else -> null
}

private fun GrimmoryBookmarkWrite.toAnswer(): BookmarkServerAnswer = when (this) {
    is GrimmoryBookmarkWrite.Saved -> BookmarkServerAnswer.Saved(bookmark.id)
    GrimmoryBookmarkWrite.Missing -> BookmarkServerAnswer.Missing
    GrimmoryBookmarkWrite.AlreadyThere -> BookmarkServerAnswer.AlreadyThere
    is GrimmoryBookmarkWrite.Refused -> BookmarkServerAnswer.Refused
}

/**
 * A server bookmark as a book of [format] here can hold it, or null: an EPUB's needs a CFI (a comic-style
 * page number isn't one); a PDF's place is its page (`pageNumber`), a comic's the page written in `cfi`,
 * each falling back on the other field only when its own is empty. An audiobook's position, or a place of
 * another format of the same Grimmory book, isn't one this copy can hold, so it is left alone there.
 */
internal fun GrimmoryBookmark.toRemoteBookmark(format: FormatType): RemoteBookmark? {
    val cfiPage = cfi?.toPageNumberOrNull()
    val page = pageNumber?.takeIf { it > 0 }
    val place = when (format) {
        FormatType.EPUB -> cfi?.takeIf { cfiPage == null && pageNumber == null }
        FormatType.PDF -> (page ?: cfiPage)?.toString()
        FormatType.CBZ, FormatType.CBR -> (cfiPage ?: page.takeIf { cfi == null })?.toString()
        else -> null
    } ?: return null
    return RemoteBookmark(
        remoteId = id,
        place = place,
        title = title,
        note = notes,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
