package com.example.unifiedreader

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.unifiedreader.core.common.ApplicationScope
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadStatus
import com.example.unifiedreader.core.model.ReadStatusStore
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.primaryAuthor
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.core.model.toServerEpochMillisOrNull
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotation
import com.example.unifiedreader.data.grimmory.GrimmoryApi
import com.example.unifiedreader.data.grimmory.GrimmoryBookCatalogEntry
import com.example.unifiedreader.data.grimmory.KtorGrimmoryApi
import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.BookmarkPlaces
import com.example.unifiedreader.feature.annotations.BookmarkSync
import com.example.unifiedreader.feature.annotations.BookmarkSyncEngine
import com.example.unifiedreader.feature.annotations.BookmarkSyncStore
import com.example.unifiedreader.feature.annotations.HighlightBookSync
import com.example.unifiedreader.feature.annotations.HighlightSyncEngine
import com.example.unifiedreader.feature.annotations.HighlightSyncStore
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Katana Desk this device is paired with: where it is, and this device's pass for it. */
data class KatanaDeskPaired(val address: String, val token: String, val deskId: String, val name: String) {
    val baseUrl: String get() = deskBaseUrl(address)
}

/** A desk found on this Wi-Fi, or answering at an address typed in. */
data class KatanaDeskFound(val name: String, val address: String)

/**
 * The pairing with Katana Desk and which books here are on it ([links]: local book id to the desk's book id).
 * Kept on this device only. Forget keeps the links, so pairing the same desk again carries on where it was.
 */
@Singleton
class KatanaDeskConnection @Inject constructor(@param:ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("katana_desk", Context.MODE_PRIVATE)
    private val pairedState = MutableStateFlow(readPaired())
    private val linkState = MutableStateFlow(readLinks())
    private val syncedState = MutableStateFlow(prefs.getLong(KEY_SYNCED, 0L))

    val paired: StateFlow<KatanaDeskPaired?> = pairedState.asStateFlow()
    val links: StateFlow<Map<String, String>> = linkState.asStateFlow()
    val lastSyncedAt: StateFlow<Long> = syncedState.asStateFlow()

    /** This device's own id for the desk, made once. */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit().putString(KEY_DEVICE, id).apply()
        }

    fun isLinked(itemId: String): Boolean = itemId in linkState.value

    fun save(paired: KatanaDeskPaired) {
        prefs.edit()
            .putString(KEY_ADDRESS, paired.address)
            .putString(KEY_TOKEN, paired.token)
            .putString(KEY_DESK, paired.deskId)
            .putString(KEY_NAME, paired.name)
            .apply()
        pairedState.value = paired
    }

    fun forget() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_TOKEN).remove(KEY_NAME).apply()
        pairedState.value = null
    }

    fun saveLinks(links: Map<String, String>) {
        prefs.edit().putStringSet(KEY_LINKS, links.map { (item, book) -> "$item=$book" }.toSet()).apply()
        linkState.value = links
    }

    fun markSynced(at: Long) {
        prefs.edit().putLong(KEY_SYNCED, at).apply()
        syncedState.value = at
    }

    /** The desk the links were made with, so another desk doesn't take them over. */
    val linkedDeskId: String? get() = prefs.getString(KEY_DESK, null)

    private fun readPaired(): KatanaDeskPaired? {
        val address = prefs.getString(KEY_ADDRESS, null)
        val token = prefs.getString(KEY_TOKEN, null)
        return if (address == null || token == null) {
            null
        } else {
            KatanaDeskPaired(address, token, prefs.getString(KEY_DESK, null).orEmpty(), prefs.getString(KEY_NAME, null).orEmpty())
        }
    }

    private fun readLinks(): Map<String, String> = prefs.getStringSet(KEY_LINKS, emptySet()).orEmpty()
        .mapNotNull { row -> row.split('=', limit = 2).takeIf { it.size == 2 }?.let { (item, book) -> item to book } }
        .toMap()

    private companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_TOKEN = "token"
        const val KEY_DESK = "desk_id"
        const val KEY_NAME = "name"
        const val KEY_LINKS = "links"
        const val KEY_SYNCED = "synced_at"
        const val KEY_DEVICE = "device_id"
    }
}

/** What the desk said no to, in words to show. */
class KatanaDeskRefused(message: String) : RuntimeException(message)

/** A book here as the desk is asked about it: the same file is the same format, size and partial MD5. */
internal data class KatanaDeskBookAsk(val itemId: String, val fingerprint: LocalFileFingerprint, val format: String)

/** Katana Desk's own requests: hello, pair and which books it has. The rest is Grimmory's API. */
internal class KatanaDeskClient(private val http: HttpClient) {
    suspend fun hello(address: String): KatanaDeskFound? = runSuspendCatching {
        val response = request { http.get("${deskBaseUrl(address)}/katana-sync/v1/hello") }
        if (!response.status.isSuccess()) return@runSuspendCatching null
        val body = JSONObject(response.bodyAsText())
        if (body.optString("app") != "katana-desk") return@runSuspendCatching null
        KatanaDeskFound(name = body.optString("name").ifBlank { "Katana Desk" }, address = address.normalizedDeskAddress())
    }.getOrNull()

    suspend fun pair(address: String, code: String, deviceId: String): KatanaDeskPaired {
        val body = JSONObject()
            .put("code", code.filter(Char::isDigit))
            .put("deviceId", deviceId)
            .put("deviceName", deviceName())
        val response = request {
            http.post("${deskBaseUrl(address)}/katana-sync/v1/pair") {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        val refusal = when (response.status.value) {
            in SUCCESS -> null
            FORBIDDEN -> "That code isn't right, or it's too old. Check the code on the PC."
            TOO_MANY -> "Too many tries. Wait a minute, then try again."
            else -> "Katana Desk said no (${response.status.value})."
        }
        if (refusal != null) throw KatanaDeskRefused(refusal)
        val answer = JSONObject(response.bodyAsText())
        return KatanaDeskPaired(
            address = address.normalizedDeskAddress(),
            token = answer.getString("token"),
            deskId = answer.optString("deskId"),
            name = answer.optString("name").ifBlank { "Katana Desk" },
        )
    }

    /** The desk's book ids of [books] it has, by local book id. */
    suspend fun match(desk: KatanaDeskPaired, books: List<KatanaDeskBookAsk>): Map<String, String> {
        if (books.isEmpty()) return emptyMap()
        val asks = JSONArray()
        books.forEach { book ->
            asks.put(
                JSONObject()
                    .put("key", book.itemId)
                    .put("md5", book.fingerprint.partialMd5)
                    .put("size", book.fingerprint.sizeBytes)
                    .put("format", book.format),
            )
        }
        val response = request {
            http.post("${desk.baseUrl}/katana-sync/v1/match") {
                bearerAuth(desk.token)
                contentType(ContentType.Application.Json)
                setBody(JSONObject().put("books", asks).toString())
            }
        }
        if (response.status.value == UNAUTHORIZED) throw KatanaDeskRefused("Katana Desk forgot this device. Pair it again.")
        if (!response.status.isSuccess()) throw KatanaDeskRefused("Katana Desk couldn't look for the books (${response.status.value}).")
        val found = JSONObject(response.bodyAsText()).optJSONArray("books") ?: JSONArray()
        return (0 until found.length()).map(found::getJSONObject).associate { row -> row.getString("key") to row.get("bookId").toString() }
    }

    // A refused request answers with its status here, whether or not the client throws on one.
    private suspend fun request(send: suspend () -> HttpResponse): HttpResponse = withTimeout(REQUEST_TIMEOUT_MILLIS) {
        try {
            send()
        } catch (refused: ResponseException) {
            refused.response
        }
    }

    private companion object {
        val SUCCESS = 200..299
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val TOO_MANY = 429
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
    }
}

private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
    .filter { it.isNotBlank() }
    .distinctBy(String::lowercase)
    .joinToString(" ")
    .ifBlank { "Katana" }

/** "192.168.1.20" → "192.168.1.20:47615"; an address with a port or a scheme keeps it. */
internal fun String.normalizedDeskAddress(): String {
    val bare = trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    return if (bare.substringAfterLast(']').contains(':')) bare else "$bare:$KATANA_DESK_PORT"
}

internal fun deskBaseUrl(address: String): String = "http://${address.normalizedDeskAddress()}"

/** How the app names the desk to the reader ("Katana Desk has you on p. 19"). */
const val KATANA_DESK_NAME = "Katana Desk"

/** The port Katana Desk answers on. */
const val KATANA_DESK_PORT = 47615

/** The DNS-SD service Katana Desk shows itself as on the Wi-Fi. */
const val KATANA_DESK_SERVICE = "_katana-desk._tcp"

/**
 * Syncs the books here that are on Katana Desk too, with Grimmory's own rules (the desk answers Grimmory's
 * API for its books): the most recent place wins, asking before going back; the reading status the same way
 * ([decideServerReadStatus]); highlights and bookmarks through the same engines as Grimmory's, holds and Undo
 * included. A book linked to Grimmory syncs there, not here.
 */
@Singleton
class KatanaDeskSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val connection: KatanaDeskConnection,
    private val httpClient: HttpClient,
    private val localDocuments: LocalDocumentDataSource,
    private val grimmoryCopies: dagger.Lazy<GrimmoryLocalCopies>,
    highlightStore: HighlightSyncStore,
    bookmarkStore: BookmarkSyncStore,
    private val readerSessionStore: ReaderSessionStore,
    private val readStatuses: ReadStatusStore,
) {
    private val client = KatanaDeskClient(httpClient)
    private val fingerprints = LocalFileFingerprints(context)
    private val bookSync = KatanaDeskBookSync(
        highlights = HighlightSyncEngine(highlightStore),
        bookmarks = BookmarkSyncEngine(bookmarkStore),
        readerSessionStore = readerSessionStore,
        statuses = readStatuses,
    )
    private val highlightWaiting = highlightStore.observeWaiting()
    private val bookmarkWaiting = bookmarkStore.observeWaiting()
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val syncingState = MutableStateFlow(false)
    private val problemState = MutableStateFlow<String?>(null)

    val syncing: StateFlow<Boolean> = syncingState.asStateFlow()

    private val placesChangedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A sync changed the place of a book here: the library's shelves and cards follow. */
    val placesChanged: SharedFlow<Unit> = placesChangedFlow.asSharedFlow()

    private fun linkedPlaces(books: List<ReadableItem>) =
        readerSessionStore.progressByItemIdFlow.value.let { places -> books.associate { it.id to places[it.id] } }

    /** Why the last sync didn't finish, in words to show; null when it did. */
    val problem: StateFlow<String?> = problemState.asStateFlow()

    /** Whether [item]'s marks sync with the desk (its highlights get places to send, like Grimmory's). */
    fun isLinked(item: ReadableItem): Boolean = connection.isLinked(item.id)

    /** The desk answering at [address], or null. */
    suspend fun hello(address: String): KatanaDeskFound? = client.hello(address)

    /** Pairs with the desk at [address] with the [code] it shows, then syncs. */
    suspend fun pair(address: String, code: String): KatanaDeskPaired {
        val paired = client.pair(address, code, connection.deviceId)
        // Another desk than the one the books were linked with: its book ids mean other books.
        if (connection.linkedDeskId != null && connection.linkedDeskId != paired.deskId) connection.saveLinks(emptyMap())
        connection.save(paired)
        syncSoon()
        return paired
    }

    fun forget() = connection.forget()

    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { requests.debounce(SOON_MILLIS).collect { syncNow() } }
        scope.launch { highlightWaiting.drop(1).collect { syncSoon() } }
        scope.launch { bookmarkWaiting.drop(1).collect { syncSoon() } }
        // A status set here for a book on the desk goes a moment later, as a place does.
        scope.launch {
            combine(readStatuses.records, connection.links) { records, links ->
                records.filter { (itemId, record) -> record.unsent && itemId in links }.keys
            }
                .distinctUntilChanged()
                .drop(1)
                .collect { waiting -> if (waiting.isNotEmpty()) syncSoon() }
        }
        scope.launch {
            readerSessionStore.progressByItemIdFlow
                .map { places -> places.filterKeys(connection::isLinked).mapValues { (_, place) -> place.updatedAtEpochMillis } }
                .distinctUntilChanged()
                .drop(1)
                .debounce(PLACE_SETTLE_MILLIS)
                .collect { syncSoon() }
        }
        scope.launch {
            while (true) {
                syncSoon()
                delay(EVERY_MILLIS)
            }
        }
    }

    fun syncSoon() {
        if (connection.paired.value != null) requests.tryEmit(Unit)
    }

    /** Syncs now; how many books are on the desk, or null when it couldn't. */
    suspend fun syncNow(): Int? = mutex.withLock {
        val desk = connection.paired.value ?: return@withLock null
        syncingState.value = true
        val result = runSuspendCatching { syncWith(desk) }
        syncingState.value = false
        result
            .onSuccess {
                connection.markSynced(System.currentTimeMillis())
                problemState.value = null
            }
            .onFailure { failure ->
                if (failure !is KatanaDeskRefused) Log.w(LOG_TAG, "Sync with Katana Desk failed.", failure)
                problemState.value = (failure as? KatanaDeskRefused)?.message
                    ?: "Can't reach Katana Desk. Both on the same Wi-Fi, and Sync on this Wi-Fi on in its Settings?"
            }
            .getOrNull()
    }

    private suspend fun syncWith(desk: KatanaDeskPaired): Int {
        val api = KtorGrimmoryApi(httpClient = httpClient, baseUrl = desk.baseUrl)
        val grimmory = grimmoryCopies.get()
        val known = connection.links.value
        // A book here once linked to the desk stays with it; one linked to Grimmory syncs there.
        val books = localDocuments.getImportedItems().filter { item ->
            item.format in DESK_FORMATS && (item.id in known || !grimmory.isLinked(item))
        }
        val asks = books.filter { it.id !in known }.mapNotNull { item ->
            val uri = localDocuments.getDocumentUri(item) ?: return@mapNotNull null
            fingerprints.of(uri.toString())?.let { file -> KatanaDeskBookAsk(item.id, file, item.format.name) }
        }
        val links = known + client.match(desk, asks)
        if (links != known) connection.saveLinks(links)
        val linked = books.filter { it.id in links }
        val placesBefore = linkedPlaces(linked)
        var firstFailure: Throwable? = null
        linked.forEach { item ->
            runSuspendCatching { bookSync.sync(api, desk.token, item, links.getValue(item.id)) }.onFailure { failure ->
                failure.rethrowIfServerAddressUnreachable()
                Log.w(LOG_TAG, "Katana Desk sync of ${item.id} failed.", failure)
                if (firstFailure == null) firstFailure = failure
            }
        }
        // A place the desk changed here (another device read on, or marked it finished or unread) shows at once.
        if (linkedPlaces(linked) != placesBefore) placesChangedFlow.tryEmit(Unit)
        firstFailure?.let { throw it }
        return linked.size
    }

    private companion object {
        const val LOG_TAG = "KatanaDesk"
        const val SOON_MILLIS = 1_500L
        const val PLACE_SETTLE_MILLIS = 5_000L
        const val EVERY_MILLIS = 2 * 60_000L
        val DESK_FORMATS = setOf(FormatType.EPUB, FormatType.PDF, FormatType.CBZ, FormatType.CBR)
    }
}

/** One book's place, reading status, highlights and bookmarks with the desk, by Grimmory's rules and engines. */
private class KatanaDeskBookSync(
    private val highlights: HighlightSyncEngine,
    private val bookmarks: BookmarkSyncEngine,
    private val readerSessionStore: ReaderSessionStore,
    private val statuses: ReadStatusStore,
) {
    private val progressStore = ReaderSessionProgressStoreAdapter(readerSessionStore)

    suspend fun sync(api: GrimmoryApi, token: String, item: ReadableItem, bookId: String) {
        // A book the desk couldn't answer for (gone there, or an answer this version can't read) is left alone.
        val deskBook = api.getBookEntry(accessToken = token, bookId = bookId)
        if (deskBook != null) {
            // The most recent place wins as with Grimmory (asking before going back).
            val decision = decideRemoteProgressSync(
                local = progressStore.getProgress(item),
                remote = deskBook.progress,
                nowEpochMillis = System.currentTimeMillis(),
                missingRemoteClearsLocal = deskBook.isExplicitUnreadReset,
                remoteClearOverridesLocal = false,
            )
            val placeHere = if (decision == RemoteProgressSyncDecision.KeepLocal) {
                placeToSend(item, deskBook.progress?.updatedAtEpochMillis)
            } else {
                null
            }
            // The status before the place: while this device's place goes up, the desk's status is from before it
            // (the desk makes Reading or Read of a place), and the desk's place coming down isn't reading here.
            pullStatus(item, deskBook, placeGoesUp = placeHere != null)
            applyOrOfferRemoteProgress(
                store = progressStore,
                item = item,
                pageCount = item.pageCount,
                decision = decision,
                offeredBy = KATANA_DESK_NAME,
            )
            placeHere?.let { place -> pushPlace(api, token, item, place, deskBook) }
        }
        pushStatus(api, token, item, bookId)
        val book = AnnotatedBook(itemId = item.id, title = item.title, author = item.bookMetadata?.primaryAuthor())
        if (item.format == FormatType.EPUB) syncHighlights(api, token, book, bookId)
        syncBookmarks(api, token, item, book, bookId)
    }

    // This device's place, when it stayed and goes up: not sent yet (as Grimmory's queued saves) or newer.
    private fun placeToSend(item: ReadableItem, deskTime: Long?): ReaderProgressSnapshot? =
        readerSessionStore.progressByItemIdFlow.value[item.id]?.takeIf { place ->
            val unsent = place.syncState != SyncState.CLEAN
            unsent || !place.isExplicitUnreadReset && (deskTime == null || place.updatedAtEpochMillis > deskTime)
        }

    // [deskBook] is the desk's answer for the book, under the desk's book id.
    private suspend fun pushPlace(
        api: GrimmoryApi,
        token: String,
        item: ReadableItem,
        here: ReaderProgressSnapshot,
        deskBook: GrimmoryBookCatalogEntry,
    ) {
        val bookId = deskBook.item.remoteId ?: return
        if (here.isExplicitUnreadReset) {
            // Marked unread here: the desk forgets its place too, as Grimmory does.
            api.resetBookProgress(accessToken = token, bookId = bookId)
        } else {
            val pageCount = readerSessionStore.getSession(item)?.pageCount ?: item.pageCount
            val update = here.toGrimmoryProgressUpdate(item = item.copy(remoteId = bookId), pageCount = pageCount) ?: return
            // Read again here, finished there: the desk's finish goes (its Read becomes Reading), as the desk asks.
            val deskFinished = deskBook.dateFinished != null || deskBook.progress?.isFinished == true
            api.updateProgress(accessToken = token, progress = update.copy(clearsFinishedState = deskFinished && !here.isFinished))
        }
        // Sent: no longer a save waiting to go up, so a newer place on the desk wins without Grimmory's margin.
        readerSessionStore.replaceProgressIfCurrent(expected = here, replacement = here.copy(syncState = SyncState.CLEAN))
    }

    private fun pullStatus(item: ReadableItem, deskBook: GrimmoryBookCatalogEntry, placeGoesUp: Boolean) {
        val places = readerSessionStore.progressByItemIdFlow.value
        statuses.applyServerStatuses(
            statuses = listOf(
                ServerBookStatus(
                    itemId = item.id,
                    status = deskBook.readStatus,
                    setAtEpochMillis = deskBook.readStatusModifiedTime?.toServerEpochMillisOrNull(),
                ),
            ),
            placeOf = places::get,
            placeUnsent = { placeGoesUp },
            nowEpochMillis = System.currentTimeMillis(),
        )
    }

    // A status set here goes up after the place, so the desk's own Reading or Read of the place can't undo it;
    // the desk's answer is kept unless the status changed here meanwhile.
    private suspend fun pushStatus(api: GrimmoryApi, token: String, item: ReadableItem, bookId: String) {
        val record = statuses.get(item.id)?.takeIf { it.unsent } ?: return
        val answer = api.setReadStatus(accessToken = token, bookId = bookId, status = record.status.serverName)
        val kept = ReadStatus.fromServer(answer.status) ?: record.status
        statuses.replaceIfCurrent(item.id, record, record.copy(status = kept, unsent = false))
    }

    private suspend fun syncHighlights(api: GrimmoryApi, token: String, book: AnnotatedBook, bookId: String) {
        val server = GrimmoryHighlightServer(api.annotations, token, bookId)
        repeat(MAX_ROUNDS) {
            val list = api.annotations.bookHighlights(token, bookId).map(GrimmoryAnnotation::toRemoteHighlight)
            if (highlights.syncBook(book, list, server) == HighlightBookSync.Done) return
        }
    }

    private suspend fun syncBookmarks(api: GrimmoryApi, token: String, item: ReadableItem, book: AnnotatedBook, bookId: String) {
        val marks = api.annotations.bookmarks
        val server = GrimmoryBookmarkServer(marks, token, bookId, item.format)
        val places = if (item.format == FormatType.EPUB) BookmarkPlaces.EpubCfi else BookmarkPlaces.Pages(item.pageCount)
        repeat(MAX_ROUNDS) {
            val list = marks.bookBookmarks(token, bookId).mapNotNull { it.toRemoteBookmark(item.format) }
            if (bookmarks.syncBook(book, list, server, places) == BookmarkSync.Done) return
        }
    }

    private companion object {
        // A book whose desk list changes under each round waits for the next sync.
        const val MAX_ROUNDS = 3
    }
}
