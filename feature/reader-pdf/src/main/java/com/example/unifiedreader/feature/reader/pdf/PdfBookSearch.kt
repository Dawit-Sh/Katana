package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.unifiedreader.feature.annotations.PageTextRect
import com.example.unifiedreader.feature.reader.core.ReaderBookSearch
import com.example.unifiedreader.feature.reader.core.ReaderSearchRow
import com.example.unifiedreader.feature.reader.core.ReaderSearchSnippet
import com.example.unifiedreader.feature.reader.core.ReaderSearchStatus
import com.example.unifiedreader.feature.reader.core.isReaderSearchQueryLongEnough
import com.example.unifiedreader.feature.reader.core.nextReaderSearchIndex
import com.example.unifiedreader.feature.reader.core.readerSearchSnippet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One match in a PDF: its page, its characters there ([start] up to [end]), its lines on the page, and its snippet. */
internal data class PdfSearchResult(
    val pageIndex: Int,
    val start: Int,
    val end: Int,
    val rects: List<PageTextRect>,
    val snippet: ReaderSearchSnippet,
)

/**
 * Search in a PDF, through the same PDFium text as selecting: page by page from the first, the matches showing as
 * they are found, up to [PdfSearchResultLimit]. Typing searches after a short pause; a new search stops the last.
 * [currentIndex] is the match the reader went to last, tinted stronger on its page; Previous and Next step from it.
 * A scanned page has no text and simply has no matches.
 */
@Stable
internal class PdfBookSearch(
    private val text: PdfTextSource,
    private val pageCount: () -> Int,
    private val scope: CoroutineScope,
) : ReaderBookSearch {
    override var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<PdfSearchResult>>(emptyList())
        private set
    override var status by mutableStateOf(ReaderSearchStatus.Idle)
        private set
    override var currentIndex by mutableStateOf<Int?>(null)
        private set

    override val resultCount: Int get() = results.size

    override fun row(index: Int): ReaderSearchRow? = results.getOrNull(index)?.let { ReaderSearchRow(it.snippet, page = null) }

    /** A search found something: the pages show its matches and the dock its buttons. */
    val isActive: Boolean get() = results.isNotEmpty()

    private val byPage by derivedStateOf { results.withIndex().groupBy { (_, result) -> result.pageIndex } }

    private var searchJob: Job? = null

    /** Page [pageIndex]'s matches, each with whether it is the current one. */
    fun hitsOn(pageIndex: Int): List<Pair<PdfSearchResult, Boolean>> =
        byPage[pageIndex].orEmpty().map { (index, result) -> result to (index == currentIndex) }

    override fun onQueryChange(newQuery: String) {
        query = newQuery
        search(delayMillis = PdfSearchTypingDelayMillis)
    }

    /** The keyboard's search key: no waiting for the pause. */
    override fun searchNow() = search(delayMillis = 0L)

    /** Makes the match at [index] the current one and returns its page, to go there. */
    fun select(index: Int): Int? {
        val match = results.getOrNull(index) ?: return null
        currentIndex = index
        return match.pageIndex
    }

    /** The next or previous match, from the current one or else from page [fromPage]; its page. */
    fun step(forward: Boolean, fromPage: Int): Int? {
        val count = pageCount().coerceAtLeast(1)
        val progressions = results.map { it.pageIndex.toDouble() / count }
        return nextReaderSearchIndex(progressions, currentIndex, forward, fromPage.toDouble() / count)?.let(::select)
    }

    fun clear() {
        searchJob?.cancel()
        query = ""
        results = emptyList()
        status = ReaderSearchStatus.Idle
        currentIndex = null
    }

    private fun search(delayMillis: Long) {
        searchJob?.cancel()
        val words = query.trim()
        if (!isReaderSearchQueryLongEnough(words)) {
            results = emptyList()
            currentIndex = null
            status = ReaderSearchStatus.Idle
            return
        }
        searchJob = scope.launch {
            delay(delayMillis)
            results = emptyList()
            currentIndex = null
            status = ReaderSearchStatus.Searching
            status = collectMatches(words)
        }
    }

    /** Reads the pages in turn (each read once, on the text source's thread) and takes their matches. */
    private suspend fun collectMatches(words: String): ReaderSearchStatus {
        val pattern = pdfSearchPattern(words)
        val found = mutableListOf<PdfSearchResult>()
        var readable = false
        for (pageIndex in 0 until pageCount()) {
            val page = text.page(pageIndex) ?: continue
            readable = true
            pdfPageMatches(page, pattern).take(PdfSearchResultLimit - found.size).mapTo(found) { range ->
                PdfSearchResult(
                    pageIndex = pageIndex,
                    start = range.first,
                    end = range.last + 1,
                    rects = page.rects(range.first, range.last + 1),
                    snippet = readerSearchSnippet(
                        before = page.textOf(range.first - PdfSnippetReach, range.first, trim = false),
                        match = page.textOf(range.first, range.last + 1),
                        after = page.textOf(range.last + 1, range.last + 1 + PdfSnippetReach, trim = false),
                    ),
                )
            }
            if (found.size != results.size) results = found.toList()
            if (found.size >= PdfSearchResultLimit) return ReaderSearchStatus.Capped
        }
        return if (readable || found.isNotEmpty()) ReaderSearchStatus.Done else ReaderSearchStatus.Failed
    }
}

/** The words searched for, any run of spaces or a line break between them, in any case. */
internal fun pdfSearchPattern(words: String): Regex =
    Regex(words.trim().split(SearchSpaces).joinToString("\\s+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

/** Where [pattern] matches on [page], as character ranges with boxes on the page (a match on a picture has none). */
internal fun pdfPageMatches(page: PdfPageText, pattern: Regex): List<IntRange> =
    pattern.findAll(page.text).map { it.range }.filter { range -> page.rects(range.first, range.last + 1).isNotEmpty() }.toList()

/** At most this many matches; a word on every page would otherwise tint thousands. */
internal const val PdfSearchResultLimit = 500
private const val PdfSearchTypingDelayMillis = 450L

/** Characters read either side of a match for its snippet, cut to whole words after. */
private const val PdfSnippetReach = 80
private val SearchSpaces = Regex("""\s+""")
