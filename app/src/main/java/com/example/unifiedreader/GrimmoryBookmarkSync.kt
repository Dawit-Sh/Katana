package com.example.unifiedreader

import android.util.Log
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.primaryAuthor
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.data.grimmory.GrimmoryApi
import com.example.unifiedreader.data.grimmory.GrimmoryBookmark
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bookmark sync with Grimmory, both ways, through `/api/v1/bookmarks` (its web reader's), for the
 * same books as highlights ([syncsHighlightsWithGrimmory]: its EPUBs, whose places are EPUB CFIs).
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
    suspend fun syncBooks(books: List<ReadableItem>): Boolean = run(books) { bookmarks, token, synced ->
        synced.eachBook { item -> syncBook(bookmarks, token, item) }
    }

    /** After a library sync: the books whose bookmarks changed on the server ([catalog]'s), and those with bookmarks here. */
    suspend fun syncLibrary(catalog: List<ReadableItem>, booksOnDevice: Set<String>): Boolean = run(catalog) { bookmarks, token, synced ->
        val byRemoteId = synced.associateBy { item -> item.remoteId.orEmpty() }
        val serverBooks = bookmarks.allBookmarks(token).mapNotNull { it.bookId }.toSet()
        val toSync = serverBooks.mapNotNull(byRemoteId::get) + synced.filter { it.id in booksOnDevice }
        toSync.distinctBy(ReadableItem::id).eachBook { item -> syncBook(bookmarks, token, item) }
    }

    private suspend fun run(
        books: List<ReadableItem>,
        block: suspend (bookmarks: GrimmoryBookmarksApi, token: String, books: List<ReadableItem>) -> Unit,
    ): Boolean {
        val syncing = books.filter(ReadableItem::syncsHighlightsWithGrimmory)
        if (syncing.isEmpty()) return true
        return mutex.withLock {
            val session = credentialStore.sessionFlow.value ?: return@withLock false
            val synced = syncing.forActiveGrimmoryServer(session)
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
    private suspend fun List<ReadableItem>.eachBook(sync: suspend (ReadableItem) -> Unit) {
        var firstFailure: Throwable? = null
        forEach { item ->
            runSuspendCatching { sync(item) }.onFailure { failure ->
                failure.rethrowIfServerAddressUnreachable()
                if (failure is GrimmoryUnauthorizedException || failure is GrimmorySignedOutException) throw failure
                Log.w(LOG_TAG, "Bookmark sync of ${item.id} failed.", failure)
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
    }

    private suspend fun syncBook(bookmarks: GrimmoryBookmarksApi, token: String, item: ReadableItem) {
        val bookId = item.remoteId ?: return
        val book = AnnotatedBook(itemId = item.id, title = item.title, author = item.bookMetadata?.primaryAuthor())
        val server = GrimmoryBookmarkServer(bookmarks, token, bookId)
        repeat(MAX_ROUNDS) {
            val list = bookmarks.bookBookmarks(token, bookId).mapNotNull(GrimmoryBookmark::toRemoteBookmark)
            if (engine.syncBook(book, list, server) == BookmarkSync.Done) return
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
) : BookmarkServer {
    override suspend fun create(upload: BookmarkUpload.Create): BookmarkServerAnswer {
        val answer = bookmarks.create(token, bookId, upload.cfi, upload.title).toAnswer()
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

private fun GrimmoryBookmarkWrite.toAnswer(): BookmarkServerAnswer = when (this) {
    is GrimmoryBookmarkWrite.Saved -> BookmarkServerAnswer.Saved(bookmark.id)
    GrimmoryBookmarkWrite.Missing -> BookmarkServerAnswer.Missing
    GrimmoryBookmarkWrite.AlreadyThere -> BookmarkServerAnswer.AlreadyThere
    is GrimmoryBookmarkWrite.Refused -> BookmarkServerAnswer.Refused
}

/** A bookmark with an EPUB place; a PDF's (by page) or an audiobook's isn't one an EPUB can hold. */
internal fun GrimmoryBookmark.toRemoteBookmark(): RemoteBookmark? = cfi?.let { place ->
    RemoteBookmark(
        remoteId = id,
        cfi = place,
        title = title,
        note = notes,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
