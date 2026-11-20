package com.example.unifiedreader

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.unifiedreader.feature.annotations.LocalBookIdentity
import com.example.unifiedreader.feature.annotations.LocalReadingPlace
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

    override suspend fun rememberPlaces(places: List<LocalReadingPlace>) {
        if (places.isEmpty()) return
        updatePlaces { waiting -> (waiting.filterNot { old -> places.any { it.itemId == old.itemId } } + places) to Unit }
    }

    override suspend fun takePlaces(itemIds: Set<String>): List<LocalReadingPlace> =
        updatePlaces { waiting -> waiting.partition { it.itemId in itemIds }.let { (taken, others) -> others to taken } }

    private suspend fun <T> updatePlaces(change: (List<LocalReadingPlace>) -> Pair<List<LocalReadingPlace>, T>): T =
        mutex.withLock {
            withContext(ioDispatcher) {
                val (kept, result) = change(decodePendingPlaces(preferences.getString(KEY_PLACES, null)))
                preferences.edit { putString(KEY_PLACES, encodePendingPlaces(kept)) }
                result
            }
        }

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
        const val KEY_PLACES = "places"
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

internal fun encodePendingPlaces(places: List<LocalReadingPlace>): String = JSONArray().apply {
    places.forEach { place ->
        put(
            JSONObject()
                .put("itemId", place.itemId)
                .put("locatorType", place.locatorType)
                .put("page", place.page ?: JSONObject.NULL)
                .put("cfi", place.cfi ?: JSONObject.NULL)
                .put("percent", place.percent ?: JSONObject.NULL)
                .put("isFinished", place.isFinished)
                .put("updatedAt", place.updatedAtEpochMillis),
        )
    }
}.toString()

internal fun decodePendingPlaces(text: String?): List<LocalReadingPlace> {
    val array = text?.takeIf(String::isNotBlank)?.let { json -> runCatching { JSONArray(json) }.getOrNull() }
        ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val place = array.optJSONObject(index) ?: return@mapNotNull null
        LocalReadingPlace(
            itemId = place.optString("itemId").takeIf(String::isNotEmpty) ?: return@mapNotNull null,
            locatorType = place.optString("locatorType").takeIf(String::isNotEmpty) ?: return@mapNotNull null,
            page = if (place.isNull("page")) null else place.optInt("page"),
            cfi = place.optStringOrNull("cfi"),
            percent = if (place.isNull("percent")) null else place.optDouble("percent"),
            isFinished = place.optBoolean("isFinished"),
            updatedAtEpochMillis = place.optLong("updatedAt"),
        )
    }
}

private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).takeIf(String::isNotEmpty)
