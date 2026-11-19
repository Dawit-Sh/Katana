package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppChip
import com.example.unifiedreader.ui.components.AppEmptyState
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppScreenBack
import com.example.unifiedreader.ui.components.AppScreenHost
import com.example.unifiedreader.ui.components.AppSearchField
import com.example.unifiedreader.ui.components.AppTextButton
import com.example.unifiedreader.ui.components.appGutter
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens

/** A book's cover as the library draws it, sized by [Modifier]; the app gives it (it knows the catalog). */
typealias MarkedBookCover = @Composable (book: AnnotatedBook, modifier: Modifier) -> Unit

/** A bookmark's changes from the Bookmarks screens: removing it, and its name and note (blank removes them). */
interface BookmarkEdits {
    fun deleteBookmark(id: String)

    fun nameBookmark(id: String, title: String, note: String)
}

/** What the Bookmarks screens ask of the app and the store. */
interface BookmarksActions : BookmarkEdits {
    /** Opens the book where it was left. */
    fun continueReading(book: AnnotatedBook)

    /** Opens the book at a mark (its locator). */
    fun openAt(book: AnnotatedBook, locatorJson: String)

    /** "p. 8 of 78" for a book's Continue, when its place is known. */
    fun progressLabel(itemId: String): String?


    fun deleteWord(id: String)

    fun setWordNote(id: String, note: String)

    fun reviewWord(id: String, known: Boolean)

    suspend fun exportBook(book: AnnotatedBook): MarkdownExport

    suspend fun exportAll(): MarkdownExport
}

/**
 * The Bookmarks screen: every book with a highlight, a bookmark or a saved word as a grid of
 * covers, like the library; one book's marks; or the saved words as flashcards. [library] is
 * null until the marks have loaded. Back steps out of a book or the review.
 */
@Composable
fun BookmarksScreen(
    state: BookmarksState,
    library: MarkedLibrary?,
    actions: BookmarksActions,
    cover: MarkedBookCover,
    // The library grid's cells for its cover size (two columns at least on a phone).
    gridCells: GridCells,
    contentTopInset: Dp,
    modifier: Modifier = Modifier,
) {
    // A book's marks and the review slide in over the books and back out, Back (and the
    // predictive gesture) stepping back a page.
    AppScreenHost(
        screen = state.page,
        modifier = modifier,
        back = bookmarksBackTarget(state.page)?.let { target -> AppScreenBack(target) { state.back() } },
        transitionFor = ::bookmarksPageTransition,
        ground = true,
    ) { page ->
        val shown = library ?: return@AppScreenHost
        when (page) {
            BookmarksPage.Library -> MarkedBooksGrid(state, shown, cover, gridCells, contentTopInset)
            is BookmarksPage.Book -> {
                val book = shown.book(page.itemId)
                if (book == null) {
                    // Its last mark was removed: nothing is left to show.
                    LaunchedEffect(page.itemId) { state.home() }
                } else {
                    MarkedBookPage(state, book, actions, cover, contentTopInset)
                }
            }
            is BookmarksPage.Review -> WordReviewPage(state, shown, actions, contentTopInset)
        }
    }
}

@Composable
private fun MarkedBooksGrid(
    state: BookmarksState,
    library: MarkedLibrary,
    cover: MarkedBookCover,
    gridCells: GridCells,
    contentTopInset: Dp,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val scrolled by remember { derivedStateOf { gridState.canScrollBackward } }
    LaunchedEffect(scrolled) { state.scrolled = scrolled }
    val books = remember(library, state.kind, state.query, state.order) {
        library.shown(state.kind, state.query, state.order)
    }
    val gutter = appGutter()
    LazyVerticalGrid(
        columns = gridCells,
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = gutter,
            top = contentTopInset + AppLayout.contentTop,
            end = gutter,
            bottom = AppLayout.contentBottom,
        ),
        horizontalArrangement = Arrangement.spacedBy(AppLayout.gridGap),
        verticalArrangement = Arrangement.spacedBy(AppLayout.sectionGap),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            MarkedBooksHeader(state = state, library = library)
        }
        when {
            library.isEmpty -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                AppEmptyState(title = BookmarksCopy.EmptyTitle, body = BookmarksCopy.EmptyBody)
            }
            books.isEmpty() -> item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                AppEmptyState(title = BookmarksCopy.NothingFoundTitle, body = BookmarksCopy.NothingFoundBody)
            }
            else -> items(books, key = { it.book.itemId }) { book ->
                MarkedBookCard(book = book, cover = cover, onClick = { state.openBook(book.book.itemId) })
            }
        }
    }
}

/** The totals with Review words, the search, then the filter chips and the order. */
@Composable
private fun MarkedBooksHeader(state: BookmarksState, library: MarkedLibrary) {
    Column(verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = BookmarksCopy.librarySummary(library),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (library.wordCount > 0) {
                AppTextButton(onClick = state::openReview) {
                    Icon(imageVector = AppIcons.Style, contentDescription = null, modifier = Modifier.size(SmallIcon))
                    Spacer(Modifier.width(AppLayout.headingGap))
                    Text(BookmarksCopy.ReviewWords)
                }
            }
        }
        if (!library.isEmpty) {
            AppSearchField(
                query = state.query,
                onQueryChange = { state.query = it },
                placeholder = BookmarksCopy.SearchPlaceholder,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MarkKind.entries.forEach { kind ->
                    AppChip(
                        selected = state.kind == kind,
                        onClick = { state.kind = kind },
                        label = { Text(kindLabel(kind)) },
                    )
                }
                Spacer(Modifier.width(AppLayout.headingGap))
                AppChip(
                    selected = false,
                    onClick = {
                        state.order = if (state.order == MarkedBookOrder.LastMarked) MarkedBookOrder.Title else MarkedBookOrder.LastMarked
                    },
                    label = { Text(if (state.order == MarkedBookOrder.LastMarked) BookmarksCopy.LastMarked else BookmarksCopy.Title) },
                    leadingIcon = { Icon(imageVector = AppIcons.SwapVert, contentDescription = null, modifier = Modifier.size(SmallIcon)) },
                )
            }
        }
    }
}

/** A book's cover with a ribbon when it has bookmarks, its title, its counts and when it was last marked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkedBookCard(book: MarkedBook, cover: MarkedBookCover, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        Box {
            cover(book.book, Modifier.fillMaxWidth())
            if (book.bookmarks.isNotEmpty()) {
                BookmarkRibbon(modifier = Modifier.align(Alignment.TopEnd).padding(end = AppLayout.menuInset))
            }
        }
        Text(
            text = book.book.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
            verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        ) {
            if (book.highlights.isNotEmpty()) MarkCountPill(MarkCounts.highlights(book.highlights.size), MarkTint.Highlight)
            if (book.bookmarks.isNotEmpty()) MarkCountPill(MarkCounts.bookmarks(book.bookmarks.size), MarkTint.Bookmark)
            if (book.words.isNotEmpty()) MarkCountPill(MarkCounts.words(book.words.size), MarkTint.Word)
        }
        Text(
            text = BookmarksCopy.lastMarked(book.lastMarkedAtEpochMillis),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A cover for a book the library no longer holds: its title and author set in the book face on a
 * quiet panel, in the cover's place.
 */
@Composable
fun MarkedBookPlaceholderCover(book: AnnotatedBook, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(UnifiedReaderThemeTokens.presentation.coverCornerRadius),
            )
            .padding(AppLayout.menuInset),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = rememberQuoteFontFamily(),
            fontWeight = FontWeight.Bold,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        book.author?.let { author ->
            Text(
                text = author.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = rememberQuoteFontFamily(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "2 highlights" on a pill tinted with what it counts. */
@Composable
internal fun MarkCountPill(text: String, tint: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(tint.copy(alpha = PillAlpha), RoundedCornerShape(UnifiedReaderThemeTokens.presentation.chipCornerRadius))
            .padding(horizontal = AppLayout.headingGap, vertical = PillVerticalPadding),
    )
}

/**
 * The bookmark ribbon: an amber strip with a notch at its foot, hanging from the top edge of a
 * cover or a page.
 */
@Composable
fun BookmarkRibbon(
    modifier: Modifier = Modifier,
    width: Dp = RibbonWidth,
    height: Dp = RibbonHeight,
) {
    Canvas(modifier = modifier.size(width, height)) {
        val notch = size.height * RibbonNotch
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(size.width / 2f, size.height - notch)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, MarkTint.Bookmark)
        // A faint shadow line down one side, so the ribbon reads on a cover of the same colour.
        drawLine(Color.Black.copy(alpha = RibbonEdgeAlpha), Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 1f)
    }
}

/** The colours that tell the kinds of mark apart: a highlight's yellow, the ribbon's amber, a word's blue. */
object MarkTint {
    val Highlight = Color(HighlightColor.Yellow.argb)
    val Bookmark = Color(RIBBON_ARGB)
    val Word = Color(HighlightColor.Blue.argb)
}

internal fun kindLabel(kind: MarkKind): String = when (kind) {
    MarkKind.All -> BookmarksCopy.All
    MarkKind.Highlights -> BookmarksCopy.Highlights
    MarkKind.Bookmarks -> BookmarksCopy.Bookmarks
    MarkKind.Words -> BookmarksCopy.Words
}

private const val RIBBON_ARGB = 0xFFE8A33D
private const val PillAlpha = 0.28f
private const val RibbonNotch = 0.26f
private const val RibbonEdgeAlpha = 0.18f
internal val SmallIcon = 18.dp
private val PillVerticalPadding = 3.dp
private val RibbonWidth = 22.dp
private val RibbonHeight = 34.dp
