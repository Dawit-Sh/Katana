package com.example.unifiedreader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.unifiedreader.core.model.DeviceClock
import com.example.unifiedreader.core.model.LocatorType
import com.example.unifiedreader.feature.reader.core.ReaderProgressSnapshot
import com.example.unifiedreader.feature.reader.core.ReaderSessionStore
import com.example.unifiedreader.ui.components.AppStatusTone
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether the Katana Desk sheet is open: one sheet for Settings › Sources and the Local library's ⋮ alike. */
@Singleton
class KatanaDeskSheetState @Inject constructor() {
    private val openState = MutableStateFlow(false)
    val open: StateFlow<Boolean> = openState.asStateFlow()

    fun show() {
        openState.value = true
    }

    fun hide() {
        openState.value = false
    }
}

/** The Katana Desk row in Settings › Sources: its line, and the dot beside it. */
data class KatanaDeskRowStatus(val text: String, val tone: AppStatusTone?)

/** What the Sources row says: not paired, paired and when it last synced, syncing, or the PC out of reach. */
internal fun katanaDeskRowStatus(
    paired: KatanaDeskPaired?,
    syncing: Boolean,
    problem: String?,
    syncedAtEpochMillis: Long,
    timeOf: (Long) -> String = ::syncedWhen,
): KatanaDeskRowStatus = when {
    paired == null -> KatanaDeskRowStatus("Not paired · sync with your PC on this Wi-Fi", null)
    syncing -> KatanaDeskRowStatus("Syncing…", AppStatusTone.Neutral)
    problem != null -> KatanaDeskRowStatus("Paired with ${paired.name} · can't reach it now", AppStatusTone.Problem)
    syncedAtEpochMillis > 0 -> KatanaDeskRowStatus("Paired with ${paired.name} · synced ${timeOf(syncedAtEpochMillis)}", AppStatusTone.Good)
    else -> KatanaDeskRowStatus("Paired with ${paired.name}", AppStatusTone.Good)
}

/** A time today ("2:14 PM", as the device shows times), else the day. */
internal fun syncedWhen(epochMillis: Long, now: Long = System.currentTimeMillis()): String =
    if (android.text.format.DateUtils.isToday(epochMillis) || now - epochMillis < java.util.concurrent.TimeUnit.HOURS.toMillis(1)) {
        DeviceClock.format(epochMillis)
    } else {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(epochMillis))
    }

/** Where a book stands, in words for the note after a sync: its page when it has one ("p. 7"), else how far through. */
internal fun placeInWords(place: ReaderProgressSnapshot?): String? {
    val page = place?.page?.takeIf { place.locatorType == LocatorType.PAGE }
    val percent = place?.percent
    return when {
        page != null -> "p. ${page + 1}"
        percent != null -> "${(percent.coerceIn(0.0, 1.0) * PERCENT_OF_BOOK).toInt()}%"
        else -> null
    }
}

private const val PERCENT_OF_BOOK = 100

/** Pairing with Katana Desk, how its sync stands, and the sheet that shows both. */
@HiltViewModel
class KatanaDeskViewModel @Inject constructor(
    private val sync: KatanaDeskSync,
    private val sheet: KatanaDeskSheetState,
    private val places: ReaderSessionStore,
    finder: KatanaDeskFinder,
    connection: KatanaDeskConnection,
) : ViewModel() {
    val paired = connection.paired
    val links = connection.links
    val lastSyncedAt = connection.lastSyncedAt
    val syncing = sync.syncing
    val problem = sync.problem
    val lastResult = sync.lastResult
    val activity = sync.activity
    val events = sync.events
    val sheetOpen = sheet.open

    /** The question before more than five books move one way. */
    val question = sync.moves.question

    /** The pairing screen of the sheet. */
    val pairing = KatanaDeskPairing(finder, sync, viewModelScope)

    /** The Katana Desk row in Settings › Sources. */
    val sourceRow: StateFlow<KatanaDeskRowStatus> = combine(paired, syncing, problem, lastSyncedAt) { desk, busy, failed, at ->
        katanaDeskRowStatus(desk, busy, failed, at)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, katanaDeskRowStatus(paired.value, false, null, lastSyncedAt.value))

    fun openSheet() = sheet.show()

    fun closeSheet() {
        sheet.hide()
        pairing.stop()
    }

    fun syncNow() {
        viewModelScope.launch { sync.syncNow() }
    }

    fun forget() {
        sync.forget()
        pairing.reset()
    }

    /** Where [itemId] stands now, in words for the note after a sync ("p. 7", "53%"); null when it has no place. */
    fun placeOf(itemId: String): String? = placeInWords(places.progressByItemIdFlow.value[itemId])

    fun answer(yes: Boolean) = sync.moves.answer(yes)

    override fun onCleared() = pairing.stop()
}
