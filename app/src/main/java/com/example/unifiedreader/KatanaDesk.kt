package com.example.unifiedreader

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.unifiedreader.core.common.ApplicationScope
import com.example.unifiedreader.core.database.dao.ReaderMarkServerMoveDao
import com.example.unifiedreader.core.model.FormatType
import com.example.unifiedreader.core.model.ReadStatus
import com.example.unifiedreader.core.model.ReadStatusStore
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.primaryAuthor
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.core.model.toServerEpochMillisOrNull
import com.example.unifiedreader.data.grimmory.GrimmoryAnnotation
import com.example.unifiedreader.data.grimmory.GrimmoryApi
import com.example.unifiedreader.data.grimmory.GrimmoryBookCatalogEntry
import com.example.unifiedreader.data.grimmory.KtorGrimmoryApi
import com.example.unifiedreader.data.repository.ReadableItemRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Katana Desk this device is paired with: where it is, and this device's pass for it. */
data class KatanaDeskPaired(val address: String, val token: String, val deskId: String, val name: String) {
    val baseUrl: String get() = deskBaseUrl(address)
}

/** A desk found on this Wi-Fi, or answering at an address typed in. [pairing]: a code is showing on its screen. */
data class KatanaDeskFound(val name: String, val address: String, val deskId: String = "", val pairing: Boolean = false)

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
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_TOKEN).remove(KEY_NAME).remove(KEY_BRING).remove(KEY_BRING_TRIES).apply()
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

    /** The next sync brings the book being read on the PC (set by pairing; kept until it came, or was given up). */
    var bringReadingBook: Boolean
        get() = prefs.getBoolean(KEY_BRING, false)
        set(value) = prefs.edit().putBoolean(KEY_BRING, value).apply()

    /** How many syncs have tried to bring it and failed. */
    var bringTries: Int
        get() = prefs.getInt(KEY_BRING_TRIES, 0)
        set(value) = prefs.edit().putInt(KEY_BRING_TRIES, value).apply()

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
        const val KEY_BRING = "bring_reading_book"
        const val KEY_BRING_TRIES = "bring_tries"
    }
}

/** Why pairing didn't go through, so the screen can say what to do next. */
enum class KatanaDeskPairRefusal {
    /** The code on the PC ran out: a new one is needed. */
    CodeRanOut,

    /** Not the code on the PC's screen. */
    WrongCode,

    /** The PC isn't showing a code (pairing closed there, or the code already paired a device). */
    NoCode,
    TooManyTries,

    /** Katana Desk didn't answer (closed, sync turned off, or this device left the Wi-Fi). */
    NoAnswer,
    Other,
    ;

    /** Whether the code typed is no use any more: the field empties for the next one. */
    val needsNewCode: Boolean get() = this == CodeRanOut || this == WrongCode || this == NoCode
}

/** What the desk said no to, in words to show. */
class KatanaDeskRefused(message: String, val refusal: KatanaDeskPairRefusal = KatanaDeskPairRefusal.Other) :
    RuntimeException(message)

/** Katana Desk didn't answer in time (a request's own limit, not a cancelled sync). */
internal class KatanaDeskNoAnswer : java.net.SocketTimeoutException("Katana Desk didn't answer in time.")

/** The refusal a pairing answer with [status] and [body] means (an older desk sends no reason: 403 is a code no good). */
internal fun pairRefusalOf(status: Int, body: String?): KatanaDeskRefused? {
    if (status in HTTP_SUCCESS) return null
    val reason = body?.let { runCatching { JSONObject(it).optString("reason") }.getOrNull() }.orEmpty()
    val refusal = refusalOf(status, reason)
    return KatanaDeskRefused(refusalWords(refusal, saidWrong = reason == "wrong", status = status), refusal)
}

private fun refusalOf(status: Int, reason: String): KatanaDeskPairRefusal = when {
    status == HTTP_TOO_MANY -> KatanaDeskPairRefusal.TooManyTries
    reason == "expired" -> KatanaDeskPairRefusal.CodeRanOut
    reason == "closed" -> KatanaDeskPairRefusal.NoCode
    reason == "wrong" || status == HTTP_FORBIDDEN -> KatanaDeskPairRefusal.WrongCode
    else -> KatanaDeskPairRefusal.Other
}

// [saidWrong]: the desk said the code is wrong (an older desk says only that it's no good: wrong or ran out).
private fun refusalWords(refusal: KatanaDeskPairRefusal, saidWrong: Boolean, status: Int): String = when (refusal) {
    KatanaDeskPairRefusal.CodeRanOut -> "That code ran out. On the PC, press Pair a device for a new one, then type it here."
    KatanaDeskPairRefusal.NoCode -> "The PC isn't showing a code now. In Katana Desk: Settings › Sync and backup › Pair a device."
    KatanaDeskPairRefusal.WrongCode -> if (saidWrong) {
        "That isn't the code on the PC. Check it and type it again."
    } else {
        "That code isn't right, or it ran out. Check the code on the PC."
    }
    KatanaDeskPairRefusal.TooManyTries -> "Too many tries. Wait a minute, then try again."
    else -> "Katana Desk said no ($status)."
}

/** What a pairing that found no desk at [address] says. */
internal fun noAnswerRefusal(address: String) = KatanaDeskRefused(
    "Katana Desk didn't answer at ${address.normalizedDeskAddress()}. Is it open on the PC, with Sync on this Wi-Fi on, " +
        "and this device on the same Wi-Fi?",
    KatanaDeskPairRefusal.NoAnswer,
)

/** A book here as the desk is asked about it: the same file is the same format, size and partial MD5. */
internal data class KatanaDeskBookAsk(val itemId: String, val fingerprint: LocalFileFingerprint, val format: String)

/** The book being read on the PC (open there now, or read there last), as the desk tells it. */
internal data class KatanaDeskReading(
    val bookId: String,
    val title: String,
    val author: String?,
    /** The desk's format word ("epub", "pdf", "cbz"...) */
    val formatName: String,
    val sizeBytes: Long?,
    val partialMd5: String?,
    val fileName: String?,
    /** Its id on Grimmory, when the desk's copy came from there */
    val grimmoryId: String?,
) {
    val format: FormatType? get() = FormatType.entries.firstOrNull { it.name.equals(formatName, ignoreCase = true) }
}

/** The desk's `/katana-sync/v1/reading` answer read; null when nothing is being read there. */
internal fun parseKatanaDeskReading(body: String): KatanaDeskReading? = runCatching {
    val book = JSONObject(body).optJSONObject("book") ?: return@runCatching null
    val id = book.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return@runCatching null
    KatanaDeskReading(
        bookId = id,
        title = book.optString("title").ifBlank { "A book" },
        author = book.optString("author").takeIf { it.isNotBlank() && it != "null" },
        formatName = book.optString("format").lowercase(),
        sizeBytes = book.optLong("size", -1L).takeIf { it >= 0 },
        partialMd5 = book.optString("md5").takeIf { it.isNotBlank() && it != "null" },
        fileName = book.optString("fileName").takeIf { it.isNotBlank() && it != "null" },
        grimmoryId = book.opt("grimmoryId")?.toString()?.takeIf { it.isNotBlank() && it != "null" },
    )
}.getOrNull()

/** Katana Desk's own requests: hello, pair, which books it has and which it's reading. The rest is Grimmory's API. */
internal class KatanaDeskClient(
    private val http: HttpClient,
    /** How long a request may take before the desk counts as not answering. */
    private val requestTimeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
) {
    suspend fun hello(address: String): KatanaDeskFound? = runSuspendCatching {
        val response = request { http.get("${deskBaseUrl(address)}$KATANA_DESK_HELLO_PATH") }
        if (!response.status.isSuccess()) return@runSuspendCatching null
        parseKatanaDeskHello(response.bodyAsText())?.let { hello ->
            KatanaDeskFound(name = hello.name, address = address.normalizedDeskAddress(), deskId = hello.deskId, pairing = hello.pairing)
        }
    }.getOrNull()

    suspend fun pair(address: String, code: String, deviceId: String): KatanaDeskPaired {
        val body = JSONObject()
            .put("code", code.filter(Char::isDigit))
            .put("deviceId", deviceId)
            .put("deviceName", deviceName())
        val (status, text) = postPair(address, body.toString())
        pairRefusalOf(status, text)?.let { throw it }
        val answer = JSONObject(text.orEmpty())
        return KatanaDeskPaired(
            address = address.normalizedDeskAddress(),
            token = answer.getString("token"),
            deskId = answer.optString("deskId"),
            name = answer.optString("name").ifBlank { "Katana Desk" },
        )
    }

    // The pairing answer's status and words; no desk there any more, or one that went away mid-pairing, is
    // [noAnswerRefusal].
    private suspend fun postPair(address: String, body: String): Pair<Int, String?> {
        val response = runSuspendCatching {
            request {
                http.post("${deskBaseUrl(address)}/katana-sync/v1/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        }.getOrElse { unreachable -> throw noAnswerRefusal(address).apply { initCause(unreachable) } }
        return response.status.value to runSuspendCatching { response.bodyAsText() }.getOrNull()
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

    /** The book being read on the PC; null when nothing is (or the desk is older than this question). */
    suspend fun reading(desk: KatanaDeskPaired): KatanaDeskReading? {
        val response = request {
            http.get("${desk.baseUrl}/katana-sync/v1/reading") { bearerAuth(desk.token) }
        }
        if (response.status.value == UNAUTHORIZED) throw KatanaDeskRefused("Katana Desk forgot this device. Pair it again.")
        if (!response.status.isSuccess()) return null
        return parseKatanaDeskReading(response.bodyAsText())
    }

    /**
     * Tells the desk this device's sync starts or ended ([end]: how it went), so the PC can show it too. Best
     * effort: an older desk doesn't know the question, and a desk out of reach is no reason to fail a sync.
     */
    suspend fun tell(desk: KatanaDeskPaired, end: KatanaDeskSyncResult?) {
        val body = JSONObject().put("phase", if (end == null) "start" else "end")
        end?.let { result ->
            body.put("books", result.books).put("changed", result.changed)
            result.problem?.let { body.put("problem", it) }
        }
        runSuspendCatching {
            request(TELL_TIMEOUT_MILLIS) {
                http.post("${desk.baseUrl}/katana-sync/v1/sync") {
                    bearerAuth(desk.token)
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
        }
    }

    // A refused request answers with its status here, whether or not the client throws on one. A request past its
    // time fails like a lost connection, never as a cancellation (that would end the sync's own coroutine).
    private suspend fun request(limitMillis: Long = requestTimeoutMillis, send: suspend () -> HttpResponse): HttpResponse =
        withTimeoutOrNull(limitMillis) {
            try {
                send()
            } catch (refused: ResponseException) {
                refused.response
            }
        } ?: throw KatanaDeskNoAnswer()

    private companion object {
        const val UNAUTHORIZED = 401
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val TELL_TIMEOUT_MILLIS = 3_000L
    }
}

private val HTTP_SUCCESS = 200..299
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY = 429

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
 * included. A book linked to Grimmory syncs there, not here. The first sync after pairing brings the book being
 * read on the PC ([KatanaDeskBookBringer]). Each sync's start and end go out as [events] (and to the desk).
 */
@Singleton
class KatanaDeskSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val connection: KatanaDeskConnection,
    private val httpClient: HttpClient,
    private val localDocuments: LocalDocumentDataSource,
    private val grimmoryCopies: dagger.Lazy<GrimmoryLocalCopies>,
    private val catalog: dagger.Lazy<ReadableItemRepository>,
    highlightStore: HighlightSyncStore,
    bookmarkStore: BookmarkSyncStore,
    private val markServerMove: ReaderMarkServerMoveDao,
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
    private val bringer = KatanaDeskBookBringer(
        context = context,
        httpClient = httpClient,
        documents = localDocuments,
        fingerprints = fingerprints,
        onGrimmoryHere = { grimmoryId ->
            withContext(Dispatchers.Default) {
                catalog.get().getItems().any { it.sourceType == SourceType.GRIMMORY && it.remoteId == grimmoryId }
            }
        },
        syncsWithGrimmory = { item -> grimmoryCopies.get().isLinked(item) },
    )
    private val highlightWaiting = highlightStore.observeWaiting()
    private val bookmarkWaiting = bookmarkStore.observeWaiting()
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val syncingState = MutableStateFlow(false)
    private val problemState = MutableStateFlow<String?>(null)
    private val lastResultState = MutableStateFlow<KatanaDeskSyncResult?>(null)
    private val eventsFlow = MutableSharedFlow<KatanaDeskSyncEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val syncing: StateFlow<Boolean> = syncingState.asStateFlow()

    /** Each sync's start and end (with what it did), for a sign on screen. */
    val events: SharedFlow<KatanaDeskSyncEvent> = eventsFlow.asSharedFlow()

    /** How the last sync went (null before the first since the app started). */
    val lastResult: StateFlow<KatanaDeskSyncResult?> = lastResultState.asStateFlow()

    private val placesChangedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A sync changed the place of a book here: the library's shelves and cards follow. */
    val placesChanged: SharedFlow<Unit> = placesChangedFlow.asSharedFlow()

    private fun linkedPlaces(books: List<ReadableItem>) =
        readerSessionStore.progressByItemIdFlow.value.let { places -> books.associate { it.id to places[it.id] } }

    /** Why the last sync didn't finish, in words to show; null when it did. */
    val problem: StateFlow<String?> = problemState.asStateFlow()

    /** Whether [item]'s marks sync with the desk (its highlights get places to send, like Grimmory's). */
    fun isLinked(item: ReadableItem): Boolean = connection.isLinked(item.id)

    /**
     * Pairs with the desk at [address] with the [code] it shows, then syncs (the first sync brings the book being
     * read on the PC). Refusals come as [KatanaDeskRefused], with what went wrong as [KatanaDeskRefused.refusal].
     */
    suspend fun pair(address: String, code: String): KatanaDeskPaired {
        val paired = client.pair(address, code, connection.deviceId)
        // Another desk than the one the books were linked with: its book ids mean other books, and the marks
        // carry the first desk's ids, which this one would read as removed: they go up to it as new instead.
        if (connection.linkedDeskId != null && connection.linkedDeskId != paired.deskId) {
            mutex.withLock {
                withContext(Dispatchers.IO) { markServerMove.moveToNewServer(connection.links.value.keys.toList()) }
                connection.saveLinks(emptyMap())
            }
        }
        connection.save(paired)
        connection.bringReadingBook = true
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

    /** A book opened here: when it's on the desk, the desk's newest place and marks come first (as with Grimmory). */
    fun onBooksOpened(itemIds: Collection<String>) {
        if (itemIds.any(connection::isLinked)) syncSoon()
    }

    /** Syncs now; how many books are on the desk, or null when it couldn't. */
    suspend fun syncNow(): Int? = mutex.withLock {
        val desk = connection.paired.value ?: return@withLock null
        val since = System.currentTimeMillis()
        val tally = KatanaDeskTally()
        val outcome = KatanaDeskOutcome()
        syncingState.value = true
        eventsFlow.tryEmit(KatanaDeskSyncEvent.Started(since, desk.name))
        Log.i(LOG_TAG, "Sync with ${desk.name} started.")
        try {
            client.tell(desk, end = null)
            val result = runSuspendCatching { syncWith(desk, tally, outcome) }
            val problem = result.exceptionOrNull()?.let { failure ->
                if (failure !is KatanaDeskRefused) Log.w(LOG_TAG, "Sync with Katana Desk failed.", failure)
                (failure as? KatanaDeskRefused)?.message
                    ?: "Can't reach Katana Desk. Both on the same Wi-Fi, and Sync on this Wi-Fi on in its Settings?"
            }
            val summary = tally.result(outcome.books, outcome.placesHere, outcome.brought, problem)
            if (problem == null) connection.markSynced(System.currentTimeMillis())
            problemState.value = problem
            lastResultState.value = summary
            if (problem == null || outcome.reached) client.tell(desk, end = summary)
            eventsFlow.tryEmit(KatanaDeskSyncEvent.Ended(System.currentTimeMillis(), desk.name, since, summary))
            Log.i(LOG_TAG, "Sync with ${desk.name} ended: $summary")
            result.getOrNull()
        } finally {
            syncingState.value = false
        }
    }

    private suspend fun syncWith(desk: KatanaDeskPaired, tally: KatanaDeskTally, outcome: KatanaDeskOutcome): Int {
        val api = KtorGrimmoryApi(httpClient = httpClient, baseUrl = desk.baseUrl)
        // The first sync after pairing: the book being read on the PC comes here (downloaded when it isn't).
        if (connection.bringReadingBook) outcome.brought = bringReadingBook(desk)
        val grimmory = grimmoryCopies.get()
        val known = connection.links.value
        // A book here once linked to the desk stays with it; one linked to Grimmory syncs there.
        val books = localDocuments.getImportedItems().filter { item ->
            item.format in KATANA_DESK_FORMATS && (item.id in known || !grimmory.isLinked(item))
        }
        val asks = books.filter { it.id !in known }.mapNotNull { item ->
            val uri = localDocuments.getDocumentUri(item) ?: return@mapNotNull null
            fingerprints.of(uri.toString())?.let { file -> KatanaDeskBookAsk(item.id, file, item.format.name) }
        }
        val links = known + client.match(desk, asks)
        outcome.reached = true
        if (links != known) connection.saveLinks(links)
        val linked = books.filter { it.id in links }
        outcome.books = linked.size
        val placesBefore = linkedPlaces(linked)
        var firstFailure: Throwable? = null
        linked.forEach { item ->
            runSuspendCatching { bookSync.sync(api, desk.token, item, links.getValue(item.id), tally) }.onFailure { failure ->
                failure.rethrowIfServerAddressUnreachable()
                Log.w(LOG_TAG, "Katana Desk sync of ${item.id} failed.", failure)
                if (firstFailure == null) firstFailure = failure
            }
        }
        // A place the desk changed here (another device read on, or marked it finished or unread) shows at once.
        val placesAfter = linkedPlaces(linked)
        outcome.placesHere = placesAfter.count { (itemId, place) -> place.movedFrom(placesBefore[itemId]) }
        if (placesAfter != placesBefore) placesChangedFlow.tryEmit(Unit)
        firstFailure?.let { throw it }
        return linked.size
    }

    // The book being read on the PC. No desk to reach: the next sync asks again. Any other failure (a download
    // that broke off, a file that came damaged) doesn't hold up the rest of the sync, and is tried again a few
    // times; then it's left.
    private suspend fun bringReadingBook(desk: KatanaDeskPaired): KatanaDeskBringing {
        var title: String? = null
        val brought = runSuspendCatching {
            val reading = client.reading(desk) ?: return@runSuspendCatching KatanaDeskBringing.NothingRead
            title = reading.title
            bringer.bring(desk, reading, connection.links.value) { itemId, deskBookId ->
                connection.saveLinks(connection.links.value + (itemId to deskBookId))
            }
        }.getOrElse { failure ->
            failure.rethrowIfServerAddressUnreachable()
            if (failure is KatanaDeskRefused) throw failure
            Log.w(LOG_TAG, "Bringing the PC's book failed.", failure)
            val tries = connection.bringTries + 1
            connection.bringTries = tries
            KatanaDeskBringing.Failed(
                title = title,
                problem = failure.message ?: "The book didn't come from the PC.",
                triesAgain = tries < KATANA_DESK_BRING_TRIES,
            )
        }
        if (brought !is KatanaDeskBringing.Failed || !brought.triesAgain) {
            connection.bringReadingBook = false
            connection.bringTries = 0
        }
        return brought
    }

    private companion object {
        const val LOG_TAG = "KatanaDesk"
        const val SOON_MILLIS = 1_500L
        const val PLACE_SETTLE_MILLIS = 5_000L
        const val EVERY_MILLIS = 2 * 60_000L
    }
}

private fun ReadableItem.annotatedBook() = AnnotatedBook(itemId = id, title = title, author = bookMetadata?.primaryAuthor())

/** Whether this place is somewhere else than [before] (a new time or sync state for the same place isn't a move). */
internal fun ReaderProgressSnapshot?.movedFrom(before: ReaderProgressSnapshot?): Boolean {
    if (this == null || before == null) return this != before
    return locatorType != before.locatorType || page != before.page || cfi != before.cfi ||
        isFinished != before.isFinished || isExplicitUnreadReset != before.isExplicitUnreadReset ||
        kotlin.math.abs((percent ?: 0.0) - (before.percent ?: 0.0)) > SAME_PLACE_PERCENT
}

// Two places this close (a hundredth of a percent) are one place said twice.
private const val SAME_PLACE_PERCENT = 0.0001

/** What a sync found out along the way (kept even when it fails part way). */
private class KatanaDeskOutcome {
    var books = 0
    var placesHere = 0
    var brought: KatanaDeskBringing? = null

    /** Whether the desk answered at all (then it hears how the sync ended). */
    var reached = false
}

/** One book's place, reading status, highlights and bookmarks with the desk, by Grimmory's rules and engines. */
private class KatanaDeskBookSync(
    private val highlights: HighlightSyncEngine,
    private val bookmarks: BookmarkSyncEngine,
    private val readerSessionStore: ReaderSessionStore,
    private val statuses: ReadStatusStore,
) {
    private val progressStore = ReaderSessionProgressStoreAdapter(readerSessionStore)

    suspend fun sync(api: GrimmoryApi, token: String, item: ReadableItem, bookId: String, tally: KatanaDeskTally) {
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
            placeHere?.let { place -> if (pushPlace(api, token, item, place, deskBook)) tally.placesSent++ }
        }
        if (pushStatus(api, token, item, bookId)) tally.statusesSent++
        if (item.format == FormatType.EPUB) syncHighlights(api, token, item.annotatedBook(), bookId, tally)
        syncBookmarks(api, token, item, bookId, tally)
    }

    // This device's place, when it stayed and goes up: not sent yet (as Grimmory's queued saves) or newer.
    private fun placeToSend(item: ReadableItem, deskTime: Long?): ReaderProgressSnapshot? =
        readerSessionStore.progressByItemIdFlow.value[item.id]?.takeIf { place ->
            val unsent = place.syncState != SyncState.CLEAN
            unsent || !place.isExplicitUnreadReset && (deskTime == null || place.updatedAtEpochMillis > deskTime)
        }

    // [deskBook] is the desk's answer for the book, under the desk's book id. True when the place went up.
    private suspend fun pushPlace(
        api: GrimmoryApi,
        token: String,
        item: ReadableItem,
        here: ReaderProgressSnapshot,
        deskBook: GrimmoryBookCatalogEntry,
    ): Boolean {
        val bookId = deskBook.item.remoteId
        val update = if (bookId == null || here.isExplicitUnreadReset) {
            null
        } else {
            val pageCount = readerSessionStore.getSession(item)?.pageCount ?: item.pageCount
            here.toGrimmoryProgressUpdate(item = item.copy(remoteId = bookId), pageCount = pageCount)
        }
        val sent = when {
            bookId == null -> false
            // Marked unread here: the desk forgets its place too, as Grimmory does.
            here.isExplicitUnreadReset -> true.also { api.resetBookProgress(accessToken = token, bookId = bookId) }
            update == null -> false
            else -> true.also {
                // Read again here, finished there: the desk's finish goes (its Read becomes Reading), as the desk asks.
                val deskFinished = deskBook.dateFinished != null || deskBook.progress?.isFinished == true
                api.updateProgress(accessToken = token, progress = update.copy(clearsFinishedState = deskFinished && !here.isFinished))
            }
        }
        // Sent: no longer a save waiting to go up, so a newer place on the desk wins without Grimmory's margin.
        if (sent) readerSessionStore.replaceProgressIfCurrent(expected = here, replacement = here.copy(syncState = SyncState.CLEAN))
        return sent
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
    private suspend fun pushStatus(api: GrimmoryApi, token: String, item: ReadableItem, bookId: String): Boolean {
        val record = statuses.get(item.id)?.takeIf { it.unsent } ?: return false
        val answer = api.setReadStatus(accessToken = token, bookId = bookId, status = record.status.serverName)
        val kept = ReadStatus.fromServer(answer.status) ?: record.status
        statuses.replaceIfCurrent(item.id, record, record.copy(status = kept, unsent = false))
        return true
    }

    private suspend fun syncHighlights(api: GrimmoryApi, token: String, book: AnnotatedBook, bookId: String, tally: KatanaDeskTally) {
        val server = CountingHighlightServer(GrimmoryHighlightServer(api.annotations, token, bookId), tally)
        repeat(MAX_ROUNDS) {
            val list = api.annotations.bookHighlights(token, bookId).map(GrimmoryAnnotation::toRemoteHighlight)
            if (highlights.syncBook(book, list, server) == HighlightBookSync.Done) return
        }
    }

    private suspend fun syncBookmarks(api: GrimmoryApi, token: String, item: ReadableItem, bookId: String, tally: KatanaDeskTally) {
        val book = item.annotatedBook()
        val marks = api.annotations.bookmarks
        val server = CountingBookmarkServer(GrimmoryBookmarkServer(marks, token, bookId, item.format), tally)
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
