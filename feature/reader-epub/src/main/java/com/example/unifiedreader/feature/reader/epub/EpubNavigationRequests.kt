package com.example.unifiedreader.feature.reader.epub

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.locateProgression

/** A place the reader screen asks the navigator to go to; [id] tells two requests for the same place apart. */
internal sealed interface EpubNavigationRequest {
    val id: Int
}

/** The dock's slider, let go at [progress] of the book. */
internal data class EpubProgressSeekRequest(
    override val id: Int,
    val progress: Float,
) : EpubNavigationRequest

/** An entry of the contents panel. */
internal data class EpubTocNavigationRequest(
    override val id: Int,
    val link: Link,
) : EpubNavigationRequest

/**
 * Takes the navigator to [request]. A note opened with Go to note is marked for a moment in [noteTint]
 * once it shows, on [scope], so the move itself isn't held up.
 */
internal suspend fun EpubNavigatorFragment.goToEpubRequest(
    request: EpubNavigationRequest,
    publication: Publication,
    noteTint: Int,
    scope: CoroutineScope,
) {
    when (request) {
        is EpubProgressSeekRequest -> {
            val locator = withContext(Dispatchers.IO) {
                publication.locateProgression(request.progress.toDouble())
            }
            locator?.let { go(it, animated = false) }
        }

        is EpubTocNavigationRequest -> go(request.link, animated = false)

        is EpubLocatorNavigationRequest -> go(request.locator, animated = false)

        is EpubNoteNavigationRequest -> {
            go(request.link, animated = false)
            scope.launch { markEpubNote(request.link, noteTint) }
        }
    }
}
