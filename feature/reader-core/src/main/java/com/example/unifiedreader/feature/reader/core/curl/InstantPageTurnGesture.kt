package com.example.unifiedreader.feature.reader.core.curl

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * Page turn [PageTurnStyle.None]: a sideways swipe changes the page at once, with no slide or curl.
 * Once a drag goes sideways past the touch slop the swipe is the reader's (the page under it gets a
 * cancel, so it doesn't slide), and the page changes when the finger lifts, if the swipe still points
 * the same way. A drag up or down, a long press (selecting words), a second finger, text being
 * selected, or a turn [canTurn] refuses (no page that way) stay with the page.
 */
fun Modifier.instantPageTurnGesture(
    enabled: Boolean,
    rightToLeft: Boolean,
    canTurn: (PageCurlTarget) -> Boolean = { true },
    isSelectingText: () -> Boolean = { false },
    onTurn: (PageCurlTarget) -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    composed {
        val latestCanTurn by rememberUpdatedState(canTurn)
        val latestSelectingText by rememberUpdatedState(isSelectingText)
        val latestOnTurn by rememberUpdatedState(onTurn)
        pointerInput(rightToLeft) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val swipe = InstantPageSwipe(
                    rightToLeft = rightToLeft,
                    touchSlop = viewConfiguration.touchSlop,
                    longPressMillis = viewConfiguration.longPressTimeoutMillis,
                    selectingText = latestSelectingText(),
                )
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    swipe.onEvent(
                        movement = change?.let { it.position - down.position },
                        elapsedMillis = change?.let { it.uptimeMillis - down.uptimeMillis } ?: 0L,
                        pressedPointers = event.changes.count { it.pressed },
                        // Compose shows a cancel as consumed lifts: checked before this gesture consumes.
                        cancelled = change?.isConsumed == true,
                        selectingText = latestSelectingText(),
                        canTurn = { target -> latestCanTurn(target) },
                    )
                    if (swipe.owned) event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
                swipe.turnOnRelease()?.let { target -> latestOnTurn(target) }
            }
        }
    }
}

/**
 * One swipe of [instantPageTurnGesture], decided from where the finger is against where it went down.
 * [owned] once it claims the swipe; [turnOnRelease] is the page to turn to when the finger lifts.
 */
class InstantPageSwipe(
    private val rightToLeft: Boolean,
    private val touchSlop: Float,
    private val longPressMillis: Long,
    selectingText: Boolean = false,
) {
    var owned = false
        private set
    private var yielded = selectingText
    private var dropped = false
    private var movedPastSlop = false
    private var target: PageCurlTarget? = null
    private var lastMovement = Offset.Zero

    @Suppress("LongParameterList")
    fun onEvent(
        movement: Offset?,
        elapsedMillis: Long,
        pressedPointers: Int,
        cancelled: Boolean = false,
        selectingText: Boolean = false,
        canTurn: (PageCurlTarget) -> Boolean = { true },
    ) {
        val lostAfterOwning = owned && cancelled
        if (movement == null || pressedPointers > 1 || lostAfterOwning) {
            // A pinch, a lost finger or a cancel: no turn, but a swipe already owned stays owned.
            yielded = true
            dropped = true
        } else {
            lastMovement = movement
            if (!owned && !yielded) decide(movement, elapsedMillis, selectingText, canTurn)
        }
    }

    private fun decide(movement: Offset, elapsedMillis: Long, selectingText: Boolean, canTurn: (PageCurlTarget) -> Boolean) {
        if (!movedPastSlop && elapsedMillis > longPressMillis) yielded = true
        movedPastSlop = movedPastSlop || movement.getDistance() > touchSlop
        val vertical = abs(movement.y) > touchSlop && abs(movement.y) > abs(movement.x) * VerticalDominance
        if (selectingText || vertical) yielded = true
        val candidate = resolvePageCurlTarget(movement.x, rightToLeft, touchSlop)
        when {
            yielded || candidate == null -> Unit
            canTurn(candidate) -> {
                owned = true
                target = candidate
            }
            else -> yielded = true
        }
    }

    /** The page to turn to now the finger is up: only an owned swipe that still points its first way. */
    fun turnOnRelease(): PageCurlTarget? {
        val claimed = target?.takeIf { owned && !dropped } ?: return null
        return claimed.takeIf { resolvePageCurlTarget(lastMovement.x, rightToLeft, touchSlop) == it }
    }

    private companion object {
        /** A drag this much more up or down than sideways scrolls or selects; it isn't a swipe. */
        const val VerticalDominance = 1.5f
    }
}
