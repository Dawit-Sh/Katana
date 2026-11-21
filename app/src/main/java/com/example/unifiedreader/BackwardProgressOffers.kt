package com.example.unifiedreader

import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.SyncState
import com.example.unifiedreader.core.model.progress.ProgressSyncLocator
import com.example.unifiedreader.core.model.progress.ProgressSyncStore
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A server's place for a book that is the more recent read but behind this device's place (another device
 * opened the book at an older page, say). It is held here instead of moving the book back, and the book
 * asks before it opens: stay, or go to the server's place. While it waits, this device's place isn't
 * pushed over it.
 */
data class BackwardProgressOffer(
    val itemId: String,
    val server: ProgressSyncLocator,
    val serverFinished: Boolean,
    val serverUpdatedAtEpochMillis: Long,
    /** Where the place came from, for the question ("Katana Desk"); null: the book's own server. */
    val from: String? = null,
)

/**
 * The offers waiting for an answer. Kept in memory: every pull and push works the offer out again from
 * the server and this device, so a restart loses nothing.
 */
object BackwardProgressOffers {
    private val offersByItemId = MutableStateFlow<Map<String, BackwardProgressOffer>>(emptyMap())
    val offers: StateFlow<Map<String, BackwardProgressOffer>> = offersByItemId.asStateFlow()

    fun offer(offer: BackwardProgressOffer) = offersByItemId.update { it + (offer.itemId to offer) }

    fun current(itemId: String): BackwardProgressOffer? = offersByItemId.value[itemId]

    fun withdraw(itemId: String) {
        if (itemId in offersByItemId.value) offersByItemId.update { it - itemId }
    }

    /** For tests. */
    fun clear() = offersByItemId.update { emptyMap() }
}

/**
 * Applies a pull's [decision], except a server copy behind this device's place, which waits as an offer.
 * Any other decision ends an offer for the book: this device read on, or the server caught up.
 */
internal fun applyOrOfferRemoteProgress(
    store: ProgressSyncStore,
    item: ReadableItem,
    pageCount: Int?,
    decision: RemoteProgressSyncDecision,
    offeredBy: String? = null,
) {
    when (val asked = decision.askingBeforeGoingBack(store.getProgress(item))) {
        is RemoteProgressSyncDecision.OfferRemote -> BackwardProgressOffers.offer(asked.toOffer(item.id, offeredBy))
        else -> {
            BackwardProgressOffers.withdraw(item.id)
            applyRemoteProgressSyncDecision(store = store, item = item, pageCount = pageCount, decision = asked)
        }
    }
}

internal fun RemoteProgressSyncDecision.OfferRemote.toOffer(itemId: String, from: String? = null) = BackwardProgressOffer(
    itemId = itemId,
    server = locator,
    serverFinished = isFinished,
    serverUpdatedAtEpochMillis = updatedAtEpochMillis,
    from = from,
)

/**
 * "p. 4", or the share of the book when the page isn't known ("6%"); null when neither is. A book read by
 * the page ([LocatorType.PAGE]: PDFs, comics) keeps its page counted from 0, an EPUB the page it shows, from
 * 1. (Before 1.1.2 a PDF on page 5 was asked about as "p. 4".)
 */
internal fun progressPlaceLabel(locatorType: LocatorType, page: Int?, percent: Double?, pageCount: Int?): String? {
    val shownPage = when (locatorType) {
        LocatorType.PAGE -> page?.takeIf { it >= 0 }?.plus(1)
        LocatorType.CFI, LocatorType.PERCENT -> page?.takeIf { it > 0 }
    }
    val knownPage = shownPage
        ?: percent?.let { share -> pageCount?.takeIf { it > 0 }?.let { (share * it).roundToInt().coerceIn(1, it) } }
    return when {
        knownPage != null -> "p. $knownPage"
        percent != null -> "${(percent * PercentScale).roundToInt()}%"
        else -> null
    }
}

private const val PercentScale = 100

/** The server's place as this device saves it, once you choose to go there. */
internal fun BackwardProgressOffer.toServerSnapshot(): ReaderProgressSnapshot = ReaderProgressSnapshot(
    itemId = itemId,
    locatorType = server.locatorType,
    page = server.page,
    cfi = server.cfi,
    percent = server.percent,
    isFinished = serverFinished,
    syncState = SyncState.CLEAN,
    updatedAtEpochMillis = serverUpdatedAtEpochMillis,
)
