package com.example.unifiedreader

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.unifiedreader.feature.annotations.LocalBookIdentity
import com.example.unifiedreader.feature.annotations.PendingLocalMarks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The books from files a restore had marks for but couldn't find on this device, with their files' fingerprints,
 * until those files are imported ([LocalMarksHoming]). Kept as a small JSON list in shared preferences.
 */
internal class PendingLocalMarksStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PendingLocalMarks {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun remember(books: List<LocalBookIdentity>) {
        if (books.isEmpty()) return
        update { waiting -> (waiting.filterNot { old -> books.any { it.itemId == old.itemId } } + books) to Unit }
    }

    override suspend fun takeSameFile(file: LocalBookIdentity): List<LocalBookIdentity> =
        update { waiting -> waiting.partition { it.sameFileAs(file) }.let { (same, others) -> others to same } }

    private suspend fun <T> update(change: (List<LocalBookIdentity>) -> Pair<List<LocalBookIdentity>, T>): T =
        mutex.withLock {
            withContext(ioDispatcher) {
                val (kept, result) = change(decodePendingBooks(preferences.getString(KEY_BOOKS, null)))
                preferences.edit { putString(KEY_BOOKS, encodePendingBooks(kept)) }
                result
            }
        }

    private companion object {
        const val PREFERENCES_NAME = "pending_local_marks"
        const val KEY_BOOKS = "books"
    }
}

internal fun encodePendingBooks(books: List<LocalBookIdentity>): String = JSONArray().apply {
    books.forEach { book ->
        put(
            JSONObject()
                .put("itemId", book.itemId)
                .put("title", book.title)
                .put("author", book.author ?: JSONObject.NULL)
                .put("format", book.format)
                .put("fileSize", book.fileSize ?: JSONObject.NULL)
                .put("partialMd5", book.partialMd5 ?: JSONObject.NULL),
        )
    }
}.toString()

internal fun decodePendingBooks(text: String?): List<LocalBookIdentity> {
    val array = text?.takeIf(String::isNotBlank)?.let { json -> runCatching { JSONArray(json) }.getOrNull() }
        ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val book = array.optJSONObject(index) ?: return@mapNotNull null
        LocalBookIdentity(
            itemId = book.optString("itemId").takeIf(String::isNotEmpty) ?: return@mapNotNull null,
            title = book.optString("title"),
            author = book.optStringOrNull("author"),
            format = book.optString("format"),
            fileSize = if (book.isNull("fileSize")) null else book.optLong("fileSize"),
            partialMd5 = book.optStringOrNull("partialMd5"),
        )
    }
}

private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).takeIf(String::isNotEmpty)
