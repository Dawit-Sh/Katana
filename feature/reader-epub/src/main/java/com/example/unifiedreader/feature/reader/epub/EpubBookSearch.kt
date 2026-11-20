package com.example.unifiedreader.feature.reader.epub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.feature.reader.core.ReaderBookSearch
import com.example.unifiedreader.feature.reader.core.ReaderSearchGroup
import com.example.unifiedreader.feature.reader.core.ReaderSearchParts
import com.example.unifiedreader.feature.reader.core.ReaderSearchRow
import com.example.unifiedreader.feature.reader.core.ReaderSearchSnippet
import com.example.unifiedreader.feature.reader.core.ReaderSearchStatus
import com.example.unifiedreader.feature.reader.core.isReaderSearchQueryLongEnough
import com.example.unifiedreader.feature.reader.core.nextReaderSearchIndex
import com.example.unifiedreader.feature.reader.core.readerSearchGroups
import com.example.unifiedreader.feature.reader.core.readerSearchPosition
import com.example.unifiedreader.feature.reader.core.readerSearchSnippet
import com.example.unifiedreader.feature.reader.core.readerSearchSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.Try

/**
 * One match: where it is (Readium's locator, with the words and the text around them), its snippet,
 * and its page as the dock counts them (null when the book has no positions).
 */
internal data class EpubSearchResult(
    val locator: Locator,
    val snippet: EpubSearchSnippet,
    val page: Int? = null,
) {
    val progression: Double? get() = locator.locations.totalProgression
}

/** The match and a few words either side, cut at whole words: "…to the [bento] shop. He…". */
internal typealias EpubSearchSnippet = ReaderSearchSnippet

internal typealias EpubSearchStatus = ReaderSearchStatus

/**
 * Search in the book, through Readium's search service (`publication.search`). Typing searches after a
 * short pause; matches arrive a chapter at a time. [currentIndex] is the match the reader went to last,
 * which the page tints more strongly ([decorations]) and the previous and next buttons step from.
 */
@Stable
internal class EpubBookSearch(
    private val publication: Publication,
    private val scope: CoroutineScope,
) : ReaderBookSearch {
    override var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<EpubSearchResult>>(emptyList())
        private set
    override var status by mutableStateOf(EpubSearchStatus.Idle)
        private set
    override var currentIndex by mutableStateOf<Int?>(null)
        private set

    override val resultCount: Int get() = results.size

    override fun row(index: Int): ReaderSearchRow? = results.getOrNull(index)?.let { ReaderSearchRow(it.snippet, it.page) }

    /** The tint for every match on the page, the current one stronger. */
    val decorations: List<Decoration> by derivedStateOf { epubSearchDecorations(results, currentIndex) }

    /** A search found something: the page shows its matches and the dock its buttons. */
    val isActive: Boolean get() = results.isNotEmpty()

    private var searchJob: Job? = null

    override fun onQueryChange(newQuery: String) {
        query = newQuery
        search(delayMillis = EpubSearchTypingDelayMillis)
    }

    /** The keyboard's search key: no waiting for the pause. */
    override fun searchNow() = search(delayMillis = 0L)

    /** Makes the match at [index] the current one and returns where it is, to go there. */
    fun select(index: Int): Locator? {
        val match = results.getOrNull(index) ?: return null
        currentIndex = index
        return match.locator
    }

    /** The next or previous match, from the current one or else from the reader's place in the book. */
    fun step(forward: Boolean, fromProgress: Double): Locator? =
        nextEpubSearchIndex(results.map(EpubSearchResult::progression), currentIndex, forward, fromProgress)
            ?.let(::select)

    fun clear() {
        searchJob?.cancel()
        query = ""
        results = emptyList()
        status = EpubSearchStatus.Idle
        currentIndex = null
    }

    fun dispose() {
        searchJob?.cancel()
    }

    private fun search(delayMillis: Long) {
        searchJob?.cancel()
        val text = query.trim()
        if (!isEpubSearchQueryLongEnough(text)) {
            results = emptyList()
            currentIndex = null
            status = EpubSearchStatus.Idle
            return
        }
        searchJob = scope.launch {
            delay(delayMillis)
            results = emptyList()
            currentIndex = null
            status = EpubSearchStatus.Searching
            status = runSuspendCatching { collectMatches(text) }.getOrDefault(EpubSearchStatus.Failed)
        }
    }

    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun collectMatches(text: String): EpubSearchStatus {
        val iterator = withContext(Dispatchers.IO) { publication.search(text) } ?: return EpubSearchStatus.Failed
        try {
            return collectMatches(iterator)
        } finally {
            iterator.close()
        }
    }

    /** Takes Readium's matches a chapter at a time into [results], up to [EpubSearchResultLimit]. */
    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun collectMatches(iterator: SearchIterator): EpubSearchStatus {
        // Readium's matches say where in the book they are but not on which page; its positions do.
        val positions = withContext(Dispatchers.IO) { publication.positionsByReadingOrder() }
        val found = mutableListOf<EpubSearchResult>()
        var finished: EpubSearchStatus? = null
        while (finished == null) {
            val next = withContext(Dispatchers.IO) { iterator.next() }
            val page = (next as? Try.Success)?.value
            page?.locators?.take(EpubSearchResultLimit - found.size)?.mapTo(found) { locator ->
                EpubSearchResult(
                    locator = locator,
                    snippet = epubSearchSnippet(locator.text.before, locator.text.highlight, locator.text.after),
                    page = epubSearchPage(
                        resourcePositions = publication.readingOrderIndexOf(locator)?.let(positions::getOrNull).orEmpty(),
                        progression = locator.locations.progression,
                    ),
                )
            }
            if (page != null) results = found.toList()
            finished = when {
                next is Try.Failure -> if (found.isEmpty()) EpubSearchStatus.Failed else EpubSearchStatus.Done
                page == null -> EpubSearchStatus.Done
                found.size >= EpubSearchResultLimit -> EpubSearchStatus.Capped
                else -> null
            }
        }
        return finished
    }
}

@Composable
internal fun rememberEpubBookSearch(publication: Publication): EpubBookSearch {
    val scope = rememberCoroutineScope()
    val search = remember(publication) { EpubBookSearch(publication, scope) }
    DisposableEffect(search) { onDispose(search::dispose) }
    return search
}

/** The page a match is on: the last of its file's positions that starts at or before it. */
internal fun epubSearchPage(
    resourcePositions: List<Locator>,
    progression: Double?,
): Int? {
    val at = progression ?: 0.0
    val start = resourcePositions.lastOrNull { (it.locations.progression ?: 0.0) <= at } ?: resourcePositions.firstOrNull()
    return start?.locations?.position
}

/** Two letters at least, or one character of a script without spaces between words (Chinese, Japanese). */
internal fun isEpubSearchQueryLongEnough(query: String): Boolean = isReaderSearchQueryLongEnough(query)

/** The match with [before] and [after] cut to a few whole words, "…" where they were cut. */
internal fun epubSearchSnippet(
    before: String?,
    match: String?,
    after: String?,
    maxBefore: Int = EpubSnippetMaxBefore,
    maxAfter: Int = EpubSnippetMaxAfter,
): EpubSearchSnippet = readerSearchSnippet(before, match, after, maxBefore, maxAfter)

/** The matches of one chapter, in book order: [ReaderSearchGroup.indices] into the results. */
internal typealias EpubSearchGroup = ReaderSearchGroup

/** Matches next to each other in the same chapter go under one heading; [chapterOf] names a match's chapter. */
internal fun epubSearchGroups(
    results: List<EpubSearchResult>,
    chapterOf: (EpubSearchResult) -> String,
): List<EpubSearchGroup> = readerSearchGroups(results, chapterOf)

/** The line under the field: "12 results in 3 chapters", "Searching…", or null before a search. */
internal fun epubSearchSummary(status: EpubSearchStatus, resultCount: Int, chapterCount: Int): String? =
    readerSearchSummary(status, resultCount, chapterCount, ReaderSearchParts.Chapters)

/** "3 of 12", or "12 results" before one is chosen. */
internal fun epubSearchPosition(currentIndex: Int?, resultCount: Int): String = readerSearchPosition(currentIndex, resultCount)

/** The match to step to; see [nextReaderSearchIndex]. */
internal fun nextEpubSearchIndex(
    progressions: List<Double?>,
    currentIndex: Int?,
    forward: Boolean,
    fromProgress: Double,
): Int? = nextReaderSearchIndex(progressions, currentIndex, forward, fromProgress)

/** At most this many matches; a word on every page would otherwise tint thousands. */
internal const val EpubSearchResultLimit = 500
private const val EpubSearchTypingDelayMillis = 450L
private const val EpubSnippetMaxBefore = 40
private const val EpubSnippetMaxAfter = 60
