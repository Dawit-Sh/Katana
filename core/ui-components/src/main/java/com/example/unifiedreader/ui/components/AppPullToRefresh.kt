package com.example.unifiedreader.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

// Pull down to refresh, in two parts so a screen keeps its own box: Modifier.appPullToRefresh on the
// box around the list, and AppPullToRefreshIndicator as the box's last child.

/** How far a pull has gone; one per list. */
@Composable
fun rememberAppPullToRefreshState(): PullToRefreshState = rememberPullToRefreshState()

/**
 * Pull down to refresh: once the list inside (a lazy grid or column) is at its top, dragging further
 * down draws the indicator out; let go past the threshold and [onRefresh] runs. The pull takes only
 * the drag the list has left over at its top, so the list's own scrolling and flings, and anything
 * that scrolls sideways inside it, are untouched. With [enabled] off the drag is the list's alone
 * (Android's stretch at the top).
 */
fun Modifier.appPullToRefresh(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    enabled: Boolean = true,
    onRefresh: () -> Unit,
): Modifier = pullToRefresh(
    isRefreshing = isRefreshing,
    state = state,
    enabled = enabled,
    onRefresh = onRefresh,
)

/**
 * The pull's indicator, centred at the top of the box and coming out from under [top] (the line under
 * a floating top bar). It turns while [isRefreshing] and slides back up when that ends. Material's
 * arrow that becomes the spinner, in `primary` on `surface-container-high` (the surface of menus and
 * dialogs), so it reads on every theme's ground; it follows the system's animation scale. Nothing is
 * composed at rest.
 */
@Composable
fun BoxScope.AppPullToRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    top: Dp,
    modifier: Modifier = Modifier,
) {
    val pulled by remember(state) { derivedStateOf { state.distanceFraction > 0f } }
    if (!pulled && !isRefreshing) return
    PullToRefreshDefaults.Indicator(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier
            .align(Alignment.TopCenter)
            .padding(top = top),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        color = MaterialTheme.colorScheme.primary,
    )
}
