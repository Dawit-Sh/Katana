package com.example.unifiedreader.feature.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.ReadableItemKind
import com.example.unifiedreader.feature.library.ItemProgressSummary
import com.example.unifiedreader.feature.library.LibraryCoverImageVariant
import com.example.unifiedreader.feature.library.VisibleResolvedLibraryCover
import com.example.unifiedreader.journal.JournalSnapshot
import com.example.unifiedreader.ui.components.AppBookType
import com.example.unifiedreader.ui.components.AppCoverShape
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppPanel
import com.example.unifiedreader.ui.components.AppPanelDefaults
import com.example.unifiedreader.ui.components.AppProgressBar
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The book being read, for the side menu's Reading card: the library's item and how far it is. */
data class ReadingCardBook(
    val item: ReadableItem,
    val progress: ItemProgressSummary?,
)

/**
 * The book being read across every source, for the card: the one read last that isn't finished, with
 * how far it is ([progressByItemId]); null when nothing is under way.
 */
fun readingCardBook(items: List<ReadableItem>, progressByItemId: Map<String, ItemProgressSummary>): ReadingCardBook? {
    val underWay = progressByItemId.entries
        .filter { (_, progress) -> progress.lastReadAtEpochMillis > 0L && !progress.isFinished && progress.fraction < FINISHED_FRACTION }
        .sortedByDescending { (_, progress) -> progress.lastReadAtEpochMillis }
    if (underWay.isEmpty()) return null
    val itemsById = items.associateBy(ReadableItem::id)
    // A place kept for a book no longer in the library (removed, or its server not loaded yet) is passed over.
    return underWay.firstNotNullOfOrNull { (itemId, progress) ->
        itemsById[itemId]?.let { item -> ReadingCardBook(item = item, progress = progress) }
    }
}

/** The card's model for [snapshot] against [goalMinutes], worked out off the main thread; today moves on at midnight. */
@Composable
fun rememberReadingCardModel(snapshot: JournalSnapshot, goalMinutes: Int): ReadingCardModel? {
    val today = rememberLocalToday()
    val model by produceState<ReadingCardModel?>(null, snapshot.events, snapshot.sessions, today, goalMinutes) {
        value = withContext(Dispatchers.Default) { readingCardModel(snapshot, today, goalMinutes) }
    }
    return model
}

/**
 * The side menu's Reading card: today's reading ("1 h 12 read today", Journal ›), the book being read
 * (cover, title in the book face, chapter and how far, a thin bar; a tap opens it) and the last 16 weeks
 * as a heat map coloured by the daily goal (a tap opens the Journal). Nothing else: no streak, totals or
 * key. Given less height than it would like, the heat map's squares get smaller (see [ReadingHeatMap]).
 */
@Composable
fun JournalReadingCard(
    model: ReadingCardModel,
    book: ReadingCardBook?,
    onOpenJournal: () -> Unit,
    onOpenBook: (ReadableItem) -> Unit,
    modifier: Modifier = Modifier,
    coverImageUrlProvider: (ReadableItem, LibraryCoverImageVariant) -> String? = { item, _ -> item.coverUrl },
    onCoverVisible: (ReadableItem, LibraryCoverImageVariant) -> Unit = { _, _ -> },
) {
    val ground = AppPanelDefaults.containerColor
    val shades = readingDayShades(ground)
    AppPanel(modifier = modifier.fillMaxWidth(), color = ground) {
        Column(
            modifier = Modifier.padding(ReadingCardDefaults.Inset),
            verticalArrangement = Arrangement.spacedBy(ReadingCardDefaults.Gap),
        ) {
            TodayLine(minutes = model.todayMinutes, onOpenJournal = onOpenJournal)
            if (book != null) {
                BookRow(
                    book = book,
                    onOpenBook = onOpenBook,
                    coverImageUrlProvider = coverImageUrlProvider,
                    onCoverVisible = onCoverVisible,
                )
            }
            ReadingHeatMap(
                columns = model.weeks,
                shades = shades,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = ReadingCardCopy.OpenJournal, onClick = onOpenJournal)
                    .clearAndSetSemantics {
                        contentDescription = ReadingCardCopy.heatMapDescription(model)
                        role = Role.Button
                        onClick(label = ReadingCardCopy.OpenJournal) {
                            onOpenJournal()
                            true
                        }
                    },
            )
        }
    }
}

/** "1 h 12 read today" and, at the end, "Journal ›"; the whole line opens the Journal. */
@Composable
private fun TodayLine(minutes: Int, onOpenJournal: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UnifiedReaderThemeTokens.presentation.innerCornerRadius))
            .clickable(onClickLabel = ReadingCardCopy.OpenJournal, role = Role.Button, onClick = onOpenJournal)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = readingTodayTime(minutes),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            text = ReadingCardCopy.ReadToday,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
        Text(
            text = ReadingCardCopy.Journal,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            fontWeight = FontWeight.Medium,
            color = muted,
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
        Icon(
            imageVector = AppIcons.KeyboardArrowRight,
            contentDescription = null,
            tint = muted,
            modifier = Modifier.size(ReadingCardDefaults.ArrowSize),
        )
    }
}

/** The book being read: its cover, the title in the book face, "chapter · 34%" and a thin bar; a tap opens it. */
@Composable
private fun BookRow(
    book: ReadingCardBook,
    onOpenBook: (ReadableItem) -> Unit,
    coverImageUrlProvider: (ReadableItem, LibraryCoverImageVariant) -> String?,
    onCoverVisible: (ReadableItem, LibraryCoverImageVariant) -> Unit,
) {
    val item = book.item
    val detail = readingCardBookDetail(book.progress)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UnifiedReaderThemeTokens.presentation.innerCornerRadius))
            .clickable(onClickLabel = ReadingCardCopy.OpenBook, role = Role.Button) { onOpenBook(item) }
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(ReadingCardDefaults.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(ReadingCardDefaults.CoverWidth, ReadingCardDefaults.CoverHeight).clip(AppCoverShape.shape)) {
            VisibleResolvedLibraryCover(
                item = item,
                coverImageUrlProvider = coverImageUrlProvider,
                onCoverVisible = onCoverVisible,
                imageVariant = LibraryCoverImageVariant.ShelfRow,
                modifier = Modifier.size(ReadingCardDefaults.CoverWidth, ReadingCardDefaults.CoverHeight),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = AppBookType.title(
                    MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 19.sp),
                    comic = item.kind == ReadableItemKind.COMIC,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = ReadingCardDefaults.LineGap),
                )
            }
            Spacer(modifier = Modifier.height(ReadingCardDefaults.BarGap))
            AppProgressBar(
                fraction = book.progress?.fraction ?: 0f,
                height = ReadingCardDefaults.BarHeight,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** "Stave Three · 34%", "Chapter 9 · 70%", or only "34%" when the reader kept no chapter. */
fun readingCardBookDetail(progress: ItemProgressSummary?): String? {
    progress ?: return null
    val chapter = progress.chapterTitle?.trim()?.takeIf(String::isNotEmpty)
        ?: progress.chapter?.let { number -> "Chapter $number" }
    return listOfNotNull(chapter, progress.percentLabel).joinToString(" · ")
}

/** A book this far through counts as finished (the library's shelves agree). */
private const val FINISHED_FRACTION = 0.999f

/** What the reading card says. */
internal object ReadingCardCopy {
    const val ReadToday = " read today"
    const val Journal = "Journal"
    const val OpenJournal = "Open the Journal"
    const val OpenBook = "Open the book"

    fun heatMapDescription(model: ReadingCardModel): String {
        val read = model.weeks.flatten().count { day -> !day.isFuture && day.step > 0 }
        return "Reading days, the last ${model.weeks.size} weeks: $read days read"
    }
}

/** The reading card's sizes. */
object ReadingCardDefaults {
    /** Inside the card, and between its parts. */
    val Inset: Dp = 12.dp
    val Gap: Dp = 12.dp
    val CoverWidth: Dp = 36.dp
    val CoverHeight: Dp = 54.dp
    val BarHeight: Dp = 3.dp
    val BarGap: Dp = 7.dp
    val LineGap: Dp = 2.dp
    val ArrowSize: Dp = 16.dp
}
