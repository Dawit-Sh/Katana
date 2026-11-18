package com.example.unifiedreader.feature.reader.epub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.feature.annotations.AnnotationPlace
import com.example.unifiedreader.feature.annotations.HighlightToPlace
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        val highlight = locator.text.highlight?.takeIf(String::isNotBlank) ?: return EpubServerPlace.NotFound
        val path = locator.href.bookPath() ?: return EpubServerPlace.NotFound
        val spine = spine() ?: return EpubServerPlace.Unreadable
        val item = spine.items.getOrNull(spine.indexOf(path)) ?: return EpubServerPlace.NotFound
        val chapter = chapter(item.path) ?: return EpubServerPlace.Unreadable
        val span = chapter.find(highlight, locator.text.before, locator.text.after) ?: return EpubServerPlace.NotFound
        val (start, end) = chapter.cfiPaths(span) ?: return EpubServerPlace.NotFound
        return EpubServerPlace.Found(EpubCfi.formatRange(item.packageSteps, start, end))
    }

    /** Where the CFI [cfi] is in this copy of the book, or null when it isn't (another edition, say). */
    suspend fun locatorOf(cfi: String): Locator? {
        val range = EpubCfi.parse(cfi) ?: return null
        val item = spine()?.items?.getOrNull(range.spineIndex) ?: return null
        val link = linkFor(item.path) ?: return null
        val chapter = chapter(item.path) ?: return null
        val span = chapter.spanOf(range) ?: return null
        val quote = chapter.quote(span)
        return Locator(
            href = link.url(),
            mediaType = link.mediaType ?: MediaType.XHTML,
            title = link.title,
            locations = Locator.Locations(
                progression = quote.progression,
                totalProgression = totalProgression(item.path, quote.progression),
            ),
            text = Locator.Text(before = quote.before, highlight = quote.highlight, after = quote.after),
        )
    }

    private suspend fun spine(): EpubPackageSpine? {
        spine?.let { return it }
        val fromPackage = runSuspendCatching {
            val container = read(Url("META-INF/container.xml")) ?: return@runSuspendCatching null
            val opfPath = EpubPackageSpine.packagePath(container) ?: return@runSuspendCatching null
            val opf = Url(opfPath.encodedPath())?.let { read(it) } ?: return@runSuspendCatching null
            EpubPackageSpine.parse(opf, opfPath)
        }.getOrNull()?.takeIf { it.items.isNotEmpty() }
        // Without the package document: the reading order, as if the spine had no non-linear items.
        val found = fromPackage ?: EpubPackageSpine(
            publication.readingOrder.mapIndexedNotNull { position, link ->
                link.url().bookPath()?.let { path ->
                    EpubSpineItem(path, listOf(CfiStep(SPINE_STEP), CfiStep(2 * (position + 1))))
                }
            },
        )
        spine = found
        return found
    }

    private suspend fun chapter(path: String): EpubChapterText? {
        chapters[path]?.let { return it }
        val link = linkFor(path) ?: return null
        val bytes = runSuspendCatching { publication.get(link)?.use { resource -> resource.read().getOrNull() } }.getOrNull() ?: return null
        val isHtml = link.mediaType?.matches(MediaType.HTML) == true || path.endsWith(".html", true) || path.endsWith(".htm", true)
        val parsed = runSuspendCatching { EpubChapterText.parse(bytes, isHtml) }.getOrNull() ?: return null
        chapters[path] = parsed
        return parsed
    }

    private fun linkFor(path: String): Link? =
        (publication.readingOrder + publication.resources).firstOrNull { link -> link.url().bookPath() == path }

    private suspend fun read(url: Url?): ByteArray? = url?.let { publication.get(it)?.use { resource -> resource.read().getOrNull() } }

    // How far through the book: the chapter's share of Readium's positions, [progression] into it.
    private suspend fun totalProgression(path: String, progression: Double): Double? {
        val index = publication.readingOrder.indexOfFirst { link -> link.url().bookPath() == path }.takeIf { it >= 0 } ?: return null
        val positions = runSuspendCatching { publication.positionsByReadingOrder() }.getOrNull() ?: return null
        val start = positions.getOrNull(index)?.firstOrNull()?.locations?.totalProgression ?: return null
        val end = positions.drop(index + 1).firstNotNullOfOrNull { it.firstOrNull()?.locations?.totalProgression } ?: 1.0
        return (start + (end - start) * progression).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val CHAPTERS_KEPT = 3
        const val LOAD_FACTOR = 0.75f
        const val SPINE_STEP = 6
    }
}

/** A Readium href as a path from the book's root, decoded and without a fragment. */
private fun Url.bookPath(): String? = removeFragment().path?.removePrefix("./")?.trimStart('/')?.takeIf(String::isNotEmpty)

// A decoded path as a URL path: each segment percent-encoded.
private fun String.encodedPath(): String = split('/').joinToString("/") { segment -> android.net.Uri.encode(segment) }

/**
 * While a book from a server that keeps highlights is open: works out the server's place (an EPUB
 * CFI) of each new highlight made here, so it can be sent, and finds each highlight that came from
 * the server in this copy of the book, so it shows on its page. Waits a moment after the book
 * opens, so the first page comes first; then keeps up as highlights are made or come down.
 */
@Composable
internal fun EpubHighlightPlacementEffect(
    publication: Publication,
    itemId: String,
    syncsHighlights: Boolean,
    store: ReaderAnnotationStore?,
    chapterTitleOf: (Locator) -> String?,
) {
    val latestChapterTitleOf by rememberUpdatedState(chapterTitleOf)
    LaunchedEffect(publication, itemId, syncsHighlights, store) {
        if (store == null || !syncsHighlights) return@LaunchedEffect
        delay(PLACEMENT_START_DELAY_MILLIS)
        val places = EpubHighlightPlaces(publication)
        // Ones not found this time (another edition's place, a chapter that wouldn't read): not tried again until the book reopens.
        val skipped = mutableSetOf<String>()
        store.highlightsToPlace(itemId).collect { waiting ->
            waiting.filterNot { it.id in skipped }.forEach { highlight ->
                val placed = withContext(Dispatchers.IO) { places.place(highlight, store, latestChapterTitleOf) }
                if (!placed) skipped += highlight.id
            }
        }
    }
}

// True when the highlight's place was settled one way or the other.
private suspend fun EpubHighlightPlaces.place(
    highlight: HighlightToPlace,
    store: ReaderAnnotationStore,
    chapterTitleOf: (Locator) -> String?,
): Boolean {
    val serverPlace = highlight.serverPlace
    if (serverPlace == null) {
        val locator = highlight.locatorJson.toLocatorOrNull() ?: return false.also { store.keepOnDevice(highlight.id) }
        return when (val found = serverPlaceOf(locator)) {
            is EpubServerPlace.Found -> true.also { store.setServerPlace(highlight.id, found.cfi) }
            EpubServerPlace.NotFound -> true.also { store.keepOnDevice(highlight.id) }
            EpubServerPlace.Unreadable -> false
        }
    }
    val locator = locatorOf(serverPlace) ?: return false
    store.place(
        highlight.id,
        AnnotationPlace(
            href = locator.href.toString(),
            locatorJson = locator.toJSON().toString(),
            chapterTitle = highlight.chapterTitle ?: chapterTitleOf(locator),
            progression = locator.locations.totalProgression,
        ),
    )
    return true
}

private fun String.toLocatorOrNull(): Locator? = try {
    Locator.fromJSON(JSONObject(this))
} catch (_: JSONException) {
    null
}

private const val PLACEMENT_START_DELAY_MILLIS = 1_500L
