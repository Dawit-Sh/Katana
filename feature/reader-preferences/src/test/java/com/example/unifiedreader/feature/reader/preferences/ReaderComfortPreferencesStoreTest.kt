package com.example.unifiedreader.feature.reader.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderComfortPreferencesStoreTest {
    @Test
    fun `defaults follow the tablet, keep the screen on and turn pages with the volume keys`() = runTest {
        val store = DataStoreReaderComfortPreferencesStore(
            context = context(),
            dataStore = dataStore("reader-comfort-defaults-test"),
            scope = backgroundScope + Dispatchers.IO,
        )

        val comfort = store.comfortFlow.value
        assertFalse(comfort.ownBrightness)
        assertNull(comfort.brightness)
        assertEquals(0f, comfort.warmth)
        assertFalse(comfort.warmthAtNightOnly)
        assertTrue(comfort.keepScreenOn)
        assertTrue(comfort.volumeKeysTurnPages)
    }

    @Test
    fun `changes are read back by a new store`() = runTest {
        val dataStore = dataStore("reader-comfort-persist-test")
        DataStoreReaderComfortPreferencesStore(context = context(), dataStore = dataStore, scope = backgroundScope + Dispatchers.IO)
            .update { comfort ->
                comfort.copy(
                    ownBrightness = true,
                    brightness = 0.4f,
                    warmth = 0.6f,
                    warmthAtNightOnly = true,
                    keepScreenOn = false,
                    volumeKeysTurnPages = false,
                )
            }

        val reopened = DataStoreReaderComfortPreferencesStore(
            context = context(),
            dataStore = dataStore,
            scope = backgroundScope + Dispatchers.IO,
        )

        val comfort = reopened.comfortFlow.first { it.ownBrightness }
        assertEquals(0.4f, comfort.brightness)
        assertEquals(0.6f, comfort.warmth)
        assertTrue(comfort.warmthAtNightOnly)
        assertFalse(comfort.keepScreenOn)
        assertFalse(comfort.volumeKeysTurnPages)
    }

    @Test
    fun `an update keeps what it does not change`() = runTest {
        val store = DataStoreReaderComfortPreferencesStore(
            context = context(),
            dataStore = dataStore("reader-comfort-partial-test"),
            scope = backgroundScope + Dispatchers.IO,
        )
        store.update { it.copy(warmth = 0.3f) }
        store.comfortFlow.first { it.warmth == 0.3f }

        store.update { it.copy(volumeKeysTurnPages = false) }

        val comfort = store.comfortFlow.first { !it.volumeKeysTurnPages }
        assertEquals(0.3f, comfort.warmth)
        assertTrue(comfort.keepScreenOn)
    }

    @Test
    fun `the night-only tint shows from 21_00 until 6_00`() {
        val comfort = ReaderComfort(warmth = 0.5f, warmthAtNightOnly = true)

        assertEquals(0f, comfort.warmthAt(minuteOfDay = 20 * 60 + 59))
        assertEquals(0.5f, comfort.warmthAt(minuteOfDay = 21 * 60))
        assertEquals(0.5f, comfort.warmthAt(minuteOfDay = 0))
        assertEquals(0.5f, comfort.warmthAt(minuteOfDay = 5 * 60 + 59))
        assertEquals(0f, comfort.warmthAt(minuteOfDay = 6 * 60))
        assertEquals(0f, comfort.warmthAt(minuteOfDay = 12 * 60))
    }

    @Test
    fun `the tint shows all day unless it keeps to the night, and never at zero`() {
        assertEquals(0.5f, ReaderComfort(warmth = 0.5f).warmthAt(minuteOfDay = 12 * 60))
        assertEquals(0f, ReaderComfort(warmth = 0f, warmthAtNightOnly = true).warmthAt(minuteOfDay = 22 * 60))
    }

    private fun context(): android.content.Context = ApplicationProvider.getApplicationContext()

    private fun kotlinx.coroutines.test.TestScope.dataStore(name: String) = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { File(context().cacheDir, "$name.preferences_pb").apply { delete() } },
    )
}
