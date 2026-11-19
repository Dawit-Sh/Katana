package com.example.unifiedreader.feature.reader.epub

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import com.example.unifiedreader.core.model.runSuspendCatching
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/** Where a chapter's scroll stood when a drag began: at its top, at its end, both (it fits the screen), or neither. */
internal enum class EpubChapterEdge { Top, End, Both, Inside }

/** What a drag that began at [edge] and moved the finger [dragY] up or down asks for: the next chapter, the one before, or nothing. */
internal enum class EpubChapterPullTurn { Next, Previous, None }

/**
 * In Readium's scroll mode each chapter scrolls by itself and stops at its end. A drag up that begins at a
 * chapter's end goes on into the next chapter, and a drag down that begins at its top goes back to the one
 * before, so the book reads as one scroll. A drag that only reaches the end on its way is a scroll, not a
 * pull: the edge counts where the finger came down.
 */
internal fun epubChapterPullTurn(
    edge: EpubChapterEdge,
    dragX: Float,
    dragY: Float,
    thresholdPx: Float,
): EpubChapterPullTurn = when {
    abs(dragY) < thresholdPx || abs(dragY) < abs(dragX) * 2 -> EpubChapterPullTurn.None
    dragY < 0f && (edge == EpubChapterEdge.End || edge == EpubChapterEdge.Both) -> EpubChapterPullTurn.Next
    dragY > 0f && (edge == EpubChapterEdge.Top || edge == EpubChapterEdge.Both) -> EpubChapterPullTurn.Previous
    else -> EpubChapterPullTurn.None
}

/** Reads the current chapter's scroll and turns chapters for [observeEpubChapterPulls]. */
internal class EpubChapterPull(
    private val scope: CoroutineScope,
    private val navigator: () -> EpubNavigatorFragment?,
    /** Only in scroll mode: turning pages has its own swipes. */
    private val enabled: () -> Boolean,
) {
    fun edgeNow(): Deferred<EpubChapterEdge>? = navigator()?.takeIf { enabled() }?.let { current ->
        scope.async {
            val edge = runSuspendCatching { current.evaluateJavascript(EpubChapterEdgeScript) }.getOrNull()?.trim()?.trim('"')
            when (edge) {
                "top" -> EpubChapterEdge.Top
                "end" -> EpubChapterEdge.End
                "both" -> EpubChapterEdge.Both
                else -> EpubChapterEdge.Inside
            }
        }
    }

    fun onDragEnded(edge: Deferred<EpubChapterEdge>, drag: Offset, thresholdPx: Float) {
        scope.launch {
            val current = navigator() ?: return@launch
            when (epubChapterPullTurn(edge.await(), drag.x, drag.y, thresholdPx)) {
                EpubChapterPullTurn.Next -> current.goForward(animated = true)
                EpubChapterPullTurn.Previous -> current.goBackward(animated = true)
                EpubChapterPullTurn.None -> Unit
            }
        }
    }
}

/** Watches one-finger drags on the page (without taking them from Readium) for [EpubChapterPull]. */
internal fun Modifier.observeEpubChapterPulls(pull: EpubChapterPull): Modifier = pointerInput(pull) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val edge = pull.edgeNow()
        var drag = Offset.Zero
        var fingers = 1
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            fingers = maxOf(fingers, event.changes.count { it.pressed })
            event.changes.firstOrNull()?.let { change -> drag += change.positionChange() }
        } while (event.changes.any { it.pressed })
        if (edge != null && fingers == 1) pull.onDragEnded(edge, drag, EpubChapterPullThresholdDp * density)
    }
}

// The chapter's scroll as one word: at its top, at its end, both (it fits the screen), or inside.
private val EpubChapterEdgeScript = """
    (function() {
      var element = document.scrollingElement || document.documentElement;
      var top = window.scrollY <= 2;
      var end = window.scrollY + window.innerHeight >= element.scrollHeight - 2;
      return top && end ? "both" : end ? "end" : top ? "top" : "inside";
    })();
""".trimIndent()

private const val EpubChapterPullThresholdDp = 48f
