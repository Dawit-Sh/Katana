package com.example.unifiedreader.feature.reader.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How the reader treats the screen and the keys while a book is open: the Light card's brightness,
 * warmth and keep-the-screen-on, and whether the volume keys turn pages. One set for every book.
 */
data class ReaderComfort(
    /** False: the tablet's own brightness. True: [brightness], for the reader's window only. */
    val ownBrightness: Boolean = false,
    /** The reader's own level, 0 to 1; null until one is set (it then starts from the tablet's). */
    val brightness: Float? = null,
    /** The warm tint over the page, 0 (none) to 1. */
    val warmth: Float = 0f,
    /** The tint shows only from [ReaderNightStartMinute] until [ReaderNightEndMinute]. */
    val warmthAtNightOnly: Boolean = false,
    val keepScreenOn: Boolean = true,
    val volumeKeysTurnPages: Boolean = true,
) {
    /** The tint to draw at [minuteOfDay] (0 is midnight): none outside the night when it is night only. */
    fun warmthAt(minuteOfDay: Int): Float = when {
        warmth <= 0f -> 0f
        warmthAtNightOnly && !isReaderNight(minuteOfDay) -> 0f
        else -> warmth.coerceIn(0f, 1f)
    }
}

/** 21:00 to 6:00, the night the warm tint can keep to. */
fun isReaderNight(minuteOfDay: Int): Boolean {
    val minute = Math.floorMod(minuteOfDay, MinutesPerDay)
    return minute >= ReaderNightStartMinute || minute < ReaderNightEndMinute
}

const val ReaderNightStartMinute: Int = 21 * 60
const val ReaderNightEndMinute: Int = 6 * 60
private const val MinutesPerDay = 24 * 60

interface ReaderComfortPreferencesStore {
    val comfortFlow: StateFlow<ReaderComfort>

    /** Saves the change in the background; [comfortFlow] follows once it is written. */
    fun update(transform: (ReaderComfort) -> ReaderComfort)
}

class DataStoreReaderComfortPreferencesStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.readerComfortDataStore,
    /** The app scope; saves run on it so they outlive the reader. */
    private val scope: CoroutineScope,
    private val ioRetryDelayMillis: Long = DATASTORE_IO_RETRY_DELAY_MILLIS,
) : ReaderComfortPreferencesStore {

    override val comfortFlow: StateFlow<ReaderComfort> = dataStore.data
        .retryWhen { error, _ ->
            if (error is IOException) {
                emit(emptyPreferences())
                delay(ioRetryDelayMillis)
            }
            error is IOException
        }
        .map(Preferences::toReaderComfort)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = ReaderComfort(),
        )

    override fun update(transform: (ReaderComfort) -> ReaderComfort) {
        scope.launch {
            dataStore.edit { preferences ->
                preferences.write(transform(preferences.toReaderComfort()))
            }
        }
    }
}

/** For previews and tests that do not persist the choices. */
class InMemoryReaderComfortPreferencesStore(
    comfort: ReaderComfort = ReaderComfort(),
) : ReaderComfortPreferencesStore {
    private val comfortState = MutableStateFlow(comfort)
    override val comfortFlow: StateFlow<ReaderComfort> = comfortState.asStateFlow()

    override fun update(transform: (ReaderComfort) -> ReaderComfort) {
        comfortState.update(transform)
    }
}

private fun Preferences.toReaderComfort(): ReaderComfort {
    val defaults = ReaderComfort()
    return ReaderComfort(
        ownBrightness = this[OwnBrightnessKey] ?: defaults.ownBrightness,
        brightness = this[BrightnessKey]?.coerceIn(0f, 1f),
        warmth = this[WarmthKey]?.coerceIn(0f, 1f) ?: defaults.warmth,
        warmthAtNightOnly = this[WarmthAtNightOnlyKey] ?: defaults.warmthAtNightOnly,
        keepScreenOn = this[KeepScreenOnKey] ?: defaults.keepScreenOn,
        volumeKeysTurnPages = this[VolumeKeysKey] ?: defaults.volumeKeysTurnPages,
    )
}

private fun MutablePreferences.write(comfort: ReaderComfort) {
    this[OwnBrightnessKey] = comfort.ownBrightness
    val brightness = comfort.brightness
    if (brightness == null) remove(BrightnessKey) else this[BrightnessKey] = brightness.coerceIn(0f, 1f)
    this[WarmthKey] = comfort.warmth.coerceIn(0f, 1f)
    this[WarmthAtNightOnlyKey] = comfort.warmthAtNightOnly
    this[KeepScreenOnKey] = comfort.keepScreenOn
    this[VolumeKeysKey] = comfort.volumeKeysTurnPages
}

private val Context.readerComfortDataStore: DataStore<Preferences> by preferencesDataStore(
    name = READER_COMFORT_DATASTORE_NAME,
)

private val OwnBrightnessKey = booleanPreferencesKey("own_brightness")
private val BrightnessKey = floatPreferencesKey("brightness")
private val WarmthKey = floatPreferencesKey("warmth")
private val WarmthAtNightOnlyKey = booleanPreferencesKey("warmth_at_night_only")
private val KeepScreenOnKey = booleanPreferencesKey("keep_screen_on")
private val VolumeKeysKey = booleanPreferencesKey("volume_keys_turn_pages")

internal const val READER_COMFORT_DATASTORE_NAME = "reader_comfort_preferences"
