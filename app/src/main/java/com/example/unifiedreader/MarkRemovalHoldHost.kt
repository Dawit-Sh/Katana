package com.example.unifiedreader

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.unifiedreader.feature.annotations.MarkRemovalHold
import com.example.unifiedreader.feature.annotations.MarkRemovalHolds
import com.example.unifiedreader.feature.reader.core.ReaderWorkCoordinator
import com.example.unifiedreader.ui.components.AppDialog
import com.example.unifiedreader.ui.components.AppDialogConfirmButton
import com.example.unifiedreader.ui.components.AppDialogDismissButton
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The question about an open book's marks that Grimmory lost many of at once, until it is answered. */
@HiltViewModel
class MarkRemovalHoldViewModel @Inject constructor(
    private val coordinator: AppHighlightSyncCoordinator,
    readerWork: ReaderWorkCoordinator,
) : ViewModel() {
    // Books whose question was put aside while they are open: it comes again when they next open.
    private val putAside = MutableStateFlow<Set<String>>(emptySet())

    val prompt: StateFlow<MarkRemovalHold?> =
        combine(MarkRemovalHolds.holds, readerWork.activeReaderItemIds, putAside) { holds, open, aside ->
            open.firstNotNullOfOrNull { itemId -> holds[itemId]?.takeIf { itemId !in aside } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    init {
        viewModelScope.launch { readerWork.activeReaderItemIds.collect { open -> putAside.update { it intersect open } } }
    }

    fun answer(hold: MarkRemovalHold, removeHere: Boolean) = coordinator.answerRemovalHold(hold.itemId, removeHere)

    fun putAside(hold: MarkRemovalHold) = putAside.update { it + hold.itemId }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Asks, over the open book, what to do with marks Grimmory no longer has when it lost many at once
 * (a restored backup, say): "Remove here" or "Put back". Back or a tap outside puts it aside: nothing is
 * removed, and it asks again the next time the book opens.
 */
@Composable
internal fun MarkRemovalHoldHost(viewModel: MarkRemovalHoldViewModel = hiltViewModel()) {
    val hold by viewModel.prompt.collectAsStateWithLifecycle()
    val current = hold ?: return
    AppDialog(
        onDismissRequest = { viewModel.putAside(current) },
        title = { Text(current.bookTitle) },
        text = { Text(MarkRemovalCopy.question(current.count)) },
        dismissButton = {
            AppDialogDismissButton(text = MarkRemovalCopy.RemoveHere, onClick = { viewModel.answer(current, removeHere = true) })
        },
        confirmButton = {
            AppDialogConfirmButton(text = MarkRemovalCopy.PutBack, onClick = { viewModel.answer(current, removeHere = false) })
        },
    )
}

internal object MarkRemovalCopy {
    const val RemoveHere = "Remove here"
    const val PutBack = "Put back"

    fun question(count: Int): String =
        "Grimmory no longer has $count of this book's marks. Remove them here too, or put them back on Grimmory?"
}
