package com.example.unifiedreader

import androidx.annotation.VisibleForTesting
import com.example.unifiedreader.core.model.isComicReaderFormat
import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.feature.library.ItemProgressSummary
import com.example.unifiedreader.feature.reader.core.ReaderChapterPosition
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionSnapshot
import com.example.unifiedreader.feature.reader.core.chapterPosition
import com.example.unifiedreader.feature.reader.core.shownPageAt
import kotlin.math.roundToInt

internal fun buildProgressSummaries(
    items: List<ReadableItem>,
    progressByItemId: Map<String, ReaderProgressSnapshot>,
    sessionByItemId: Map<String, ReaderSessionSnapshot>,
    localPageCountProvider: (ReadableItem) -> Int,
): Map<String, ItemProgressSummary> = items.associateNotNull { item ->
    val progress = progressByItemId[item.id] ?: return@associateNotNull null
    if (progress.isExplicitUnreadReset) return@associateNotNull null
    val sessionPageCount = sessionByItemId[item.id]?.pageCount?.takeIf { it > 0 }
    val summary = when (progress.locatorType) {
        LocatorType.PAGE -> {
            val pageCount = sessionPageCount ?: item.pageCount
                ?: if (item.format.isComicReaderFormat()) localPageCountProvider(item) else 0
            val pageIndex = progress.page ?: if (progress.isFinished) {
                (pageCount - 1).coerceAtLeast(0)
            } else {
                return@associateNotNull null
            }
            buildItemProgressSummary(
                pageIndex = pageIndex,
                pageCount = pageCount,
                isFinished = progress.isFinished,
                lastReadAtEpochMillis = progress.updatedAtEpochMillis,
            )
        }

        LocatorType.PERCENT -> buildPercentProgressSummary(
            percent = progress.percent,
            pageCount = sessionPageCount,
            isFinished = progress.isFinished,
            lastReadAtEpochMillis = progress.updatedAtEpochMillis,
        )
        LocatorType.CFI -> buildSavedLocationProgressSummary(
            percent = progress.percent,
            // A place from Grimmory keeps no page: the page the reader showed at this very place, if it has.
            page = progress.page?.takeIf { it > 0 } ?: sessionByItemId[item.id]?.shownPageAt(progress.percent),
            pageCount = sessionPageCount,
            isFinished = progress.isFinished,
            lastReadAtEpochMillis = progress.updatedAtEpochMillis,
        )
    } ?: return@associateNotNull null
    item.id to summary.withChapter(sessionByItemId[item.id]?.chapterPosition)
}

/**
 * The chapter the reader saved (number and title), while the book is not finished; how many it has and where they
 * start, always. A place that came since from another device (Grimmory, Katana Desk) while the book stayed closed
 * is named by the chapter its share falls in, without the saved chapter's title.
 */
private fun ItemProgressSummary.withChapter(position: ReaderChapterPosition?): ItemProgressSummary {
    if (position == null) return this
    val moved = chapterOfShare(fraction, position)?.takeIf { it != position.index }
    return copy(
        chapter = (moved ?: position.index).takeUnless { isFinished },
        chapterCount = position.count,
        chapterTitle = position.title.takeUnless { isFinished || moved != null },
        chapterStarts = position.starts,
    )
}

/**
 * The chapter (from 1) [share] of the book falls in, by the chapters' starts, when it's clearly outside the chapter the
 * reader saved; null when the starts aren't known or the place is (about) in the saved chapter.
 */
internal fun chapterOfShare(share: Float, position: ReaderChapterPosition): Int? {
    val starts = position.starts.takeIf { it.size == position.count } ?: return null
    val from = starts[position.index - 1] - CHAPTER_EDGE_MARGIN
    val until = starts.getOrNull(position.index)?.plus(CHAPTER_EDGE_MARGIN) ?: Float.MAX_VALUE
    return (starts.indexOfLast { it <= share } + 1).takeIf { it >= 1 && share !in from..until }
}

// A place this close to a chapter's edge counts as the saved chapter (the shares are rounded to four places).
private const val CHAPTER_EDGE_MARGIN = 0.002f

private fun buildItemProgressSummary(
    pageIndex: Int,
    pageCount: Int,
    isFinished: Boolean = false,
    lastReadAtEpochMillis: Long = 0L,
): ItemProgressSummary? {
    if (pageCount <= 0) {
        return if (isFinished) {
            ItemProgressSummary(
                label = "Finished",
                fraction = 1f,
                lastReadAtEpochMillis = lastReadAtEpochMillis,
                isFinished = true,
            )
        } else {
            null
        }
    }
    val clampedPageIndex = pageIndex.coerceIn(0, pageCount - 1)
    return ItemProgressSummary(
        label = if (isFinished) "Finished" else "Resume on page ${clampedPageIndex + 1} of $pageCount",
        fraction = if (isFinished) 1f else (clampedPageIndex + 1) / pageCount.toFloat(),
        currentPage = if (isFinished) pageCount else clampedPageIndex + 1,
        pageCount = pageCount,
        lastReadAtEpochMillis = lastReadAtEpochMillis,
        isFinished = isFinished,
    )
}

private fun buildPercentProgressSummary(
    percent: Double?,
    pageCount: Int? = null,
    isFinished: Boolean = false,
    lastReadAtEpochMillis: Long = 0L,
): ItemProgressSummary? {
    val normalizedPercent = if (isFinished) {
        1.0
    } else {
        percent?.coerceIn(0.0, 1.0) ?: return null
    }
    val percentLabel = (normalizedPercent * 100).roundToInt()
    return ItemProgressSummary(
        label = if (isFinished) "Finished" else "Resume at $percentLabel%",
        fraction = normalizedPercent.toFloat(),
        pageCount = pageCount,
        lastReadAtEpochMillis = lastReadAtEpochMillis,
        isFinished = isFinished,
    )
}

private fun buildSavedLocationProgressSummary(
    percent: Double?,
    page: Int? = null,
    pageCount: Int? = null,
    isFinished: Boolean = false,
    lastReadAtEpochMillis: Long = 0L,
): ItemProgressSummary = ItemProgressSummary(
    label = if (isFinished) "Finished" else "Resume from saved location",
    fraction = if (isFinished) 1f else (percent ?: 0.0).coerceIn(0.0, 1.0).toFloat(),
    currentPage = if (isFinished) pageCount else page?.takeIf { it > 0 },
    pageCount = pageCount,
    lastReadAtEpochMillis = lastReadAtEpochMillis,
    isFinished = isFinished,
)

@VisibleForTesting(otherwise = VisibleForTesting.NONE)
internal object AppViewModelTestAccess {
    fun buildItemProgressSummary(
        pageIndex: Int,
        pageCount: Int,
    ): ItemProgressSummary? = com.example.unifiedreader.buildItemProgressSummary(
        pageIndex = pageIndex,
        pageCount = pageCount,
    )
}
