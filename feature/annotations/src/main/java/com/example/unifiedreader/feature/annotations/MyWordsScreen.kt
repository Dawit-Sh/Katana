package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppButton
import com.example.unifiedreader.ui.components.AppChip
import com.example.unifiedreader.ui.components.AppEmptyState
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppMenu
import com.example.unifiedreader.ui.components.AppMenuItem
import com.example.unifiedreader.ui.components.AppPanel
import com.example.unifiedreader.ui.components.AppScreenBack
import com.example.unifiedreader.ui.components.AppScreenHost
import com.example.unifiedreader.ui.components.AppSearchField
import com.example.unifiedreader.ui.components.AppSegment
import com.example.unifiedreader.ui.components.AppSegmentedRow
import com.example.unifiedreader.ui.components.AppWindowWidth
import com.example.unifiedreader.ui.components.appGutter

/**
 * My words: every saved word, the newest first, each with its meaning when one is known, the
 * sentence it was met in (the word in bold), its book, page and when it was saved. A search, a book
 * filter and Review (the flashcards) sit on top. A tap opens the book at the sentence; a long press
 * offers Look it up, Edit note and Remove. [words] is null until they have loaded; [meanings] holds
 * a one-line meaning by word id.
 */
@Composable
fun MyWordsScreen(
    state: MyWordsState,
    words: List<SavedWord>?,
    meanings: Map<String, String>,
    actions: MyWordsActions,
    contentTopInset: Dp,
    modifier: Modifier = Modifier,
    /** The words' sense groups (the dictionary's), for the graph's synonym lines. */
    senseGroups: Map<String, Set<Long>> = emptyMap(),
    /** Draws a book's cover on the graph; null draws plain rectangles. */
    bookCover: MarkedBookCover? = null,
) {
    val list = remember(words) { words?.let(::MyWordsList) }
    // The book on the filter lost its last word: show every book again.
    LaunchedEffect(list, state.bookId) {
        if (list != null && state.bookId != null && list.book(state.bookId) == null) state.bookId = null
    }
    AppScreenHost(
        screen = state.page,
        modifier = modifier,
        back = if (state.page == MyWordsPage.Review) AppScreenBack<MyWordsPage>(MyWordsPage.Words) { state.back() } else null,
        transitionFor = ::myWordsPageTransition,
        ground = true,
    ) { page ->
        val shown = list ?: return@AppScreenHost
        when (page) {
            MyWordsPage.Words -> if (state.view == MyWordsView.Graph && !shown.isEmpty) {
                MyWordsGraphPage(state, shown, senseGroups, meanings, actions, bookCover, contentTopInset)
            } else {
                MyWordsListPage(state, shown, meanings, actions, contentTopInset)
            }
            MyWordsPage.Review -> WordReviewPage(
                words = shown.inBook(state.bookId),
                round = state.round,
                onRound = { state.round = it },
                onBack = { state.back() },
                backLabel = MyWordsCopy.Back,
                actions = actions,
                contentTopInset = contentTopInset,
                from = MyWordsCopy.reviewFrom(shown.book(state.bookId)),
            )
        }
    }
    state.editing?.let { word ->
        WordNoteDialog(
            word = word,
            onDismiss = { state.editing = null },
            onSave = { note ->
                actions.setWordNote(word.id, note)
                state.editing = null
            },
        )
    }
}

@Composable
private fun MyWordsListPage(
    state: MyWordsState,
    list: MyWordsList,
    meanings: Map<String, String>,
    actions: MyWordsActions,
    contentTopInset: Dp,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.canScrollBackward } }
    LaunchedEffect(scrolled) { state.scrolled = scrolled }
    val shown = remember(list, state.query, state.bookId) { list.shown(state.query, state.bookId) }
    // Side by side from 600 dp, as long as the text size leaves room: at 200% a 600 dp window stacks.
    val wide = LocalConfiguration.current.screenWidthDp >= AppWindowWidth.MediumMinDp * LocalDensity.current.fontScale
    val gutter = appGutter()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            top = contentTopInset + AppLayout.contentTop,
            end = gutter,
            bottom = AppLayout.contentBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        if (list.isEmpty) {
            item(key = "empty") { AppEmptyState(title = MyWordsCopy.EmptyTitle, body = MyWordsCopy.EmptyBody) }
            return@LazyColumn
        }
        item(key = "header") { MyWordsHeader(state = state, list = list) }
        if (shown.isEmpty()) {
            item(key = "none") { AppEmptyState(title = MyWordsCopy.NothingFoundTitle, body = MyWordsCopy.NothingFoundBody) }
        }
        items(shown, key = { it.id }) { word ->
            MyWordRow(
                word = word,
                meaning = meaningLine(word, meanings),
                wide = wide,
                onOpen = { actions.open(word) },
                onEdit = { state.editing = word },
                onRemove = { actions.remove(word) },
                onLookUp = { actions.lookUp(word) },
            )
        }
    }
}

/**
 * The graph: the header (count, Review, List | Graph, the book and state filters) on top, the graph filling the
 * rest of the page edge to edge.
 */
@Composable
private fun MyWordsGraphPage(
    state: MyWordsState,
    list: MyWordsList,
    senseGroups: Map<String, Set<Long>>,
    meanings: Map<String, String>,
    actions: MyWordsActions,
    bookCover: MarkedBookCover?,
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
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

/**
 * How many words from how many books with Review, then (on the list) the search, then List | Graph and the book
 * filter, and on the graph the state filter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MyWordsHeader(state: MyWordsState, list: MyWordsList, modifier: Modifier = Modifier) {
    val now = remember(list) { System.currentTimeMillis() }
    val book = list.book(state.bookId)
    val graph = state.view == MyWordsView.Graph
    Column(
        modifier = modifier.padding(bottom = AppLayout.headingGap),
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppLayout.gridGap),
            verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        ) {
            Text(
                text = MyWordsCopy.summary(list, book),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            AppButton(onClick = state::openReview) {
                Icon(imageVector = AppIcons.Style, contentDescription = null, modifier = Modifier.size(SmallIcon))
                Spacer(Modifier.width(AppLayout.headingGap))
                Text(MyWordsCopy.review(list.dueCount(now, state.bookId)))
            }
        }
        if (!graph) {
            AppSearchField(
                query = state.query,
                onQueryChange = { state.query = it },
                placeholder = MyWordsCopy.SearchPlaceholder,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
            verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        ) {
            ViewToggle(state)
            BookFilter(list = list, selected = book, onSelect = { state.bookId = it })
        }
        if (graph) StateFilters(state)
    }
}

/** List | Graph. */
@Composable
private fun ViewToggle(state: MyWordsState) {
    AppSegmentedRow(modifier = Modifier.width(ToggleWidth)) {
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

/** All, Learned, Learning, New: the graph fades the words that don't match. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StateFilters(state: MyWordsState) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
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
}

private fun filterState(filter: WordStateFilter): WordState? = when (filter) {
    WordStateFilter.All -> null
    WordStateFilter.Learned -> WordState.Learned
    WordStateFilter.Learning -> WordState.Learning
    WordStateFilter.New -> WordState.New
}

/** "All books" or the book shown, opening the list of books with their word counts. */
@Composable
private fun BookFilter(list: MyWordsList, selected: MyWordsBook?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AppChip(
            selected = selected != null,
            onClick = { open = true },
            label = {
                Text(text = selected?.book?.title ?: MyWordsCopy.AllBooks, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = { Icon(imageVector = AppIcons.FilterList, contentDescription = null, modifier = Modifier.size(SmallIcon)) },
            trailingIcon = {
                Icon(imageVector = AppIcons.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(SmallIcon))
            },
        )
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            AppMenuItem(
                text = { Text(MyWordsCopy.AllBooks) },
                onClick = {
                    open = false
                    onSelect(null)
                },
                trailingIcon = if (selected == null) checkMark() else null,
            )
            list.books.forEach { book ->
                AppMenuItem(
                    text = { Text(book.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onSelect(book.book.itemId)
                    },
                    // The count stays in view however long the title; a check on the book shown.
                    trailingIcon = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = book.count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (selected?.book?.itemId == book.book.itemId) checkMark().invoke()
                        }
                    },
                )
            }
        }
    }
}

private fun checkMark(): @Composable () -> Unit = {
    Icon(imageVector = AppIcons.Check, contentDescription = null, modifier = Modifier.size(SmallIcon))
}

/**
 * One saved word: the word in the book's face and its meaning (its note, else the dictionary's), the
 * sentence it was met in, then its book, page and when it was saved. On a phone these stack; from 600 dp (at the usual text
 * size) the word and its meaning sit beside the rest. A tap opens the book there; a long press opens its menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MyWordRow(
    word: SavedWord,
    meaning: String?,
    wide: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onLookUp: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    AppPanel(modifier = Modifier.fillMaxWidth()) {
        Box {
            val rowModifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = MyWordsCopy.WordActions,
                )
                .padding(horizontal = AppLayout.menuInset, vertical = AppLayout.menuInset)
            if (wide) {
                Row(modifier = rowModifier, horizontalArrangement = Arrangement.spacedBy(AppLayout.gridGap)) {
                    WordHeading(word, meaning, Modifier.weight(WordColumnWeight))
                    WordContext(word, Modifier.weight(1f))
                }
            } else {
                Column(modifier = rowModifier, verticalArrangement = Arrangement.spacedBy(RowLineGap)) {
                    WordHeading(word, meaning)
                    WordContext(word)
                }
            }
            AppMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                WordMenuItem(MyWordsCopy.LookUp, AppIcons.Search) {
                    menuOpen = false
                    onLookUp()
                }
                WordMenuItem(MyWordsCopy.EditNote, AppIcons.Edit) {
                    menuOpen = false
                    onEdit()
                }
                WordMenuItem(MyWordsCopy.Remove, AppIcons.Delete) {
                    menuOpen = false
                    onRemove()
                }
            }
        }
    }
}

@Composable
private fun WordMenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    AppMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
    )
}

/** The word in the book's face, then its meaning on one line when one is known. */
@Composable
private fun WordHeading(word: SavedWord, meaning: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RowLineGap)) {
        Text(
            text = word.word,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = rememberQuoteFontFamily(),
            fontWeight = FontWeight.SemiBold,
        )
        meaning?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Where the word was met: the sentence with the word in bold, then "book · page · saved". */
@Composable
private fun WordContext(word: SavedWord, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RowLineGap)) {
        word.sentence?.takeIf(String::isNotBlank)?.let { sentence ->
            Text(
                text = sentenceWithWord(sentence, word.word),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = rememberQuoteFontFamily(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = SentenceLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = listOfNotNull(word.book.title, wordPlace(word), BookmarksCopy.moment(word.createdAtEpochMillis))
                .joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = MetaLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The sentence around the saved word, on one line, with the word in bold. */
@Composable
internal fun sentenceWithWord(sentence: String, word: String): AnnotatedString {
    val text = sentenceAround(sentence, word)
    val span = wordSpan(text, word)
    val strong = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    return buildAnnotatedString {
        if (span == null) {
            append(text)
        } else {
            append(text.substring(0, span.first))
            withStyle(strong) { append(text.substring(span)) }
            append(text.substring(span.last + 1))
        }
    }
}

private val ToggleWidth: Dp = 196.dp
private const val WordColumnWeight = 0.42f
private const val SentenceLines = 6
private const val MetaLines = 3
