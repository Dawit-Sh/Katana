package com.example.unifiedreader

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import com.example.unifiedreader.ui.components.AppDialog
import com.example.unifiedreader.ui.components.AppDialogConfirmButton
import com.example.unifiedreader.ui.components.AppDialogDismissButton
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** More books than this moving one way at once are asked about first (his call, 2026-11-24). */
const val KATANA_DESK_ASK_OVER = 5

/**
 * The question before many books move: "Your PC is reading 7 books that aren't here. Bring all 7?" ([toThisDevice])
 * or "This device is reading 7 books that aren't on your PC. Send all 7?".
 */
data class KatanaDeskQuestion(val toThisDevice: Boolean, val ids: List<String>) {
    val count: Int get() = ids.size
    val text: String
        get() = if (toThisDevice) {
            "Your PC is reading $count books that aren't here. Bring all $count?"
        } else {
            "This device is reading $count books that aren't on your PC. Send all $count?"
        }
}

/** The question on this device; "Not now" lets those books be (it isn't asked again for them). */
@Composable
internal fun KatanaDeskQuestionDialog(question: KatanaDeskQuestion, onAnswer: (Boolean) -> Unit) {
    val verb = if (question.toThisDevice) "Bring" else "Send"
    AppDialog(
        onDismissRequest = { onAnswer(false) },
        confirmButton = { AppDialogConfirmButton(text = "$verb all ${question.count}", onClick = { onAnswer(true) }) },
        dismissButton = { AppDialogDismissButton(text = "Not now", onClick = { onAnswer(false) }) },
        title = { Text("Katana Desk") },
        text = { Text(question.text) },
    )
}

/** Whether [count] books move now: up to [KATANA_DESK_ASK_OVER] just move; more, as [answer] says (null: ask). */
internal fun katanaDeskMovesNow(count: Int, answer: Boolean?): Boolean? = if (count <= KATANA_DESK_ASK_OVER) true else answer

/**
 * Which books have moved (or were let be) each way, so each moves once, and the answers to the question, so it
 * doesn't come back at every sync. One set per desk: pairing again starts afresh.
 */
class KatanaDeskMoves(private val prefs: SharedPreferences, private val onAnswered: () -> Unit) {
    private val questionState = MutableStateFlow<KatanaDeskQuestion?>(null)

    /** The question waiting for an answer on this device, if any. */
    val question: StateFlow<KatanaDeskQuestion?> = questionState.asStateFlow()

    fun settled(toThisDevice: Boolean): Set<String> = prefs.getStringSet(setKey(toThisDevice), emptySet()).orEmpty()

    fun settle(toThisDevice: Boolean, ids: Collection<String>) {
        if (ids.isEmpty()) return
        prefs.edit().putStringSet(setKey(toThisDevice), settled(toThisDevice) + ids).apply()
    }

    /** Whether the books [ids] (not settled) move now; more than five wait for the question's answer. */
    fun decide(toThisDevice: Boolean, ids: List<String>): Boolean {
        val key = answerKey(toThisDevice, ids)
        val answer = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
        val now = katanaDeskMovesNow(ids.size, answer)
        if (now == null && questionState.value == null) questionState.value = KatanaDeskQuestion(toThisDevice, ids)
        return now == true
    }

    /** The answer to [question]: yes moves them at the next sync (now); no lets them be for good. */
    fun answer(yes: Boolean) {
        val asked = questionState.value ?: return
        prefs.edit().putBoolean(answerKey(asked.toThisDevice, asked.ids), yes).apply()
        if (!yes) settle(asked.toThisDevice, asked.ids)
        questionState.value = null
        onAnswered()
    }

    /** One more failed try at bringing [id]; how many there have been. */
    fun tried(id: String): Int {
        val tries = prefs.getInt("tries:$id", 0) + 1
        prefs.edit().putInt("tries:$id", tries).apply()
        return tries
    }

    fun clear() {
        prefs.edit().clear().apply()
        questionState.value = null
    }

    private fun setKey(toThisDevice: Boolean) = if (toThisDevice) "settled_here" else "settled_sent"

    private fun answerKey(toThisDevice: Boolean, ids: List<String>) =
        "answer:${if (toThisDevice) "here" else "sent"}:${ids.sorted().joinToString(",").hashCode()}"
}

/** Every book the PC is reading (`/katana-sync/v1/reading-list`); an older desk: the one `/reading` gives. */
internal suspend fun katanaDeskReadingList(http: HttpClient, desk: KatanaDeskPaired, one: suspend () -> KatanaDeskReading?) =
    runSuspendCatchingList {
        val response = http.get("${desk.baseUrl}/katana-sync/v1/reading-list") { bearerAuth(desk.token) }
        if (!response.status.isSuccess()) return@runSuspendCatchingList null
        val books = JSONObject(response.bodyAsText()).optJSONArray("books") ?: return@runSuspendCatchingList emptyList()
        (0 until books.length()).mapNotNull { i ->
            parseKatanaDeskReading(JSONObject().put("book", books.getJSONObject(i)).toString())
        }
    } ?: listOfNotNull(one())

private suspend fun <T> runSuspendCatchingList(block: suspend () -> List<T>?): List<T>? = try {
    block()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
    failure.rethrowIfServerAddressUnreachable()
    null
}

/**
 * The books this device is reading that the PC hasn't (his ask: "Book files should also work from phone to PC"):
 * each goes up with `PUT /katana-sync/v1/books?name=<file name>` into "From <device>" on the PC, a Local folder there.
 * The next sync finds it by its file and links it. Only books started, not finished and read in the last 60 days go.
 */
internal class KatanaDeskBookSender(
    private val context: Context,
    private val http: HttpClient,
    private val documents: LocalDocumentDataSource,
    private val places: ReaderSessionStore,
    private val moves: KatanaDeskMoves,
) {
    /** Sends the books of [unlinked] being read here; how many went. */
    suspend fun send(desk: KatanaDeskPaired, unlinked: List<ReadableItem>, now: Long = System.currentTimeMillis()): Int {
        val sent = moves.settled(toThisDevice = false)
        val reading = unlinked.filter { it.id !in sent && isBeingRead(it, now) }
        if (reading.isEmpty() || !moves.decide(toThisDevice = false, reading.map { it.id })) return 0
        return reading.count { item ->
            val went = runSuspendCatchingList { listOfNotNull(sendOne(desk, item)) }.orEmpty().isNotEmpty()
            if (went) moves.settle(toThisDevice = false, listOf(item.id))
            went
        }
    }

    private fun isBeingRead(item: ReadableItem, now: Long): Boolean {
        val place = places.progressByItemIdFlow.value[item.id]
        val percent = place?.percent ?: 0.0
        return place != null && !place.isFinished && percent > STARTED && percent < FINISHED &&
            now - place.updatedAtEpochMillis < RECENT_MILLIS
    }

    private suspend fun sendOne(desk: KatanaDeskPaired, item: ReadableItem): Unit? {
        val uri = documents.getDocumentUri(item) ?: return null
        val size = sizeOf(uri)
        val name = fileNameOf(uri) ?: "${item.title}.${item.format.name.lowercase()}"
        val response = http.put("${desk.baseUrl}/katana-sync/v1/books") {
            bearerAuth(desk.token)
            parameter("name", name)
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentLength: Long? = size
                override val contentType: ContentType = ContentType.Application.OctetStream
                override fun readFrom(): ByteReadChannel =
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "Can't read $uri" }.toByteReadChannel()
            })
        }
        if (!response.status.isSuccess()) Log.w(LOG_TAG, "Sending ${item.id} to the PC: ${response.status}")
        return Unit.takeIf { response.status.isSuccess() }
    }

    private fun sizeOf(uri: Uri): Long? = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { len -> len > 0 } }
    }.getOrNull()

    private fun fileNameOf(uri: Uri): String? = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { '.' in it }

    private companion object {
        const val LOG_TAG = "KatanaDesk"
        const val STARTED = 0.004
        const val FINISHED = 0.995
        const val RECENT_MILLIS = 60L * 24 * 60 * 60 * 1000
    }
}
