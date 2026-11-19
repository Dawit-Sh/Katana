package com.example.unifiedreader.feature.reader.core

import android.os.Build
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSystemBarsBehaviorTest {
    @Test
    fun `from Android 12 the back gesture works while a book hides the bars`() {
        assertEquals(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT, readerSystemBarsBehavior(Build.VERSION_CODES.S))
        assertEquals(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT, readerSystemBarsBehavior(Build.VERSION_CODES.BAKLAVA))
    }

    @Test
    fun `Android 9 to 11, the Fire 7 among them, keep transient bars`() {
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            readerSystemBarsBehavior(Build.VERSION_CODES.P),
        )
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            readerSystemBarsBehavior(Build.VERSION_CODES.R),
        )
    }
}
