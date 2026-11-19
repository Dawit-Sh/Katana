package com.example.unifiedreader.feature.reader.epub

import com.example.unifiedreader.core.model.runSuspendCatching
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.positionsByReadingOrder

// Bookmark places both ways, for their server sync: the same chapters and CFIs as highlights.

/**
 * A bookmark's place the server's way: the CFI range of the first words on its page ([firstWords],
 * found nearest its place in the chapter), or, without them, of the word at its place.
 */
internal suspend fun EpubHighlightPlaces.bookmarkServerPlaceOf(locator: Locator, firstWords: String?): EpubServerPlace {
    val spine = spine()
    val item = locator.href.bookPath()?.let { path -> spine.items.getOrNull(spine.indexOf(path)) }
    val chapter = item?.let { chapterAt(it.path)?.second }
    val paths = chapter?.let {
        val near = ((locator.locations.progression ?: 0.0) * it.text.length).toInt()
        val words = firstWords?.trim()?.take(BOOKMARK_WORDS)?.trim()?.takeIf(String::isNotEmpty)
        val span = words?.let { quoted -> it.find(quoted, before = null, after = null, near = near) } ?: it.wordAt(near)
        span?.let(it::cfiPaths)
    }
    return when {
        item == null -> EpubServerPlace.NotFound
        chapter == null -> EpubServerPlace.Unreadable
        paths == null -> EpubServerPlace.NotFound
        else -> EpubServerPlace.Found(EpubCfi.formatRange(item.packageSteps, paths.first, paths.second))
    }
}

/** Where a bookmark from the server ([cfi], a range or a point) starts in this copy, and the words there. */
internal suspend fun EpubHighlightPlaces.bookmarkLocalPlaceOf(cfi: String): Pair<EpubLocalPlace, String?> {
    val range = EpubCfi.parse(cfi)
    val item = range?.let { spine().items.getOrNull(it.spineIndex) }
    val link = item?.let { chapterLink(it.path) }
    val chapter = if (item != null && link != null) chapterAt(item.path)?.second else null
    val start = if (chapter != null && range != null) chapter.spanOf(range)?.start else null
    return when {
        item == null || link == null -> EpubLocalPlace.NotFound to null
        chapter == null -> EpubLocalPlace.Unreadable to null
        start == null -> EpubLocalPlace.NotFound to null
        else -> {
            val words = chapter.text.substring(start, (start + BOOKMARK_TEXT).coerceAtMost(chapter.text.length))
                .replace(Regex("\\s+"), " ").trim()
            val quote = chapter.quote(EpubTextSpan(start, (start + 1).coerceAtMost(chapter.text.length)))
            EpubLocalPlace.Found(locatorFor(link, item.path, quote)) to words.takeIf(String::isNotEmpty)
        }
    }
}

/** The page (Readium's position, from 1) at [totalProgression] and how many there are. */
internal suspend fun EpubHighlightPlaces.pageAt(totalProgression: Double?): Pair<Int, Int>? {
    val at = totalProgression ?: return null
    val positions = runSuspendCatching { publication.positionsByReadingOrder().flatten() }.getOrNull()?.takeIf { it.isNotEmpty() }
    val index = positions?.indexOfLast { (it.locations.totalProgression ?: 0.0) <= at + PAGE_EPSILON }?.coerceAtLeast(0)
    return if (positions != null && index != null) (index + 1) to positions.size else null
}

// The word at [offset] (or the next one), as a bookmark without first words is placed by.
private fun EpubChapterText.wordAt(offset: Int): EpubTextSpan? {
    var start = offset.coerceIn(0, text.length)
    while (start < text.length && text[start].isIgnorable()) start += 1
    var end = start
    while (end < text.length && !text[end].isIgnorable()) end += 1
    return if (start < end) EpubTextSpan(start, end) else null
}

// A bookmark's first words looked for in the chapter, and the words kept for one from the server.
private const val BOOKMARK_WORDS = 60
private const val BOOKMARK_TEXT = 240
private const val PAGE_EPSILON = 1e-6
