package com.example.unifiedreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.unifiedreader.core.model.ReadStatus
import com.example.unifiedreader.core.model.ReadStatusStore
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.readStatusChoices
import com.example.unifiedreader.feature.library.LibraryBookStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A book's reading status for its page in the library, and setting one ([SetReadStatusUseCase]). */
@HiltViewModel
class AppBookPageStatusViewModel @Inject constructor(
    private val store: ReadStatusStore,
    private val setReadStatus: SetReadStatusUseCase,
) : ViewModel() {
    /** The status [itemId] was given, here or on its server; null for none. */
    fun own(itemId: String): Flow<ReadStatus?> = store.records.map { records -> records[itemId]?.status }.distinctUntilChanged()

    fun ownNow(itemId: String): ReadStatus? = store.get(itemId)?.status

    fun select(item: ReadableItem, status: ReadStatus) {
        viewModelScope.launch { setReadStatus(item, status) }
    }
}

/**
 * The book page's reading status for [item] ([LibraryBookStatus]): its own status as it changes (picked here,
 * moved on by reading, or come down from its server), the statuses it takes, and setting one.
 */
@Composable
internal fun rememberBookPageStatus(
    item: ReadableItem,
    viewModel: AppBookPageStatusViewModel = hiltViewModel(),
): LibraryBookStatus {
    val own by remember(viewModel, item.id) { viewModel.own(item.id) }
        .collectAsStateWithLifecycle(initialValue = remember(viewModel, item.id) { viewModel.ownNow(item.id) })
    return remember(item, own, viewModel) {
        LibraryBookStatus(own = own, choices = item.readStatusChoices(), onSelect = { status -> viewModel.select(item, status) })
    }
}
