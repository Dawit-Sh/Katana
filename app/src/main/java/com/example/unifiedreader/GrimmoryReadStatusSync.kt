package com.example.unifiedreader

import android.util.Log
import com.example.unifiedreader.core.common.ApplicationScope
import com.example.unifiedreader.core.model.ReadStatus
import com.example.unifiedreader.core.model.ReadStatusRecord
import com.example.unifiedreader.core.model.ReadStatusStore
import com.example.unifiedreader.core.model.SourceConnectivityState
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.remoteItemSourceType
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.core.model.toServerEpochMillisOrNull
import com.example.unifiedreader.data.grimmory.GrimmoryApi
import com.example.unifiedreader.data.grimmory.GrimmoryBookCatalogEntry
import com.example.unifiedreader.data.grimmory.GrimmorySignedOutException
import com.example.unifiedreader.data.grimmory.GrimmoryUnauthorizedException
import com.example.unifiedreader.data.repository.ReadableItemRepository
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A Grimmory book's status as its catalog or book answer carries it (Katana Desk answers the same way). */
internal fun GrimmoryBookCatalogEntry.toServerBookStatus(): ServerBookStatus = ServerBookStatus(
    itemId = item.id,
    status = readStatus,
    setAtEpochMillis = readStatusModifiedTime?.toServerEpochMillisOrNull(),
)

/** Whether a server's book's place here waits to go up (its server's status is then from before it). */
internal fun ReaderProgressSnapshot?.waitsToGoUp(): Boolean = this != null && syncState != SyncState.CLEAN

/**
 * Takes Grimmory's statuses for [entries] (a library sync's or a book's answer), as [applyServerStatuses] says;
 * the ones waiting here go up through [GrimmoryReadStatusSync].
 */
internal fun ReadStatusStore.takeGrimmoryStatuses(
    entries: List<GrimmoryBookCatalogEntry>,
    readerSessionStore: ReaderSessionStore,
    nowEpochMillis: Long,
) {
    if (entries.isEmpty()) return
    val places = readerSessionStore.progressByItemIdFlow.value
    applyServerStatuses(
        statuses = entries.map(GrimmoryBookCatalogEntry::toServerBookStatus),
        placeOf = places::get,
        placeUnsent = { itemId -> places[itemId].waitsToGoUp() },
        nowEpochMillis = nowEpochMillis,
    )
}

/**
 * Sends the reading statuses set here for Grimmory's books (`POST /api/v1/books/status`, as its web app does),
 * through the saved sign-in like every other request. A status goes a few seconds after it is set, once the
 * book's place has gone up (Grimmory makes Reading or Read of a place it is sent, so the status follows the
 * place, never the other way round); after a failure, with the other waiting uploads' retries, and as soon as
 * Grimmory is online again. Statuses come down with the library sync and when a book is opened
 * ([takeGrimmoryStatuses]). Only Grimmory's books are sent: files here keep their status here (or with
 * Katana Desk), and a book the server no longer has is left.
 */
@Singleton
class GrimmoryReadStatusSync @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    private val grimmoryApiFactory: GrimmoryApiFactory,
    private val credentialStore: GrimmoryCredentialStore,
    private val store: ReadStatusStore,
    private val readerSessionStore: ReaderSessionStore,
    private val catalog: ReadableItemRepository,
    private val connectivity: RemoteSourceConnectivityPolicy,
) {
    private val sessionRequestRunner = GrimmoryStoredSessionRequestRunner(credentialStore)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)

    // Statuses Grimmory refused, or of books it doesn't list: not tried again until they change.
    private val leftAlone = ConcurrentHashMap<String, ReadStatusRecord>()

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(store.records, readerSessionStore.progressByItemIdFlow, connectivity.reachabilityFlow) { records, places, reach ->
                waitingToSend(records, places) to (reach[SourceType.GRIMMORY] == SourceConnectivityState.Online)
            }
                .distinctUntilChanged()
                .debounce(SEND_DEBOUNCE_MILLIS)
                .collect { (waiting, online) -> if (online && waiting.isNotEmpty()) sendWaiting() }
        }
        scope.launchPendingSyncRetries(hasWork = ::hasWaiting, retry = { scope.launch { sendWaiting() } })
    }

    fun hasWaiting(): Boolean =
        credentialStore.sessionFlow.value != null &&
            waitingToSend(store.records.value, readerSessionStore.progressByItemIdFlow.value).isNotEmpty()

    /** Sends what waits now; false when Grimmory couldn't be reached or refused the sign-in. */
    suspend fun sendWaiting(): Boolean = mutex.withLock {
        val session = credentialStore.sessionFlow.value ?: return@withLock false
        val waiting = waitingToSend(store.records.value, readerSessionStore.progressByItemIdFlow.value)
        if (waiting.isEmpty()) return@withLock true
        val books = withContext(Dispatchers.IO) {
            catalog.getItems().filter { it.id in waiting && !it.remoteId.isNullOrBlank() }.forActiveGrimmoryServer(session)
        }
        // Not in this server's library (gone from it, or another server's): left until its status changes.
        (waiting - books.mapTo(mutableSetOf()) { it.id }).forEach { itemId -> store.get(itemId)?.let { leftAlone[itemId] = it } }
        if (books.isEmpty()) return@withLock true
        runSuspendCatching {
            runWithStoredSessionAddressFailover(
                sessionStore = credentialStore,
                session = session,
                createApi = grimmoryApiFactory::create,
            ) { api: GrimmoryApi, activeSession ->
                sessionRequestRunner.run(api = api, session = activeSession) { token ->
                    books.forEach { book -> send(api, token, book.id, requireNotNull(book.remoteId)) }
                }
            }
        }.fold(
            onSuccess = {
                connectivity.markReachable(SourceType.GRIMMORY)
                true
            },
            onFailure = { failure ->
                Log.w(LOG_TAG, "Reading statuses didn't reach Grimmory.", failure)
                connectivity.markFromFailure(SourceType.GRIMMORY, failure)
                false
            },
        )
    }

    // One book's status up; Grimmory's answer is kept unless the status changed here meanwhile. A refusal for
    // one book doesn't stop the others; the server going away or the sign-in running out does.
    private suspend fun send(api: GrimmoryApi, token: String, itemId: String, bookId: String) {
        val record = store.get(itemId)?.takeIf(ReadStatusRecord::unsent) ?: return
        runSuspendCatching { api.setReadStatus(accessToken = token, bookId = bookId, status = record.status.serverName) }
            .onSuccess { answer ->
                val kept = ReadStatus.fromServer(answer.status) ?: record.status
                store.replaceIfCurrent(itemId, record, record.copy(status = kept, unsent = false))
            }
            .onFailure { failure ->
                failure.rethrowIfServerAddressUnreachable()
                if (failure is GrimmoryUnauthorizedException || failure is GrimmorySignedOutException) throw failure
                Log.w(LOG_TAG, "Grimmory refused the status of $itemId.", failure)
                leftAlone[itemId] = record
            }
    }

    // Grimmory's books whose status waits here, once their place isn't waiting to go up first.
    private fun waitingToSend(records: Map<String, ReadStatusRecord>, places: Map<String, ReaderProgressSnapshot>): Set<String> =
        records.filter { (itemId, record) ->
            record.unsent &&
                remoteItemSourceType(itemId) == SourceType.GRIMMORY &&
                leftAlone[itemId] != record &&
                !places[itemId].waitsToGoUp()
        }.keys

    private companion object {
        const val LOG_TAG = "GrimmoryReadStatus"
        const val SEND_DEBOUNCE_MILLIS = 3_000L
    }
}
