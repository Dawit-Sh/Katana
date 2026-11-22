package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppChip
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppSegment
import com.example.unifiedreader.ui.components.AppSegmentedRow
import com.example.unifiedreader.ui.components.appGutter

/**
 * The graph: the header (count, Review, List | Graph, the book and state filters) on top, the graph filling the
 * rest of the page edge to edge.
 */
@Composable
internal fun MyWordsGraphPage(
    state: MyWordsState,
    list: MyWordsList,
    senseGroups: Map<String, Set<Long>>,
    meanings: Map<String, String>,
    actions: MyWordsActions,
    bookCover: MarkedBookCover?,
    coveredBooks: Set<String>,
    contentTopInset: Dp,
) {
    LaunchedEffect(Unit) { state.scrolled = false }
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.height(contentTopInset + AppLayout.contentTop))
        MyWordsHeader(state = state, list = list, modifier = Modifier.padding(horizontal = appGutter()))
        WordGraphPane(
            state = state,
            words = list.words,
            senseGroups = senseGroups,
            meanings = meanings,
            actions = actions,
            bookCover = bookCover,
            coveredBooks = coveredBooks,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

/** List | Graph. */
@Composable
internal fun ViewToggle(state: MyWordsState) {
    // Wider with the text size, so "Graph" never cuts off at 150% or 200%.
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    AppSegmentedRow(modifier = Modifier.width(ToggleWidth * fontScale)) {
        AppSegment(
            selected = state.view == MyWordsView.List,
            onClick = { state.view = MyWordsView.List },
            label = { Text(WordGraphCopy.List) },
            icon = { Icon(AppIcons.ViewList, contentDescription = null, modifier = Modifier.size(SmallIcon)) },
        )
        AppSegment(
            selected = state.view == MyWordsView.Graph,
            onClick = { state.view = MyWordsView.Graph },
            label = { Text(WordGraphCopy.Graph) },
            icon = { Icon(AppIcons.Hub, contentDescription = null, modifier = Modifier.size(SmallIcon)) },
        )
    }
}

/** All, Learned, Learning, New, in the header's row (they wrap on a phone): the graph fades the words that don't match. */
@Composable
internal fun StateFilters(state: MyWordsState) {
    WordStateFilter.entries.forEach { filter ->
        val dot: (@Composable () -> Unit)? = filterState(filter)?.let { shownState -> { WordStateDot(shownState) } }
        AppChip(
            selected = state.stateFilter == filter,
            onClick = { state.stateFilter = filter },
            label = { Text(WordGraphCopy.filter(filter)) },
            leadingIcon = dot,
        )
    }
}

private fun filterState(filter: WordStateFilter): WordState? = when (filter) {
    WordStateFilter.All -> null
    WordStateFilter.Learned -> WordState.Learned
    WordStateFilter.Learning -> WordState.Learning
    WordStateFilter.New -> WordState.New
}

private val ToggleWidth: Dp = 196.dp
