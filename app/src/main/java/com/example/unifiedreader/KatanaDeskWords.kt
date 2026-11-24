package com.example.unifiedreader

import android.content.Context
import com.example.unifiedreader.core.database.UnifiedReaderDatabase
import com.example.unifiedreader.core.database.entity.SavedWordEntity
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * One saved word as this device and the desk compare it. Two words are the same word when their text (any case)
 * and their book (the desk's book id) are the same, whatever their ids; [look] is what a change changes.
 */
internal data class KatanaDeskWord(
    val word: String,
    val deskBookId: String,
    val sentence: String?,
    val note: String?,
    val chapter: String? = null,
    val fraction: Double? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val bookTitle: String? = null,
    val bookAuthor: String? = null,
) {
    val key: String get() = "${word.trim().lowercase()}|$deskBookId"
    val look: String get() = "${sentence.orEmpty().trim()}\u0001${note.orEmpty().trim()}"
}

/** What a words sync does: words to send and to take, removals both ways, and what both sides have after it. */
internal data class KatanaDeskWordPlan(
    val send: List<KatanaDeskWord>,
    val take: List<KatanaDeskWord>,
    val removeThere: List<KatanaDeskWord>,
    val removeHere: List<KatanaDeskWord>,
    val base: Map<String, String>,
)

/**
 * Decides a words sync from the words here, the desk's, and [base]: the words both had after the last sync (key to
 * look). A word in [base] missing on one side was removed there, so it goes on the other; a word not in [base] is
 * new and goes across. A word both have that differs: the side that changed it since the last sync wins, and when
 * both did (or it's the first sync), the newer change.
 */
internal fun planKatanaDeskWords(
    here: List<KatanaDeskWord>,
    desk: List<KatanaDeskWord>,
    base: Map<String, String>,
): KatanaDeskWordPlan {
    val mine = here.associateBy { it.key }
    val theirs = desk.associateBy { it.key }
    val planner = KatanaDeskWordPlanner(base)
    (mine.keys + theirs.keys).forEach { key -> planner.decide(key, mine[key], theirs[key]) }
    return planner.plan()
}

private class KatanaDeskWordPlanner(private val base: Map<String, String>) {
    private val send = mutableListOf<KatanaDeskWord>()
    private val take = mutableListOf<KatanaDeskWord>()
    private val removeThere = mutableListOf<KatanaDeskWord>()
    private val removeHere = mutableListOf<KatanaDeskWord>()
    private val after = mutableMapOf<String, String>()

    fun decide(key: String, mine: KatanaDeskWord?, theirs: KatanaDeskWord?) {
        val known = base[key]
        when {
            mine != null && theirs != null -> both(key, mine, theirs, known)
            mine != null && known != null -> removeHere += mine
            // Known to both only once the desk lists it: a desk that didn't take it never reads as a removal there.
            mine != null -> send += mine
            theirs != null && known != null -> removeThere += theirs
            theirs != null -> keep(key, theirs, take)
        }
    }

    private fun both(key: String, mine: KatanaDeskWord, theirs: KatanaDeskWord, known: String?) {
        val mineChanged = mine.look != known
        val theirsChanged = theirs.look != known
        when {
            mine.look == theirs.look -> after[key] = mine.look
            mineChanged && !theirsChanged -> keep(key, mine, send)
            theirsChanged && !mineChanged -> keep(key, theirs, take)
            mine.updatedAt > theirs.updatedAt -> keep(key, mine, send)
            else -> keep(key, theirs, take)
        }
    }

    private fun keep(key: String, word: KatanaDeskWord, into: MutableList<KatanaDeskWord>) {
        into += word
        after[key] = word.look
    }

    fun plan() = KatanaDeskWordPlan(send, take, removeThere, removeHere, after)
}

/**
 * A saved word here as the desk sees it. Its change time: when it changed since the last sync ([base] says how it
 * was), now; otherwise when it was saved (this device keeps no time of a change).
 */
internal fun SavedWordEntity.toDeskWord(deskBookId: String, base: Map<String, String>, now: Long): KatanaDeskWord {
    val word = KatanaDeskWord(
        word = word,
        deskBookId = deskBookId,
        sentence = sentence,
        note = note,
        chapter = chapterTitle,
        fraction = progression,
        createdAt = createdAtEpochMillis,
        updatedAt = createdAtEpochMillis,
    )
    val known = base[word.key]
    return if (known != null && known != word.look) word.copy(updatedAt = now) else word
}

/** The desk's word as a saved word here, in [itemId]: a change to [existing], or a new one. */
internal fun KatanaDeskWord.toSavedWord(existing: SavedWordEntity?, itemId: String): SavedWordEntity =
    existing?.copy(sentence = sentence, note = note, chapterTitle = chapter ?: existing.chapterTitle)
        ?: SavedWordEntity(
            id = UUID.randomUUID().toString(),
            itemId = itemId,
            bookTitle = bookTitle.orEmpty(),
            bookAuthor = bookAuthor,
            word = word.trim(),
            sentence = sentence,
            chapterTitle = chapter,
            locatorJson = null,
            progression = fraction,
            note = note,
            createdAtEpochMillis = createdAt.takeIf { it > 0 } ?: updatedAt,
        )

/** The desk's answer to `GET /katana-sync/v1/words`. */
internal fun parseKatanaDeskWords(body: String): List<KatanaDeskWord> {
    val rows = JSONObject(body).optJSONArray("words") ?: JSONArray()
    return (0 until rows.length()).map(rows::getJSONObject).mapNotNull { row ->
        val word = row.textOrNull("word") ?: return@mapNotNull null
        KatanaDeskWord(
            word = word,
            deskBookId = row.opt("bookId")?.toString().orEmpty(),
            sentence = row.textOrNull("sentence"),
            note = row.textOrNull("note"),
            chapter = row.textOrNull("chapter"),
            fraction = if (row.isNull("fraction")) null else row.optDouble("fraction"),
            createdAt = row.optLong("createdAt"),
            updatedAt = row.optLong("updatedAt"),
            bookTitle = row.textOrNull("bookTitle"),
            bookAuthor = row.textOrNull("bookAuthor"),
        )
    }
}

/** The body of `POST /katana-sync/v1/words`: words to save or change there, and words removed here. */
internal fun katanaDeskWordsBody(send: List<KatanaDeskWord>, removed: List<KatanaDeskWord>): String {
    fun JSONArray.add(w: KatanaDeskWord) = put(
        JSONObject()
            .put("word", w.word)
            .put("bookId", w.deskBookId.toLongOrNull() ?: 0L)
            .put("sentence", w.sentence ?: JSONObject.NULL)
            .put("note", w.note ?: JSONObject.NULL)
            .put("chapter", w.chapter ?: JSONObject.NULL)
            .put("fraction", w.fraction ?: JSONObject.NULL)
            .put("createdAt", w.createdAt)
            .put("updatedAt", w.updatedAt),
    )
    val words = JSONArray().also { array -> send.forEach { array.add(it) } }
    val gone = JSONArray().also { array -> removed.forEach { array.add(it) } }
    return JSONObject().put("words", words).put("removed", gone).toString()
}

private fun JSONObject.textOrNull(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

/**
 * My words both ways with Katana Desk, for the books both have: a word saved, changed or removed on one side is
 * the same on the other after the next sync. What both had after the last sync is kept per desk, which tells a
 * word removed on one side from a word new on the other. A desk older than this answers 404 and is left alone.
 */
@Singleton
class KatanaDeskWords @Inject constructor(
    @param:ApplicationContext context: Context,
    private val database: UnifiedReaderDatabase,
    httpClient: HttpClient,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = KatanaDeskWordsClient(httpClient)

    /** Syncs the words of the books in [links] (local book id to the desk's); how many words changed here or there. */
    internal suspend fun sync(desk: KatanaDeskPaired, links: Map<String, String>): Int = withContext(Dispatchers.IO) {
        val deskWords = client.words(desk) ?: return@withContext 0
        val dao = database.readerAnnotationDao()
        val base = readBase(desk.deskId)
        val now = System.currentTimeMillis()
        val here = dao.observeAllWords().first().mapNotNull { saved -> links[saved.itemId]?.let { saved to it } }
        val byKey = here.associate { (saved, book) -> saved.toDeskWord(book, base, now).let { it.key to (saved to it) } }
        val plan = planKatanaDeskWords(byKey.values.map { it.second }, deskWords, base)
        if (plan.send.isNotEmpty() || plan.removeThere.isNotEmpty()) client.send(desk, plan.send, plan.removeThere)
        val items = links.entries.associate { (item, book) -> book to item }
        plan.removeHere.forEach { word -> byKey[word.key]?.let { dao.deleteWord(it.first.id) } }
        plan.take.forEach { word ->
            items[word.deskBookId]?.let { itemId -> dao.upsertWord(word.toSavedWord(byKey[word.key]?.first, itemId)) }
        }
        writeBase(desk.deskId, plan.base)
        plan.send.size + plan.take.size + plan.removeHere.size + plan.removeThere.size
    }

    private fun readBase(deskId: String): Map<String, String> =
        prefs.getStringSet(baseKey(deskId), emptySet()).orEmpty()
            .mapNotNull { row -> row.split(SEPARATOR, limit = 2).takeIf { it.size == 2 } }
            .associate { (key, look) -> key to look }

    private fun writeBase(deskId: String, base: Map<String, String>) {
        prefs.edit().putStringSet(baseKey(deskId), base.map { (key, look) -> "$key$SEPARATOR$look" }.toSet()).apply()
    }

    private fun baseKey(deskId: String) = "base/$deskId"

    private companion object {
        const val PREFS = "katana_desk_words"
        const val SEPARATOR = "\u0002"
    }
}

/** Katana Desk's words requests. */
internal class KatanaDeskWordsClient(private val http: HttpClient) {
    /** The desk's words; null when the desk doesn't know the question (an older desk). */
    suspend fun words(desk: KatanaDeskPaired): List<KatanaDeskWord>? {
        val response = request { http.get("${desk.baseUrl}$WORDS_PATH") { bearerAuth(desk.token) } }
        return if (response.status.isSuccess()) parseKatanaDeskWords(response.bodyAsText()) else null
    }

    suspend fun send(desk: KatanaDeskPaired, words: List<KatanaDeskWord>, removed: List<KatanaDeskWord>) {
        val response = request {
            http.post("${desk.baseUrl}$WORDS_PATH") {
                bearerAuth(desk.token)
                contentType(ContentType.Application.Json)
                setBody(katanaDeskWordsBody(words, removed))
            }
        }
        if (!response.status.isSuccess()) throw KatanaDeskRefused("Katana Desk couldn't take the words (${response.status.value}).")
    }

    private suspend fun request(send: suspend () -> HttpResponse): HttpResponse =
        withTimeoutOrNull(TIMEOUT_MILLIS) {
            try {
                send()
            } catch (refused: ResponseException) {
                refused.response
            }
        } ?: throw KatanaDeskNoAnswer()

    private companion object {
        const val WORDS_PATH = "/katana-sync/v1/words"
        const val TIMEOUT_MILLIS = 10_000L
    }
}
