package com.example.unifiedreader

import android.util.Log
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.primaryAuthor
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotation
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotationChange
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotationCreate
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotationWrite
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotationsApi
import com.example.unifiedreader.data.grimmory.GrimmoryApi
import com.example.unifiedreader.data.grimmory.GrimmorySignedOutException
import com.example.unifiedreader.data.grimmory.GrimmoryUnauthorizedException
import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.HighlightBookSync
import com.example.unifiedreader.feature.annotations.HighlightServer
import com.example.unifiedreader.feature.annotations.HighlightServerAnswer
import com.example.unifiedreader.feature.annotations.HighlightSyncEngine
import com.example.unifiedreader.feature.annotations.HighlightSyncStore
import com.example.unifiedreader.feature.annotations.HighlightUpload
import com.example.unifiedreader.feature.annotations.RemoteHighlight
import com.example.unifiedreader.feature.annotations.highlightColorFromServer
import com.example.unifiedreader.feature.annotations.highlightStyleFromServer
import com.example.unifiedreader.feature.annotations.serverName
import com.example.unifiedreader.feature.annotations.toServerHex
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The books whose highlights Grimmory shares: its EPUBs. Grimmory keeps a highlight's place as an
 * EPUB CFI; a MOBI, AZW3 or FB2 is read here as an EPUB Katana makes from it, whose places the web
 * reader (reading the original file) can't use, and a PDF's or a comic's highlights are its own.
 */
fun ReadableItem.syncsHighlightsWithGrimmory(): Boolean =
    sourceType == SourceType.GRIMMORY && format == FormatType.EPUB && !remoteId.isNullOrBlank()

/**
 * Highlight sync with Grimmory, both ways, with the API its web reader and the grimmory.koplugin
 * use (`/api/v1/annotations`). Requests go through the saved sign-in, so a renewal goes through
 * [GrimmoryTokenRenewals] like every other request. One sync at a time: two at once could both send
 * the same new highlight.
 * - A book: one request for its highlights, then one per change waiting.
 * - Every book (after a library sync): one request for all highlights (Grimmory's notebook, without
 *   places); a book only needs its own request when its list may hold a highlight this device made
 *   under another id. New highlights from the server get their place in the book when it is opened.
 */
@Singleton
class GrimmoryHighlightSync @Inject constructor(
    private val grimmoryApiFactory: GrimmoryApiFactory,
    private val credentialStore: GrimmoryCredentialStore,
    private val store: HighlightSyncStore,
    private val connectivity: RemoteSourceConnectivityPolicy,
) {
    private val engine = HighlightSyncEngine(store)
    private val sessionRequestRunner = GrimmoryStoredSessionRequestRunner(credentialStore)
    private val mutex = Mutex()

    /** [books]' highlights from the server, and their changes waiting here up to it. False when it failed. */
    suspend fun syncBooks(books: List<ReadableItem>): Boolean =
        run(books.filter(ReadableItem::syncsHighlightsWithGrimmory)) { annotations, token, synced ->
            synced.eachBook { item -> syncBook(annotations, token, item, remote = null) }
        }

    /**
     * Every book's highlights from the server in one request, then what waits here: [catalog] is the
     * library, [booksOnDevice] the books with highlights here that the server has or waits for.
     */
    suspend fun syncLibrary(catalog: List<ReadableItem>, booksOnDevice: Set<String>): Boolean {
        val books = catalog.filter(ReadableItem::syncsHighlightsWithGrimmory)
        return run(books) { annotations, token, synced ->
            val byRemoteId = synced.associateBy { item -> item.remoteId.orEmpty() }
            val serverLists = annotations.allHighlights(token).groupBy { highlight -> highlight.bookId.orEmpty() }
            val toSync = serverLists.keys.mapNotNull(byRemoteId::get) + synced.filter { it.id in booksOnDevice }
            toSync.distinctBy(ReadableItem::id).eachBook { item ->
                syncBook(annotations, token, item, remote = serverLists[item.remoteId].orEmpty())
            }
        }
    }

    private suspend fun run(
        books: List<ReadableItem>,
        block: suspend (annotations: GrimmoryAnnotationsApi, token: String, books: List<ReadableItem>) -> Unit,
    ): Boolean {
        if (books.isEmpty()) return true
        return mutex.withLock {
            val session = credentialStore.sessionFlow.value ?: return@withLock false
            val synced = books.forActiveGrimmoryServer(session)
            if (synced.isEmpty()) return@withLock true
            runSuspendCatching {
                runWithStoredSessionAddressFailover(
                    sessionStore = credentialStore,
                    session = session,
                    createApi = grimmoryApiFactory::create,
                ) { api: GrimmoryApi, activeSession ->
                    sessionRequestRunner.run(api = api, session = activeSession) { token ->
                        block(api.annotations, token, synced)
                    }
                }
            }.fold(
                onSuccess = {
                    Log.i(LOG_TAG, "Highlights of ${synced.size} books in step with Grimmory.")
                    connectivity.markReachable(SourceType.GRIMMORY)
                    true
                },
                onFailure = { failure ->
                    Log.w(LOG_TAG, "Highlight sync with Grimmory failed.", failure)
                    connectivity.markFromFailure(SourceType.GRIMMORY, failure)
                    false
                },
            )
        }
    }

    // One book failing (a server error) doesn't stop the others; the server going away or the sign-in
    // running out does, so the address failover and the renewal can act.
    private suspend fun List<ReadableItem>.eachBook(sync: suspend (ReadableItem) -> Unit) {
        var firstFailure: Throwable? = null
        forEach { item ->
            runSuspendCatching { sync(item) }.onFailure { failure ->
                failure.rethrowIfServerAddressUnreachable()
                if (failure is GrimmoryUnauthorizedException || failure is GrimmorySignedOutException) throw failure
                Log.w(LOG_TAG, "Highlight sync of ${item.id} failed.", failure)
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
    }

    // [remote]: the book's highlights from the list of every book's (no places), or null to ask for the book's own.
    private suspend fun syncBook(
        annotations: GrimmoryAnnotationsApi,
        token: String,
        item: ReadableItem,
        remote: List<GrimmoryAnnotation>?,
    ) {
        val bookId = item.remoteId ?: return
        val book = AnnotatedBook(itemId = item.id, title = item.title, author = item.bookMetadata?.primaryAuthor())
        val server = GrimmoryHighlightServer(annotations, token, bookId)
        var list = remote ?: annotations.bookHighlights(token, bookId)
        repeat(MAX_ROUNDS) {
            when (engine.syncBook(book, list.map(GrimmoryAnnotation::toRemoteHighlight), server)) {
                HighlightBookSync.Done -> return
                HighlightBookSync.NeedsPlaces,
                HighlightBookSync.ServerChangedMeanwhile -> list = annotations.bookHighlights(token, bookId)
            }
        }
    }

    private companion object {
        const val LOG_TAG = "HighlightSync"

        // A book whose server list changes under each round (someone highlighting there right now) waits for the next sync.
        const val MAX_ROUNDS = 3
    }
}

private class GrimmoryHighlightServer(
    private val annotations: GrimmoryAnnotationsApi,
    private val token: String,
    private val bookId: String,
) : HighlightServer {
    override suspend fun create(upload: HighlightUpload.Create): HighlightServerAnswer = annotations.create(
        token,
        GrimmoryAnnotationCreate(
            bookId = bookId,
            cfi = upload.cfi,
            text = upload.text,
            color = upload.color.toServerHex(),
            style = upload.style.serverName,
            note = upload.note,
            chapterTitle = upload.chapterTitle,
        ),
    ).toAnswer()

    override suspend fun update(upload: HighlightUpload.Update): HighlightServerAnswer = annotations.update(
        token,
        upload.remoteId,
        GrimmoryAnnotationChange(color = upload.color.toServerHex(), style = upload.style.serverName, note = upload.note.orEmpty()),
    ).toAnswer()

    override suspend fun delete(upload: HighlightUpload.Delete): Boolean = annotations.delete(token, upload.remoteId)
}

private fun GrimmoryAnnotationWrite.toAnswer(): HighlightServerAnswer = when (this) {
    is GrimmoryAnnotationWrite.Saved -> HighlightServerAnswer.Saved(annotation.id)
    GrimmoryAnnotationWrite.Missing -> HighlightServerAnswer.Missing
    GrimmoryAnnotationWrite.AlreadyThere -> HighlightServerAnswer.AlreadyThere
    is GrimmoryAnnotationWrite.Refused -> HighlightServerAnswer.Refused
}

internal fun GrimmoryAnnotation.toRemoteHighlight(): RemoteHighlight = RemoteHighlight(
    remoteId = id,
    cfi = cfi,
    text = text,
    note = note,
    color = highlightColorFromServer(color),
    style = highlightStyleFromServer(style),
    chapterTitle = chapterTitle,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
