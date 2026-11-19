package com.example.unifiedreader.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.unifiedreader.ui.theme.AppMotion

/**
 * The top bar's half of a page change: what the bar shows changes with the page under it, after
 * the same wait for the new page's first frame as [AppScreenHost] and on its fade
 * ([AppMotion.pageFadeIn]), so the bar and the page change together. The bar is see-through, so
 * its old contents fade out at once over the same time ([AppMotion.pageFadeAway]) instead of
 * staying under the new ones. States with the same [contentKey] are one bar: it follows them at
 * once, in place.
 */
@Composable
fun <S> AppBarSwitch(
    targetState: S,
    modifier: Modifier = Modifier,
    contentKey: (S) -> Any? = { it },
    contentAlignment: Alignment = Alignment.TopStart,
    label: String = "AppBarSwitch",
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    val targetKey = contentKey(targetState)
    var shownKey by remember { mutableStateOf(targetKey) }
    // The last state of each bar on screen, so a bar fading out keeps drawing what it showed.
    val lastByKey = remember { HashMap<Any?, S>() }
    lastByKey[targetKey] = targetState
    LaunchedEffect(targetKey) {
        if (shownKey != targetKey) {
            awaitNewContentDrawn()
            shownKey = targetKey
            lastByKey.keys.retainAll(setOf(shownKey))
        }
    }
    val shown = if (shownKey == targetKey || !lastByKey.containsKey(shownKey)) targetState else lastByKey.getValue(shownKey)
    AnimatedContent(
        targetState = shown,
        modifier = modifier,
        transitionSpec = { appBarTransform() },
        contentAlignment = contentAlignment,
        contentKey = contentKey,
        label = label,
        content = content,
    )
}

private fun appBarTransform(): ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(AppMotion.pageFadeIn()),
    initialContentExit = fadeOut(AppMotion.pageFadeAway()),
    sizeTransform = null,
)
