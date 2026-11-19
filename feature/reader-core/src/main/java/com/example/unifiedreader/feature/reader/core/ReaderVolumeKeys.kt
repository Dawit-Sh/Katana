package com.example.unifiedreader.feature.reader.core

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * The side of the page a volume key stands for: down is the right side (the next page), up the left
 * (the page before). A reader that mirrors taps in a right-to-left book mirrors these the same way.
 */
fun readerVolumeKeySide(keyCode: Int): ReaderTapSide? = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_DOWN -> ReaderTapSide.Right
    KeyEvent.KEYCODE_VOLUME_UP -> ReaderTapSide.Left
    else -> null
}

/**
 * Turns the volume keys into page turns while a reader shows a book: [onPageKey] gets the side for
 * each press (held keys don't repeat), and the keys no longer change the volume. Other keys go on as
 * before. Call the returned function to give the keys back.
 *
 * The keys reach the window before any view, and the page doesn't always have the focus (a panel's
 * field may), so this wraps the window's callback rather than listening on a view.
 */
fun Activity.turnReaderPagesWithVolumeKeys(onPageKey: (ReaderTapSide) -> Unit): () -> Unit {
    val window = window
    val original = window.callback ?: return {}
    var active = true
    val wrapper = object : Window.Callback by original {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val side = readerVolumeKeySide(event.keyCode)
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

/**
 * Settings › Reading › "Volume keys turn pages" for the PDF, DjVu and comic readers: while [enabled],
 * a press calls [onPageKey] with the side it stands for. The newest [onPageKey] is used, so it can
 * read the reader's current page without the keys being taken and given back on every turn.
 */
@Composable
fun ReaderVolumeKeys(
    enabled: Boolean,
    onPageKey: (ReaderTapSide) -> Unit,
) {
    val activity = LocalContext.current.findReaderActivity() ?: return
    val currentOnPageKey by rememberUpdatedState(onPageKey)
    DisposableEffect(activity, enabled) {
        val release = if (enabled) activity.turnReaderPagesWithVolumeKeys { side -> currentOnPageKey(side) } else null
        onDispose { release?.invoke() }
    }
}

private tailrec fun Context.findReaderActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findReaderActivity()
    else -> null
}
