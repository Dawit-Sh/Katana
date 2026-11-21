package com.example.unifiedreader.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A card's height with the least it can show and with everything (its intrinsic heights). */
internal data class CardHeights(val least: Int, val most: Int)

/** The heights the menu gives its two cards; null leaves a card out. */
internal data class DrawerFit(val readingCard: Int?, val wordCard: Int?)

/**
 * What fits in [room] (the menu's height less its name and its rows) so the menu never scrolls, in
 * this order: everything at full size; the reading card's heat map smaller (down to its smallest
 * squares, [readingCard]'s least); the word card without its meaning ([wordCard]'s least); no word
 * card (the heat map takes back what it can); and last no reading card either. [readingGap] and
 * [wordGap] are the room each card keeps from the rows beside it.
 */
internal fun fitDrawer(
    room: Int,
    readingCard: CardHeights?,
    wordCard: CardHeights?,
    readingGap: Int,
    wordGap: Int,
): DrawerFit {
    val reading = readingCard.withGap(readingGap)
    val word = wordCard.withGap(wordGap)
    return when {
        reading.most + word.most <= room -> DrawerFit(readingCard?.most, wordCard?.most)
        reading.least + word.most <= room -> DrawerFit(readingCard?.within(room - word.most - readingGap), wordCard?.most)
        reading.least + word.least <= room -> DrawerFit(readingCard?.within(room - word.least - readingGap), wordCard?.least)
        reading.least <= room -> DrawerFit(readingCard?.within(room - readingGap), null)
        else -> DrawerFit(null, null)
    }
}

/** The room a card takes with its [gap]; none for no card. */
private fun CardHeights?.withGap(gap: Int): CardHeights = this?.let { CardHeights(it.least + gap, it.most + gap) } ?: CardHeights(0, 0)

/** As much of [room] as the card can use. */
private fun CardHeights.within(room: Int): Int = room.coerceIn(least, most)

/**
 * The menu laid out to [room] pixels tall, never more unless even its least can't fit: the [header] on
 * top, the [readingCard] under it, then the [rows], and the [wordCard] at the bottom with the free room
 * above it. The cards get the heights [fitDrawer] picks from their intrinsic heights, and fit themselves
 * to them (the heat map's squares, the word card's meaning).
 */
@Composable
internal fun DrawerFitLayout(
    room: Int,
    header: @Composable () -> Unit,
    rows: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    readingCard: (@Composable () -> Unit)? = null,
    wordCard: (@Composable () -> Unit)? = null,
) {
    Layout(
        contents = listOf(header, readingCard ?: {}, rows, wordCard ?: {}),
        modifier = modifier,
    ) { contents, constraints ->
        val headers = contents[HEADER]
        val readingCards = contents[READING_CARD]
        val rowsList = contents[ROWS]
        val wordCards = contents[WORD_CARD]
        val width = constraints.maxWidth
        val across = Constraints(minWidth = width, maxWidth = width)
        val headerPlaceables = headers.map { it.measure(across) }
        val rowPlaceables = rowsList.map { it.measure(across) }
        val headerHeight = headerPlaceables.sumOf { it.height }
        val rowsHeight = rowPlaceables.sumOf { it.height }
        val readingGap = ReadingCardGap.roundToPx()
        val wordGap = WordCardGap.roundToPx()
        val reading = readingCards.firstOrNull()
        val word = wordCards.firstOrNull()
        val fit = fitDrawer(
            room = room - headerHeight - rowsHeight,
            readingCard = reading?.let { CardHeights(it.minIntrinsicHeight(width), it.maxIntrinsicHeight(width)) },
            wordCard = word?.let { CardHeights(it.minIntrinsicHeight(width), it.maxIntrinsicHeight(width)) },
            readingGap = readingGap,
            wordGap = wordGap,
        )
        val readingPlaceable = fit.readingCard?.let { height -> reading?.measure(across.copy(maxHeight = height)) }
        val wordPlaceable = fit.wordCard?.let { height -> word?.measure(across.copy(maxHeight = height)) }
        val used = headerHeight + rowsHeight +
            (readingPlaceable?.let { it.height + readingGap } ?: 0) +
            (wordPlaceable?.let { it.height + wordGap } ?: 0)
        val height = maxOf(room, used)
        layout(width, height) {
            var y = 0
            headerPlaceables.forEach { placeable ->
                placeable.place(0, y)
                y += placeable.height
            }
            readingPlaceable?.let { placeable ->
                placeable.place(0, y)
                y += placeable.height + readingGap
            }
            rowPlaceables.forEach { placeable ->
                placeable.place(0, y)
                y += placeable.height
            }
            wordPlaceable?.place(0, height - wordPlaceable.height)
        }
    }
}

// The layout's contents, in order.
private const val HEADER = 0
private const val READING_CARD = 1
private const val ROWS = 2
private const val WORD_CARD = 3

/** Under the reading card, before the rows; and the least room above the word card. */
private val ReadingCardGap: Dp = 4.dp
private val WordCardGap: Dp = 12.dp
