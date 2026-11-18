package com.example.unifiedreader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.journal.JournalMarks
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Hands the reader's highlights and saved words to the journal, for its Highlights this month. */
@HiltViewModel
class AppJournalMarksViewModel @Inject constructor(
    store: ReaderAnnotationStore,
) : ViewModel() {
    val marks: StateFlow<JournalMarks> = combine(store.allHighlights, store.allWords, ::JournalMarks)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopSharingAfterMillis), JournalMarks())
}

/** Every highlight and saved word while the journal shows, kept up to date. */
@Composable
internal fun rememberJournalMarks(viewModel: AppJournalMarksViewModel = hiltViewModel()): JournalMarks {
    val marks by viewModel.marks.collectAsStateWithLifecycle()
    return marks
}

private const val StopSharingAfterMillis = 5_000L
