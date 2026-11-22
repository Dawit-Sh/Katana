package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.unifiedreader.ui.components.AppButton
import com.example.unifiedreader.ui.components.AppChip
import com.example.unifiedreader.ui.components.AppIconButton
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppPanel
import com.example.unifiedreader.ui.components.AppTonalIconButton
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens
import java.util.concurrent.TimeUnit

/**
 * A word picked on the graph: the word and how far it is learned, its meaning, the sentence it came from, its book,
 * its reviews, the words of the same meaning (a tap picks one) and Open in book. A sheet at the bottom on a phone,
 * a card at the side on a wide screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WordGraphCard(
    word: SavedWord,
    model: WordGraphModel,
    meaning: String?,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onPick: (String) -> Unit,
    side: Boolean,
    modifier: Modifier = Modifier,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * if (side) SIDE_HEIGHT_SHARE else SHEET_HEIGHT_SHARE
    val corner = UnifiedReaderThemeTokens.presentation.cardCornerRadius
    AppPanel(
        modifier = modifier
            .then(if (side) Modifier.width(SideWidth) else Modifier.fillMaxWidth())
            .heightIn(max = maxHeight),
        shape = if (side) RoundedCornerShape(corner) else RoundedCornerShape(topStart = corner, topEnd = corner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = CardShadow,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(AppLayout.inset),
            verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = word.word,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = rememberQuoteFontFamily(),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                WordStateTag(model.states[word.id] ?: wordState(word))
                AppIconButton(onClick = onClose) { Icon(AppIcons.Close, contentDescription = WordGraphCopy.Close) }
            }
            meaning?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            word.sentence?.takeIf(String::isNotBlank)?.let { sentence ->
                Text(
                    text = sentenceWithWord(sentence, word.word),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = rememberQuoteFontFamily(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = listOfNotNull(word.book.title, wordPlace(word), BookmarksCopy.moment(word.createdAtEpochMillis)).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = WordGraphCopy.reviews(word, System.currentTimeMillis()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val same = model.synonymsOf[word.id].orEmpty().mapNotNull(model.wordsById::get)
            if (same.isNotEmpty()) {
                Text(WordGraphCopy.SameMeaning, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = AppLayout.headingGap))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
                    verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
                ) {
                    same.forEach { other ->
                        AppChip(
                            selected = false,
                            onClick = { onPick(other.id) },
                            label = {
                                Text(
                                    text = if (other.book.itemId == word.book.itemId) other.word else "${other.word} · ${other.book.title}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = { WordStateDot(model.states[other.id] ?: wordState(other)) },
                        )
                    }
                }
            }
            Spacer(Modifier.size(AppLayout.headingGap))
            AppButton(onClick = onOpen) {
                Icon(AppIcons.MenuBook, contentDescription = null, modifier = Modifier.size(SmallIcon))
                Spacer(Modifier.width(AppLayout.headingGap))
                Text(WordGraphCopy.OpenInBook)
            }
        }
    }
}

/** "Learned" with its dot, on the card. */
@Composable
private fun WordStateTag(state: WordState) {
    Row(
        modifier = Modifier.padding(horizontal = AppLayout.headingGap),
        horizontalArrangement = Arrangement.spacedBy(TagGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WordStateDot(state)
        Text(WordGraphCopy.state(state), style = MaterialTheme.typography.labelLarge)
    }
}

/** A word's state as the graph draws it: filled in the accent, filled in the second colour, or hollow. */
@Composable
internal fun WordStateDot(state: WordState, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val fill = when (state) {
        WordState.Learned -> c.primary
        WordState.Learning -> c.tertiary
        WordState.New -> Color.Transparent
    }
    val stroke = c.onSurfaceVariant
    Canvas(modifier = modifier.size(DotSize)) {
        val r = size.minDimension / 2f
        if (state == WordState.New) {
            drawCircle(stroke, r - DOT_STROKE * density / 2f, Offset(r, r), style = Stroke(DOT_STROKE * density))
        } else {
            drawCircle(fill, r, Offset(r, r))
        }
    }
}

/** Zoom and Play, at the graph's corner. */
@Composable
internal fun GraphControls(
    play: GraphPlay,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap)) {
            AppTonalIconButton(onClick = onZoomOut) { Icon(AppIcons.Remove, contentDescription = WordGraphCopy.ZoomOut) }
            AppTonalIconButton(onClick = onZoomIn) { Icon(AppIcons.Add, contentDescription = WordGraphCopy.ZoomIn) }
            AppTonalIconButton(onClick = onFit) { Icon(AppIcons.FitScreen, contentDescription = WordGraphCopy.Fit) }
        }
        AppPanel(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                modifier = Modifier.padding(end = AppLayout.headingGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
            ) {
                AppIconButton(onClick = play::toggle) {
                    Icon(
                        imageVector = if (play.playing) AppIcons.Pause else AppIcons.PlayArrow,
                        contentDescription = if (play.playing) WordGraphCopy.Pause else WordGraphCopy.Play,
                    )
                }
                Text(
                    text = WordGraphCopy.playLine(play.index, play.total, play.index?.let(play.dateOf)),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { contentDescription = WordGraphCopy.PlayHint },
                )
                AppChip(selected = false, onClick = play::nextSpeed, label = { Text(WordGraphCopy.speed(play.speed)) })
            }
        }
    }
}

/** What the graph says on screen. */
object WordGraphCopy {
    const val List = "List"
    const val Graph = "Graph"
    const val Close = "Close"
    const val SameMeaning = "Same meaning"
    const val OpenInBook = "Open in book"
    const val ZoomIn = "Zoom in"
    const val ZoomOut = "Zoom out"
    const val Fit = "Show everything"
    const val Play = "Play the links"
    const val Pause = "Pause"
    const val PlayHint = "Words come in the order you saved them"
    const val PlayIdle = "Play the links"

    fun state(state: WordState): String = when (state) {
        WordState.Learned -> "Learned"
        WordState.Learning -> "Learning"
        WordState.New -> "New"
    }

    fun filter(filter: WordStateFilter): String = when (filter) {
        WordStateFilter.All -> "All"
        WordStateFilter.Learned -> "Learned"
        WordStateFilter.Learning -> "Learning"
        WordStateFilter.New -> "New"
    }

    fun speed(speed: Int): String = "$speed×"

    /** For TalkBack: "Graph of 34 words from 6 books", or "from Hamlet". */
    fun description(model: WordGraphModel): String {
        val from = if (model.bookId != null) model.books.firstOrNull()?.title.orEmpty() else MarkCounts.books(model.books.size)
        return "Graph of ${MarkCounts.words(model.words.size)} from $from"
    }

    /** "12 of 34 · 3 Aug" while Play runs or waits, "Play the links" before. */
    fun playLine(index: Int?, total: Int, savedAt: Long?): String = if (index == null) {
        PlayIdle
    } else {
        listOfNotNull("$index of $total", savedAt?.let { BookmarksCopy.moment(it) }).joinToString(" · ")
    }

    /** "Not reviewed yet", or "Reviewed 3 Nov · next in 7 days" / "· due now". */
    fun reviews(word: SavedWord, now: Long): String {
        val reviewed = word.reviewedAtEpochMillis ?: return "Not reviewed yet"
        val due = word.reviewDueAtEpochMillis
        val next = when {
            due == null || due <= now -> "due now"
            else -> {
                val days = TimeUnit.MILLISECONDS.toDays(due - now + DAY_MILLIS - 1).coerceAtLeast(1)
                if (days == 1L) "next tomorrow" else "next in $days days"
            }
        }
        return "Reviewed ${BookmarksCopy.moment(reviewed)} · $next"
    }

    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
}

private val SideWidth: Dp = 320.dp
private val CardShadow: Dp = 6.dp
private val DotSize: Dp = 10.dp
private val TagGap: Dp = 6.dp
private const val DOT_STROKE = 1.5f
private const val SIDE_HEIGHT_SHARE = 0.7f
private const val SHEET_HEIGHT_SHARE = 0.55f
