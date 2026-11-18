package com.example.unifiedreader.feature.reader.epub

import android.app.Activity
import android.view.KeyEvent
import com.example.unifiedreader.feature.reader.core.ReaderTapSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.ReadingProgression
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EpubVolumeKeysTest {
    @Test
    fun `volume down turns forward and up turns back`() {
        val ltr = ReadingProgression.LTR
        assertEquals(EpubTapAction.Next, resolveEpubTapAction(checkNotNull(epubVolumeKeySide(KeyEvent.KEYCODE_VOLUME_DOWN)), ltr))
        assertEquals(EpubTapAction.Previous, resolveEpubTapAction(checkNotNull(epubVolumeKeySide(KeyEvent.KEYCODE_VOLUME_UP)), ltr))
        assertNull(epubVolumeKeySide(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun `a right-to-left book mirrors the volume keys`() {
        val rtl = ReadingProgression.RTL
        assertEquals(EpubTapAction.Previous, resolveEpubTapAction(checkNotNull(epubVolumeKeySide(KeyEvent.KEYCODE_VOLUME_DOWN)), rtl))
        assertEquals(EpubTapAction.Next, resolveEpubTapAction(checkNotNull(epubVolumeKeySide(KeyEvent.KEYCODE_VOLUME_UP)), rtl))
    }

    @Test
    fun `the volume keys turn pages once per press and change no volume, until given back`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val original = activity.window.callback
        val sides = mutableListOf<ReaderTapSide>()

        val release = activity.turnPagesWithVolumeKeys { side -> sides += side }
        val callback = activity.window.callback
        assertTrue(callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
        assertTrue(callback.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 1)))
        assertTrue(callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)))
        assertTrue(callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        release()

        assertEquals(listOf(ReaderTapSide.Right, ReaderTapSide.Left), sides)
        assertSame(original, activity.window.callback)
    }

    @Test
    fun `a wrapper left in place after release passes the keys on`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val sides = mutableListOf<ReaderTapSide>()
        val release = activity.turnPagesWithVolumeKeys { side -> sides += side }
        val ours = activity.window.callback
        // Something else wrapped the window's callback after us, so ours can't be taken out.
        activity.window.callback = object : android.view.Window.Callback by ours {}
        release()

        ours.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))

        assertTrue(sides.isEmpty())
    }
}
