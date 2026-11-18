package com.example.unifiedreader.feature.reader.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The look a book you haven't set opens with ([values], empty before the first save), once [isLoaded]. */
data class ReaderBookLook(
    val values: Map<String, String>,
    val isLoaded: Boolean,
)

/**
 * "Use these for new books" in the reading settings: the font, size and spacing a book you
 * haven't set opens with. A book you set keeps its own; that is the reader's session, not this.
 * The values are the reader's own setting keys, so this store doesn't need to know them.
 */
interface ReaderBookLookPreferencesStore {
    val lookFlow: StateFlow<ReaderBookLook>

    /** Saves the look in the background; [lookFlow] follows once it is written. */
    fun save(values: Map<String, String>)
}

class DataStoreReaderBookLookPreferencesStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.readerBookLookDataStore,
    /** The app scope; saves run on it so they outlive the reader. */
    private val scope: CoroutineScope,
    private val ioRetryDelayMillis: Long = DATASTORE_IO_RETRY_DELAY_MILLIS,
) : ReaderBookLookPreferencesStore {

    override val lookFlow: StateFlow<ReaderBookLook> = dataStore.data
        .retryWhen { error, _ ->
            if (error is IOException) {
                emit(emptyPreferences())
                delay(ioRetryDelayMillis)
            }
            error is IOException
        }
        .map { preferences -> ReaderBookLook(values = decodeBookLook(preferences[BookLookKey]), isLoaded = true) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = ReaderBookLook(values = emptyMap(), isLoaded = false),
        )

    override fun save(values: Map<String, String>) {
        scope.launch {
            dataStore.edit { preferences ->
                preferences[BookLookKey] = encodeBookLook(values)
            }
        }
    }
}

/** For previews and tests that do not persist the look. */
class InMemoryReaderBookLookPreferencesStore(
    values: Map<String, String> = emptyMap(),
) : ReaderBookLookPreferencesStore {
    private val lookState = MutableStateFlow(ReaderBookLook(values = values, isLoaded = true))
    override val lookFlow: StateFlow<ReaderBookLook> = lookState.asStateFlow()

    override fun save(values: Map<String, String>) {
        lookState.value = ReaderBookLook(values = values, isLoaded = true)
    }
}

/** One "key=value" per line; the reader's keys and values hold neither "=" nor line breaks. */
internal fun encodeBookLook(values: Map<String, String>): String =
    values.entries.joinToString("\n") { (key, value) -> "$key=$value" }

internal fun decodeBookLook(encoded: String?): Map<String, String> =
    encoded.orEmpty()
        .lineSequence()
        .mapNotNull { line ->
            val key = line.substringBefore('=', missingDelimiterValue = "").trim()
            val value = line.substringAfter('=', missingDelimiterValue = "").trim()
            (key to value).takeIf { key.isNotEmpty() && value.isNotEmpty() }
        }
        .toMap()

private val Context.readerBookLookDataStore: DataStore<Preferences> by preferencesDataStore(
    name = READER_BOOK_LOOK_DATASTORE_NAME,
)

private val BookLookKey = stringPreferencesKey("new_book_look")

internal const val READER_BOOK_LOOK_DATASTORE_NAME = "reader_book_look_preferences"
