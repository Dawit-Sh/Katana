package com.example.unifiedreader.feature.reader.comics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll

private const val ContinuousReaderEndNavigationThresholdPx = 24f

internal fun shouldNavigatePastContinuousEnd(
    canScrollForward: Boolean,
    availableY: Float,
    source: NestedScrollSource,
    alreadyTriggered: Boolean,
    thresholdPx: Float = ContinuousReaderEndNavigationThresholdPx,
): Boolean = !alreadyTriggered &&
    !canScrollForward &&
    source == NestedScrollSource.UserInput &&
    availableY < -thresholdPx

/**
 * How far a scroll the strip couldn't take pulls toward its end, as a vertical pull would (negative): up for a
 * top-to-bottom strip, left for a left-to-right one, right for a right-to-left one.
 */
internal fun continuousForwardPull(
    available: Offset,
    axis: ComicScrollAxis,
    rightToLeft: Boolean,
): Float = when {
    axis == ComicScrollAxis.Vertical -> available.y
    rightToLeft -> -available.x
    else -> available.x
}

@Composable
internal fun rememberContinuousEndNavigationModifier(
    canScrollForward: Boolean,
    alreadyTriggered: Boolean,
    onNavigatePastEnd: () -> Unit,
    axis: ComicScrollAxis = ComicScrollAxis.Vertical,
    rightToLeft: Boolean = false,
): Modifier {
    val connection = remember(canScrollForward, alreadyTriggered, onNavigatePastEnd, axis, rightToLeft) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (
                    shouldNavigatePastContinuousEnd(
                        canScrollForward = canScrollForward,
                        availableY = continuousForwardPull(available, axis, rightToLeft),
                        source = source,
                        alreadyTriggered = alreadyTriggered,
                    )
                ) {
                    onNavigatePastEnd()
                }
                return Offset.Zero
            }
        }
    }
    return Modifier.nestedScroll(connection)
}
