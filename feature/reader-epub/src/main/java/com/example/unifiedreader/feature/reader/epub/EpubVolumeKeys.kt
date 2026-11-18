package com.example.unifiedreader.feature.reader.epub

import android.app.Activity
import android.view.KeyEvent
import android.view.Window
import com.example.unifiedreader.feature.reader.core.ReaderTapSide

/**
 * The side of the page a volume key stands for: down is the right side (the next page), up the left
 * (the page before). Through [resolveEpubTapAction] a right-to-left book mirrors them, as it does taps.
 */
internal fun epubVolumeKeySide(keyCode: Int): ReaderTapSide? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_DOWN -> ReaderTapSide.Right
    KeyEvent.KEYCODE_VOLUME_UP -> ReaderTapSide.Left
    else -> null
}

/**
 * Turns the volume keys into page turns while the reader shows a book: [onPageKey] gets the side for
 * each press (held keys don't repeat), and the keys no longer change the volume. Other keys go on as
 * before. Call the returned function to give the keys back.
 *
 * The keys reach the window before any view, and the page (a WebView) doesn't have the focus when a
 * panel's field does, so this wraps the window's callback rather than listening on a view.
 */
internal fun Activity.turnPagesWithVolumeKeys(onPageKey: (ReaderTapSide) -> Unit): () -> Unit {
    val window = window
    val original = window.callback ?: return {}
    var active = true
    val wrapper = object : Window.Callback by original {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val side = epubVolumeKeySide(event.keyCode)
            if (!active || side == null) return original.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onPageKey(side)
            return true
        }
    }
    window.callback = wrapper
    return {
        active = false
        // Only put the old callback back if nothing has wrapped ours since; otherwise ours just passes keys on.
        if (window.callback === wrapper) window.callback = original
    }
}
