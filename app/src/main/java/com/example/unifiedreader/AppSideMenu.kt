package com.example.unifiedreader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.unifiedreader.core.model.SourceType
import com.example.unifiedreader.feature.annotations.KeptWord
import com.example.unifiedreader.feature.annotations.SavedWord
import com.example.unifiedreader.feature.annotations.WordOfTheDay
import com.example.unifiedreader.feature.annotations.WordOfTheDayCard
import com.example.unifiedreader.feature.annotations.knownOn
import com.example.unifiedreader.feature.journal.ReadingCardBook
import com.example.unifiedreader.feature.journal.readingCardBook
import com.example.unifiedreader.feature.journal.rememberLocalToday
import com.example.unifiedreader.feature.navigation.navigationSourceCounts
import com.example.unifiedreader.feature.settings.LocalReadingGoalStore
import com.example.unifiedreader.journal.ReadingGoal
import com.example.unifiedreader.ui.theme.UnifiedReaderThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The side menu's parts that come from the app: Katana's mark, the daily goal, the book being read,
// the sources' book counts and the word of the day.

/** Katana's launcher icon, small, before its name at the top of the side menu. */
@Composable
internal fun KatanaMenuLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(MenuLogoSize)
            .clip(RoundedCornerShape(UnifiedReaderThemeTokens.presentation.innerCornerRadius))
            .background(colorResource(R.color.ic_launcher_background)),
        contentAlignment = Alignment.Center,
    ) {
        // The icon's foreground is drawn for a 108 dp layer whose middle 72 shows: scaled so that middle fills the box.
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(MenuLogoSize * LAUNCHER_LAYER / LAUNCHER_VISIBLE),
        )
    }
}

/** The daily reading goal in minutes, and how to change it; the default while there is no store. */
@Composable
internal fun rememberReadingGoal(): Pair<Int, ((Int) -> Unit)?> {
    val store = LocalReadingGoalStore.current ?: return ReadingGoal.DEFAULT_MINUTES to null
    val goal by store.goalFlow.collectAsStateWithLifecycle()
    return goal to store::setGoal
}

/** The book being read across every source, for the Reading card; worked out off the main thread. */
@Composable
internal fun rememberReadingCardBook(uiState: AppUiState): ReadingCardBook? {
    val book by produceState<ReadingCardBook?>(null, uiState.items, uiState.progressByItemId) {
        value = withContext(Dispatchers.Default) { readingCardBook(uiState.items, uiState.progressByItemId) }
    }
    return book
}

/** How many books each loaded source holds, for the menu's rows; worked out off the main thread. */
@Composable
internal fun rememberSourceCounts(uiState: AppUiState): Map<SourceType, Int> {
    val counts by produceState(emptyMap<SourceType, Int>(), uiState.items, uiState.hydratedSources) {
        value = withContext(Dispatchers.Default) { navigationSourceCounts(uiState.items, uiState.hydratedSources) }
    }
    return counts
}

/**
 * The side menu's word of the day: a word from My words picked by the date (or kept after "another
 * word"), the same all day; null while My words is empty. [onReview] opens the review on it.
 */
@Composable
internal fun rememberWordOfTheDayCard(
    myWords: AppMyWordsScreen?,
    onReview: (SavedWord) -> Unit,
): (@Composable () -> Unit)? = if (myWords == null) null else rememberWordOfTheDayCardFrom(myWords, onReview)

@Composable
private fun rememberWordOfTheDayCardFrom(
    myWords: AppMyWordsScreen,
    onReview: (SavedWord) -> Unit,
): (@Composable () -> Unit)? {
    val words by myWords.words.collectAsStateWithLifecycle()
    val kept by myWords.keptWord.collectAsStateWithLifecycle()
    val keptRead by myWords.keptWordRead.collectAsStateWithLifecycle()
    val today = rememberLocalToday()
    val word = remember(words, today, kept) { words?.let { list -> WordOfTheDay.pick(list, today, kept) } }
    // Once shown, the day's word stays the day's word, even as words are saved or removed.
    LaunchedEffect(word?.id, today, keptRead) {
        if (keptRead && word != null) myWords.keepWordOfTheDay(KeptWord(today, word.id))
    }
    val meaning by produceState<String?>(null, word?.id, word?.note) {
        value = word?.let { myWords.meaningOf(it) }
    }
    val canMoveOn = (words?.size ?: 0) > 1
    return word?.let { shown ->
        {
            WordOfTheDayCard(
                word = shown,
                meaning = meaning,
                knownToday = shown.knownOn(today),
                onReview = { onReview(shown) },
                onKnow = { myWords.reviewWord(shown.id, known = true) },
                onNext = if (canMoveOn) {
                    { myWords.nextWordOfTheDay(today, shown) }
                } else {
                    null
                },
            )
        }
    }
}

private val MenuLogoSize: Dp = 26.dp
private const val LAUNCHER_LAYER = 108f
private const val LAUNCHER_VISIBLE = 72f
