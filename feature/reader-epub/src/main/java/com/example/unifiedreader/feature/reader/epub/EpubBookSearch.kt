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
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.Try

/** One match: where it is (Readium's locator, with the words and the text around them) and its snippet. */
internal data class EpubSearchResult(
    val locator: Locator,
    val snippet: EpubSearchSnippet,
) {
    /** The book's page, as the dock counts them; null when Readium didn't say. */
    val page: Int? get() = locator.locations.position
    val progression: Double? get() = locator.locations.totalProgression
}

/** The match and a few words either side, cut at whole words: "…to the [bento] shop. He…". */
internal data class EpubSearchSnippet(
    val before: String,
    val match: String,
    val after: String,
)

internal enum class EpubSearchStatus {
    /** Nothing asked yet, or too little typed. */
    Idle,
    Searching,
    Done,

    /** Stopped at [EpubSearchResultLimit] matches. */
    Capped,
    Failed,
}

/**
 * Search in the book, through Readium's search service (`publication.search`). Typing searches after a
 * short pause; matches arrive a chapter at a time. [currentIndex] is the match the reader went to last,
 * which the page tints more strongly ([decorations]) and the previous and next buttons step from.
 */
@Stable
internal class EpubBookSearch(
    private val publication: Publication,
    private val scope: CoroutineScope,
) {
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<EpubSearchResult>>(emptyList())
        private set
    var status by mutableStateOf(EpubSearchStatus.Idle)
        private set
    var currentIndex by mutableStateOf<Int?>(null)
        private set

    /** The tint for every match on the page, the current one stronger. */
    val decorations: List<Decoration> by derivedStateOf { epubSearchDecorations(results, currentIndex) }

    /** A search found something: the page shows its matches and the dock its buttons. */
    val isActive: Boolean get() = results.isNotEmpty()

    private var searchJob: Job? = null

    fun onQueryChange(text: String) {
        query = text
        search(delayMillis = EpubSearchTypingDelayMillis)
    }

    /** The keyboard's search key: no waiting for the pause. */
    fun searchNow() = search(delayMillis = 0L)

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
        val found = mutableListOf<EpubSearchResult>()
        var finished: EpubSearchStatus? = null
        while (finished == null) {
            val next = withContext(Dispatchers.IO) { iterator.next() }
            val page = (next as? Try.Success)?.value
            page?.locators?.take(EpubSearchResultLimit - found.size)?.mapTo(found) { locator ->
                EpubSearchResult(
                    locator = locator,
                    snippet = epubSearchSnippet(locator.text.before, locator.text.highlight, locator.text.after),
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

/** Two letters at least, or one character of a script without spaces between words (Chinese, Japanese). */
internal fun isEpubSearchQueryLongEnough(query: String): Boolean {
    val text = query.trim()
    return text.length >= 2 || text.any { it.code >= CjkScriptsStart }
}

/** The match with [before] and [after] cut to a few whole words, "…" where they were cut. */
internal fun epubSearchSnippet(
    before: String?,
    match: String?,
    after: String?,
    maxBefore: Int = EpubSnippetMaxBefore,
    maxAfter: Int = EpubSnippetMaxAfter,
): EpubSearchSnippet {
    val lead = before.orEmpty().collapseSpaces().trimStart()
    val tail = after.orEmpty().collapseSpaces().trimEnd()
    val shortLead = if (lead.length <= maxBefore) {
        lead
    } else {
        val cut = lead.takeLast(maxBefore)
        val wordStart = cut.indexOf(' ').takeIf { it in 0 until cut.length - 1 }
        "…" + (wordStart?.let { cut.substring(it + 1) } ?: cut)
    }
    val shortTail = if (tail.length <= maxAfter) {
        tail
    } else {
        val cut = tail.take(maxAfter)
        val wordEnd = cut.lastIndexOf(' ').takeIf { it > 0 }
        (wordEnd?.let { cut.substring(0, it) } ?: cut) + "…"
    }
    return EpubSearchSnippet(before = shortLead, match = match.orEmpty().collapseSpaces(), after = shortTail)
}

private fun String.collapseSpaces(): String = replace(Whitespace, " ")

/** The matches of one chapter, in book order: [indices] into the results. */
internal data class EpubSearchGroup(
    val title: String,
    val indices: List<Int>,
)

/** Matches next to each other in the same chapter go under one heading; [chapterOf] names a match's chapter. */
internal fun epubSearchGroups(
    results: List<EpubSearchResult>,
    chapterOf: (EpubSearchResult) -> String,
): List<EpubSearchGroup> {
    val groups = mutableListOf<EpubSearchGroup>()
    results.forEachIndexed { index, result ->
        val title = chapterOf(result)
        val last = groups.lastOrNull()
        if (last != null && last.title == title) {
            groups[groups.lastIndex] = last.copy(indices = last.indices + index)
        } else {
            groups += EpubSearchGroup(title = title, indices = listOf(index))
        }
    }
    return groups
}

/** The line under the field: "12 results in 3 chapters", "Searching…", or null before a search. */
internal fun epubSearchSummary(
    status: EpubSearchStatus,
    resultCount: Int,
    chapterCount: Int,
): String? {
    val results = if (resultCount == 1) "1 result" else "$resultCount results"
    val chapters = if (chapterCount == 1) "1 chapter" else "$chapterCount chapters"
    return when (status) {
        EpubSearchStatus.Idle -> null
        EpubSearchStatus.Searching -> if (resultCount == 0) "Searching…" else "$results so far"
        EpubSearchStatus.Done -> if (resultCount == 0) "No results" else "$results in $chapters"
        EpubSearchStatus.Capped -> "The first $resultCount results, in $chapters"
        EpubSearchStatus.Failed -> "This book can't be searched"
    }
}

/** "3 of 12", or "12 results" before one is chosen. */
internal fun epubSearchPosition(currentIndex: Int?, resultCount: Int): String = when {
    currentIndex != null -> "${currentIndex + 1} of $resultCount"
    resultCount == 1 -> "1 result"
    else -> "$resultCount results"
}

/**
 * The match to step to: the one after (or before) [currentIndex], round past the ends; before any is
 * chosen, the first match after the reader's place ([fromProgress]) or the last one before it.
 */
internal fun nextEpubSearchIndex(
    progressions: List<Double?>,
    currentIndex: Int?,
    forward: Boolean,
    fromProgress: Double,
): Int? {
    val count = progressions.size
    return when {
        count == 0 -> null
        currentIndex != null -> Math.floorMod(currentIndex + if (forward) 1 else -1, count)
        forward -> progressions.indexOfFirst { (it ?: 0.0) >= fromProgress }.takeIf { it >= 0 } ?: 0
        else -> progressions.indexOfLast { (it ?: 0.0) < fromProgress }.takeIf { it >= 0 } ?: (count - 1)
    }
}

/** At most this many matches; a word on every page would otherwise tint thousands. */
internal const val EpubSearchResultLimit = 500
private const val EpubSearchTypingDelayMillis = 450L
private const val EpubSnippetMaxBefore = 40
private const val EpubSnippetMaxAfter = 60
private const val CjkScriptsStart = 0x2E80
private val Whitespace = Regex("""\s+""")
