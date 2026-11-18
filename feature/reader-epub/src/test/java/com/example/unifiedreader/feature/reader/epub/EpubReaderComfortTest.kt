package com.example.unifiedreader.feature.reader.epub

import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.example.unifiedreader.feature.reader.preferences.InMemoryReaderComfortPreferencesStore
import com.example.unifiedreader.feature.reader.preferences.ReaderComfort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubReaderComfortTest {
    private val store = InMemoryReaderComfortPreferencesStore()
    private val comfort = EpubReaderComfort(
        store = store,
        saved = object : State<ReaderComfort> {
            override val value: ReaderComfort get() = store.comfortFlow.value
        },
        tabletBrightness = { 0.3f },
    )

    @Test
    fun `following the tablet leaves the window's brightness alone`() {
        assertNull(comfort.windowBrightness)
        assertEquals(0.3f, comfort.shownBrightness)
    }

    @Test
    fun `a swipe starts from the tablet's level and saves its own when the finger lifts`() {
        val start = comfort.beginBrightnessSwipe()
        comfort.previewBrightness(start + 0.2f)

        assertTrue(comfort.isSwiping)
        assertEquals(0.5f, comfort.windowBrightness!!, 0.0001f)
        assertFalse("nothing saved mid-swipe", store.comfortFlow.value.ownBrightness)

        comfort.endBrightnessSwipe()

        assertFalse(comfort.isSwiping)
        assertTrue(store.comfortFlow.value.ownBrightness)
        assertEquals(0.5f, store.comfortFlow.value.brightness!!, 0.0001f)
    }

    @Test
    fun `the level stays above black`() {
        comfort.beginBrightnessSwipe()
        comfort.previewBrightness(-1f)
        assertEquals(0.02f, comfort.windowBrightness!!, 0.0001f)
        comfort.previewBrightness(3f)
        assertEquals(1f, comfort.windowBrightness!!, 0.0001f)
    }

    @Test
    fun `following the tablet again keeps the own level for next time`() {
        comfort.previewBrightness(0.7f)
        comfort.commit()

        comfort.followTablet(true)

        assertNull(comfort.windowBrightness)
        assertEquals(0.7f, store.comfortFlow.value.brightness!!, 0.0001f)
        comfort.followTablet(false)
        assertEquals(0.7f, comfort.windowBrightness!!, 0.0001f)
    }

    @Test
    fun `a slider shows its value at once and saves it when let go`() {
        comfort.previewWarmth(0.4f)

        assertEquals(0.4f, comfort.comfort.warmth)
        assertEquals(0f, store.comfortFlow.value.warmth)

        comfort.commit()
        comfort.onSaved(store.comfortFlow.value)

        assertEquals(0.4f, store.comfortFlow.value.warmth)
        assertEquals(0.4f, comfort.comfort.warmth)
    }

    @Test
    fun `up or down at the edge is the brightness's, anything else the page's`() {
        fun touch(dx: Float, dy: Float, elapsed: Long = 50L, oneFinger: Boolean = true) =
            resolveEpubEdgeTouch(Offset(dx, dy), elapsed, oneFinger, touchSlop = 8f, longPressMillis = 400L)

        assertEquals(EpubEdgeTouch.Undecided, touch(dx = 2f, dy = 4f))
        assertEquals(EpubEdgeTouch.Brightness, touch(dx = 2f, dy = -30f))
        assertEquals(EpubEdgeTouch.Page, touch(dx = 30f, dy = 10f))
        assertEquals(EpubEdgeTouch.Page, touch(dx = 0f, dy = 2f, elapsed = 600L))
        assertEquals(EpubEdgeTouch.Page, touch(dx = 0f, dy = -30f, oneFinger = false))
    }

    @Test
    fun `the Light row says what is set`() {
        assertEquals("Tablet", epubLightSummary(ReaderComfort()))
        assertEquals("40%", epubLightSummary(ReaderComfort(ownBrightness = true, brightness = 0.4f)))
        assertEquals("Tablet, warm", epubLightSummary(ReaderComfort(warmth = 0.3f)))
        val evening = ReaderComfort(ownBrightness = true, brightness = 0.4f, warmth = 0.3f, warmthAtNightOnly = true)
        assertEquals("40%, warm from 21:00", epubLightSummary(evening))
    }

    @Test
    fun `the warmth label says off, how warm and when`() {
        assertEquals("Off", epubWarmthLabel(ReaderComfort()))
        assertEquals("55%", epubWarmthLabel(ReaderComfort(warmth = 0.55f)))
        assertEquals("55% from 21:00", epubWarmthLabel(ReaderComfort(warmth = 0.55f, warmthAtNightOnly = true)))
    }

    @Test
    fun `no warmth multiplies by white, full warmth takes blue out most`() {
        assertEquals(Color.White, epubWarmthColor(0f))
        val warmest = epubWarmthColor(1f)
        assertEquals(1f, warmest.red, 0.01f)
        assertTrue(warmest.blue < warmest.green && warmest.green < warmest.red)
    }
}
