package com.example.unifiedreader.feature.reader.epub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import android.util.Log
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.feature.annotations.AnnotationPlace
import com.example.unifiedreader.feature.annotations.HighlightPlacementStore
import com.example.unifiedreader.feature.annotations.HighlightToPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.use

/** A highlight's place the server's way, for one found by its words in this device's copy of the book. */
internal sealed interface EpubServerPlace {
    data class Found(val cfi: String) : EpubServerPlace

    /** Its words aren't in its chapter as quoted: it can't be placed the server's way. */
    data object NotFound : EpubServerPlace

    /** The chapter couldn't be read now; another time may do. */
    data object Unreadable : EpubServerPlace
}

/** Where a highlight from the server is in this device's copy of the book. */
internal sealed interface EpubLocalPlace {
    data class Found(val locator: Locator) : EpubLocalPlace

    /** Its place isn't in this copy (another edition, say): trying again won't find it. */
    data object NotFound : EpubLocalPlace

    /** Its chapter couldn't be read now; another time may do. */
    data object Unreadable : EpubLocalPlace
}

/**
 * Turns highlight places both ways for one open book: a Readium locator (the words and the text
 * around them, which Katana keeps) into the EPUB CFI Grimmory's web reader and KOReader keep, and a
 * CFI back into a locator. Reads the chapters from the book itself (the last few kept parsed).
 */
internal class EpubHighlightPlaces(private val publication: Publication) {
    private var spine: EpubPackageSpine? = null
    private val chapters = object : LinkedHashMap<String, EpubChapterText>(CHAPTERS_KEPT, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpubChapterText>?): Boolean =
            size > CHAPTERS_KEPT
    }

    suspend fun serverPlaceOf(locator: Locator): EpubServerPlace {
        val highlight = locator.text.highlight?.takeIf(String::isNotBlank)
        val spine = spine()
        val item = locator.href.bookPath()?.let { path -> spine.items.getOrNull(spine.indexOf(path)) }
        val chapter = item?.let { chapterAt(it.path)?.second }
        val paths = highlight
            ?.let { chapter?.find(it, locator.text.before, locator.text.after) }
            ?.let { span -> chapter?.cfiPaths(span) }
        return when {
            item != null && paths != null -> EpubServerPlace.Found(EpubCfi.formatRange(item.packageSteps, paths.first, paths.second))
            item != null && chapter == null -> EpubServerPlace.Unreadable
            else -> EpubServerPlace.NotFound
        }
    }

    /**
     * Where the CFI [cfi] of the highlighted [words] is in this copy of the book. A CFI whose characters
     * are counted a little differently (the KOReader plugin counts some the file's way) still finds its
     * words: the nearest place they are. NotFound when the place isn't in this copy (another edition,
     * say: no use trying again); Unreadable when its chapter couldn't be read just now (a big chapter
     * read while the page itself loads on a small device can fail once and read fine a moment later).
     */
    suspend fun localPlaceOf(cfi: String, words: String): EpubLocalPlace {
        val range = EpubCfi.parse(cfi)
        val item = range?.let { spine().items.getOrNull(it.spineIndex) }
        val link = item?.let { chapterLink(it.path) }
        val chapter = if (item != null && link != null) chapterAt(item.path)?.second else null
        val span = if (chapter != null && range != null) chapter.wordsAt(range, words) else null
        return when {
            item == null || link == null -> EpubLocalPlace.NotFound
            chapter == null -> EpubLocalPlace.Unreadable
            span == null -> EpubLocalPlace.NotFound
            else -> EpubLocalPlace.Found(locatorFor(link, item.path, chapter.quote(span)))
        }
    }

    private suspend fun locatorFor(link: Link, path: String, quote: EpubTextQuote): Locator = Locator(
        href = link.url(),
        mediaType = link.mediaType ?: MediaType.XHTML,
        title = link.title,
        locations = Locator.Locations(
            progression = quote.progression,
            totalProgression = totalProgression(path, quote.progression),
        ),
        text = Locator.Text(before = quote.before, highlight = quote.highlight, after = quote.after),
    )

    // The package's spine; without the package document, the reading order (as if no item were non-linear).
    private suspend fun spine(): EpubPackageSpine {
        val known = spine
        if (known != null) return known
        val fromPackage = runSuspendCatching {
            val opfPath = read(Url("META-INF/container.xml"))?.let(EpubPackageSpine::packagePath)
            opfPath?.let { path -> read(Url(path.encodedPath()))?.let { opf -> EpubPackageSpine.parse(opf, path) } }
        }.getOrNull()?.takeIf { it.items.isNotEmpty() }
        return (fromPackage ?: readingOrderSpine()).also { spine = it }
    }

    private fun readingOrderSpine() = EpubPackageSpine(
        publication.readingOrder.mapIndexedNotNull { position, link ->
            link.url().bookPath()?.let { path -> EpubSpineItem(path, listOf(CfiStep(SPINE_STEP), CfiStep(2 * (position + 1)))) }
        },
    )

    private fun chapterLink(path: String): Link? =
        (publication.readingOrder + publication.resources).firstOrNull { it.url().bookPath() == path }

    private suspend fun chapterAt(path: String): Pair<Link, EpubChapterText>? {
        val link = chapterLink(path) ?: return null
        val chapter = chapters[path] ?: readChapter(link, path)?.also { chapters[path] = it }
        return chapter?.let { link to it }
    }

    private suspend fun readChapter(link: Link, path: String): EpubChapterText? = runSuspendCatching {
        val isHtml = link.mediaType?.matches(MediaType.HTML) == true || path.endsWith(".html", true) || path.endsWith(".htm", true)
        read(link.url())?.let { bytes -> EpubChapterText.parse(bytes, isHtml) }
    }.getOrNull()

    private suspend fun read(url: Url?): ByteArray? = url?.let { publication.get(it)?.use { resource -> resource.read().getOrNull() } }

    // How far through the book: the chapter's share of Readium's positions, [progression] into it.
    private suspend fun totalProgression(path: String, progression: Double): Double? {
        val index = publication.readingOrder.indexOfFirst { link -> link.url().bookPath() == path }
        val positions = if (index >= 0) runSuspendCatching { publication.positionsByReadingOrder() }.getOrNull() else null
        val start = positions?.getOrNull(index)?.firstOrNull()?.locations?.totalProgression
        val end = positions?.drop(index + 1)?.firstNotNullOfOrNull { it.firstOrNull()?.locations?.totalProgression } ?: 1.0
        return start?.let { (it + (end - it) * progression).coerceIn(0.0, 1.0) }
    }

    private companion object {
        const val CHAPTERS_KEPT = 3
        const val LOAD_FACTOR = 0.75f
        const val SPINE_STEP = 6
    }
}

// The words at [range], or (when the CFI counts characters differently) the nearest place they are.
private fun EpubChapterText.wordsAt(range: EpubCfiRange, words: String): EpubTextSpan? {
    val atPlace = spanOf(range)
    return when {
        words.isBlank() || (atPlace != null && holds(atPlace, words)) -> atPlace
        else -> find(words, before = null, after = null, near = atPlace?.start) ?: atPlace
    }
}

/** A Readium href as a path from the book's root, decoded and without a fragment. */
private fun Url.bookPath(): String? = removeFragment().path?.removePrefix("./")?.trimStart('/')?.takeIf(String::isNotEmpty)

// A decoded path as a URL path: each segment percent-encoded.
private fun String.encodedPath(): String = split('/').joinToString("/") { segment -> android.net.Uri.encode(segment) }

/**
 * While a book from a server that keeps highlights is open ([store] places for it): works out the
 * server's place (an EPUB CFI) of each new highlight made here, so it can be sent, and finds each
 * highlight that came from the server in this copy of the book, so it shows on its page. Waits a
 * moment after the book opens, so the first page comes first; then keeps up as highlights are made
 * or come down.
 */
@Composable
internal fun EpubHighlightPlacementEffect(
    publication: Publication,
    itemId: String,
    store: HighlightPlacementStore?,
    chapterTitleOf: (Locator) -> String?,
) {
    val latestChapterTitleOf by rememberUpdatedState(chapterTitleOf)
    LaunchedEffect(publication, itemId, store) {
        if (store == null || store == HighlightPlacementStore.None) return@LaunchedEffect
        delay(PLACEMENT_START_DELAY_MILLIS)
        val places = EpubHighlightPlaces(publication)
        // Ones whose place isn't in this copy (another edition's): not tried again until the book reopens.
        val notHere = mutableSetOf<String>()
        // Each new list starts over: a placed highlight leaves the list, so the rest come round again.
        store.highlightsToPlace(itemId).collectLatest { waiting ->
            var pending = waiting.filterNot { it.id in notHere }
            var pause = PLACEMENT_RETRY_START_MILLIS
            for (attempt in 1..PLACEMENT_TRIES) {
                val unreadable = mutableListOf<HighlightToPlace>()
                pending.forEach { highlight ->
                    val outcome = withContext(Dispatchers.IO) {
                        val serverPlace = highlight.serverPlace
                        if (serverPlace == null) {
                            places.placeServerSide(highlight, store)
                        } else {
                            places.placeHere(highlight, serverPlace, store, latestChapterTitleOf)
                        }
                    }
                    when (outcome) {
                        PlacementOutcome.Done -> Unit
                        PlacementOutcome.NotHere -> notHere += highlight.id
                        PlacementOutcome.Unreadable -> unreadable += highlight
                    }
                }
                if (unreadable.isEmpty()) return@collectLatest
                // A highlight that came down while the book was still opening: its chapter is read again
                // in a moment, instead of the highlight waiting for the book to be reopened.
                Log.i(LOG_TAG, "${unreadable.size} highlight(s) couldn't be placed yet (try $attempt of $PLACEMENT_TRIES)")
                pending = unreadable
                if (attempt < PLACEMENT_TRIES) {
                    delay(pause)
                    pause *= 2
                }
            }
        }
    }
}

private enum class PlacementOutcome { Done, NotHere, Unreadable }

// A highlight made here gets its place the server's way (or stays on this device).
private suspend fun EpubHighlightPlaces.placeServerSide(highlight: HighlightToPlace, store: HighlightPlacementStore): PlacementOutcome {
    val found = highlight.locatorJson.toLocatorOrNull()?.let { serverPlaceOf(it) } ?: EpubServerPlace.NotFound
    return when (found) {
        is EpubServerPlace.Found -> PlacementOutcome.Done.also { store.setServerPlace(highlight.id, found.cfi) }
        EpubServerPlace.NotFound -> PlacementOutcome.Done.also { store.keepOnDevice(highlight.id) }
        EpubServerPlace.Unreadable -> PlacementOutcome.Unreadable
    }
}

// A highlight from the server gets its place in this copy of the book.
private suspend fun EpubHighlightPlaces.placeHere(
    highlight: HighlightToPlace,
    serverPlace: String,
    store: HighlightPlacementStore,
    chapterTitleOf: (Locator) -> String?,
): PlacementOutcome = when (val found = localPlaceOf(serverPlace, highlight.text)) {
    is EpubLocalPlace.Found -> {
        val locator = found.locator
        store.place(
            highlight.id,
            AnnotationPlace(
                href = locator.href.toString(),
                locatorJson = locator.toJSON().toString(),
                chapterTitle = highlight.chapterTitle ?: chapterTitleOf(locator),
                progression = locator.locations.totalProgression,
            ),
        )
        PlacementOutcome.Done
    }
    EpubLocalPlace.NotFound -> PlacementOutcome.NotHere
    EpubLocalPlace.Unreadable -> PlacementOutcome.Unreadable
}

private fun String.toLocatorOrNull(): Locator? = try {
    Locator.fromJSON(JSONObject(this))
} catch (_: JSONException) {
    null
}

private const val PLACEMENT_START_DELAY_MILLIS = 1_500L
private const val PLACEMENT_TRIES = 5
private const val PLACEMENT_RETRY_START_MILLIS = 2_000L
private const val LOG_TAG = "HighlightPlaces"
