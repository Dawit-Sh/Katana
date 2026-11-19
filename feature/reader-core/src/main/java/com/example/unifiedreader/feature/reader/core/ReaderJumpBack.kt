package com.example.unifiedreader.feature.reader.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.example.unifiedreader.ui.components.AppToastIcon
import com.example.unifiedreader.ui.components.AppToastMessage
import com.example.unifiedreader.ui.components.AppToastState
import com.example.unifiedreader.ui.components.LocalAppToastState

/**
 * The way back after a jump made from the reader's controls: the slider, the page strip, the contents,
 * a search hit or a mark. A toast "Back to page 8" with Back shows for a few seconds; Back returns to
 * the place the jump left from. Jumps made while it shows keep the first place, so trying the slider
 * twice still goes back to where you were reading. One level only: going back offers nothing further.
 *
 * [T] is the reader's own idea of a place (a locator, a page index).
 */
@Stable
class ReaderJumpBack<T : Any> internal constructor(
    private val toastState: AppToastState?,
) {
    private var origin: T? = null
    private var originPage: Int = 0
    private var shown: AppToastMessage? = null
    internal var onReturn: (T) -> Unit = {}

    /** The reader is leaving [from] (page [page], 1-based; 0 when unknown) for a place the reader chose. */
    fun jumped(from: T, page: Int) {
        val toasts = toastState ?: return
        if (origin == null) {
            origin = from
            originPage = page
        }
        lateinit var message: AppToastMessage
        message = AppToastMessage(
            text = readerJumpBackLabel(originPage),
            icon = AppToastIcon.Back,
            actionLabel = ReaderJumpBackAction,
            onAction = {
                if (shown === message) {
                    val back = origin
                    forget()
                    back?.let(onReturn)
                }
            },
            // Timed out or swiped away: the offer ends. A toast replaced by the next jump's keeps it.
            onDismissed = { if (shown === message) forget() },
        )
        shown = message
        toasts.show(message)
    }

    /** Ends the offer, as when the reader closes. */
    fun cancel() {
        val message = shown
        forget()
        message?.let { toastState?.dismiss(it) }
    }

    private fun forget() {
        origin = null
        originPage = 0
        shown = null
    }
}

/** Remembers the way back for one book ([key]); [onReturn] takes the reader back to a place it left. */
@Composable
fun <T : Any> rememberReaderJumpBack(
    key: Any?,
    onReturn: (T) -> Unit,
): ReaderJumpBack<T> {
    val toastState = LocalAppToastState.current
    val jumpBack = remember(key, toastState) { ReaderJumpBack<T>(toastState) }
    SideEffect { jumpBack.onReturn = onReturn }
    DisposableEffect(jumpBack) {
        onDispose { jumpBack.cancel() }
    }
    return jumpBack
}

fun readerJumpBackLabel(page: Int): String = if (page > 0) "Back to page $page" else "Back to where you were"

const val ReaderJumpBackAction = "Back"
