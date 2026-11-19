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

/**
 * The "Pages" choice last made in any PDF or DjVu book ([pageImages], null before the first pick),
 * once [isLoaded].
 */
data class ReaderPagesPick(
    val pageImages: ReaderPagesMode?,
    val isLoaded: Boolean,
)

/**
 * How a PDF or DjVu book you haven't set opens: turning pages or scrolling, the way you last picked
 * in any of them. A book you set keeps its own; that is the reader's session, not this. EPUBs keep
 * theirs with "Use these for new books" and comics with their library's reading defaults.
 */
interface ReaderPagesPreferencesStore {
    val pickFlow: StateFlow<ReaderPagesPick>

    /** Saves the pick in the background; [pickFlow] follows once it is written. */
    fun setPageImagesPick(mode: ReaderPagesMode)
}

class DataStoreReaderPagesPreferencesStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.readerPagesPreferencesDataStore,
    /** The app scope; saves run on it so they outlive the reader. */
    private val scope: CoroutineScope,
    private val ioRetryDelayMillis: Long = DATASTORE_IO_RETRY_DELAY_MILLIS,
) : ReaderPagesPreferencesStore {

    override val pickFlow: StateFlow<ReaderPagesPick> = dataStore.data
        .retryWhen { error, _ ->
            if (error is IOException) {
                emit(emptyPreferences())
                delay(ioRetryDelayMillis)
            }
            error is IOException
        }
        .map { preferences ->
            ReaderPagesPick(pageImages = readerPagesModeOrNull(preferences[PageImagesKey]), isLoaded = true)
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = ReaderPagesPick(pageImages = null, isLoaded = false),
        )

    override fun setPageImagesPick(mode: ReaderPagesMode) {
        scope.launch {
            dataStore.edit { preferences ->
                preferences[PageImagesKey] = mode.name
            }
        }
    }
}

/** For previews and tests that do not persist the pick. */
class InMemoryReaderPagesPreferencesStore(
    pageImages: ReaderPagesMode? = null,
) : ReaderPagesPreferencesStore {
    private val pickState = MutableStateFlow(ReaderPagesPick(pageImages = pageImages, isLoaded = true))
    override val pickFlow: StateFlow<ReaderPagesPick> = pickState.asStateFlow()

    override fun setPageImagesPick(mode: ReaderPagesMode) {
        pickState.value = ReaderPagesPick(pageImages = mode, isLoaded = true)
    }
}

/** A saved [ReaderPagesMode] name, or null for nothing saved or a name this version doesn't know. */
fun readerPagesModeOrNull(name: String?): ReaderPagesMode? = ReaderPagesMode.entries.firstOrNull { it.name == name }

private val Context.readerPagesPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = READER_PAGES_DATASTORE_NAME,
)

private val PageImagesKey = stringPreferencesKey("page_images_pages")

internal const val READER_PAGES_DATASTORE_NAME = "reader_pages_preferences"
