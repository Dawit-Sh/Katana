package com.example.unifiedreader.feature.annotations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.unifiedreader.ui.components.AppButton
import com.example.unifiedreader.ui.components.AppDialog
import com.example.unifiedreader.ui.components.AppDialogConfirmButton
import com.example.unifiedreader.ui.components.AppDialogDismissButton
import com.example.unifiedreader.ui.components.AppEmptyState
import com.example.unifiedreader.ui.components.AppIcons
import com.example.unifiedreader.ui.components.AppLayout
import com.example.unifiedreader.ui.components.AppPanel
import com.example.unifiedreader.ui.components.AppProgressBar
import com.example.unifiedreader.ui.components.AppTextField
import com.example.unifiedreader.ui.components.AppTonalButton
import com.example.unifiedreader.ui.components.appGutter

/**
 * The saved words as flashcards: the word large, the sentence it came from with the word marked,
 * the book, the reader's own note, Look it up; then Again (it comes round later in this round) or
 * I know it (it rests longer each time). A round takes the words that are due; with none due, it
 * offers every word.
 */
@Composable
internal fun WordReviewPage(
    state: BookmarksState,
    library: MarkedLibrary,
    actions: BookmarksActions,
    contentTopInset: Dp,
    modifier: Modifier = Modifier,
) {
    WordReviewPage(
        words = library.words,
        round = state.round,
        onRound = { state.round = it },
        onBack = { state.back() },
        backLabel = BookmarksCopy.Back,
        actions = actions,
        contentTopInset = contentTopInset,
        modifier = modifier,
    )
}

/**
 * The flashcards over [words] (My words' list, or the Bookmarks page's every word): [round] is the
 * round under way, which [onRound] replaces; [onBack] leaves, its button saying [backLabel].
 * [from] says where the words come from ("from every book").
 */
@Composable
internal fun WordReviewPage(
    words: List<SavedWord>,
    round: WordReviewRound?,
    onRound: (WordReviewRound?) -> Unit,
    onBack: () -> Unit,
    backLabel: String,
    actions: WordStudyActions,
    contentTopInset: Dp,
    modifier: Modifier = Modifier,
    from: String = BookmarksCopy.FromEveryBook,
) {
    val latestOnRound by rememberUpdatedState(onRound)
    val latestWords by rememberUpdatedState(words)
    LaunchedEffect(round == null) {
        if (round == null) {
            latestOnRound(WordReviewRound.of(WordReviewSchedule.round(latestWords, System.currentTimeMillis())))
        }
    }
    val active = round ?: return
    val gutter = appGutter()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = gutter, top = contentTopInset + AppLayout.contentTop, end = gutter, bottom = AppLayout.contentBottom),
        verticalArrangement = Arrangement.spacedBy(AppLayout.gridGap),
    ) {
        val current = active.current?.let { id -> words.firstOrNull { it.id == id } }
        when {
            words.isEmpty() -> AppEmptyState(title = BookmarksCopy.NoWordsTitle, body = BookmarksCopy.NoWordsBody)
            active.total == 0 -> AppEmptyState(
                title = BookmarksCopy.NothingDueTitle,
                body = BookmarksCopy.NothingDueBody,
                actions = {
                    AppButton(onClick = { onRound(everyWord(words)) }) {
                        Text(BookmarksCopy.ReviewAll)
                    }
                },
            )
            active.isFinished || current == null -> AppEmptyState(
                title = BookmarksCopy.RoundDoneTitle,
                body = BookmarksCopy.roundDone(active.known),
                actions = {
                    AppTonalButton(onClick = onBack) { Text(backLabel) }
                    AppButton(onClick = { onRound(everyWord(words)) }) {
                        Text(BookmarksCopy.ReviewAll)
                    }
                },
            )
            else -> ReviewCard(
                word = current,
                round = active,
                from = from,
                onNote = { note -> actions.setWordNote(current.id, note) },
                onAnswer = { known ->
                    actions.reviewWord(current.id, known)
                    onRound(active.answer(known))
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReviewCard(
    word: SavedWord,
    round: WordReviewRound,
    from: String,
    onNote: (String) -> Unit,
    onAnswer: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var editingNote by rememberSaveable(word.id) { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AppLayout.gridGap)) {
        ReviewCardBody(
            word = word,
            round = round,
            from = from,
            onEditNote = { editingNote = true },
            onLookUp = { context.lookUpWord(word.word) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AppLayout.gridGap)) {
            AppTonalButton(onClick = { onAnswer(false) }, modifier = Modifier.weight(1f).heightIn(min = AnswerHeight)) {
                Text(BookmarksCopy.Again)
            }
            AppButton(onClick = { onAnswer(true) }, modifier = Modifier.weight(1f).heightIn(min = AnswerHeight)) {
                Text(BookmarksCopy.KnowIt)
            }
        }
        Text(
            text = BookmarksCopy.ReviewHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (editingNote) {
        WordNoteDialog(
            word = word,
            onDismiss = { editingNote = false },
            onSave = { note ->
                onNote(note)
                editingNote = false
            },
        )
    }
}

/** The progress line and bar, then the card: the word, its sentence, its book, the note and Look it up. */
@Composable
private fun ColumnScope.ReviewCardBody(
    word: SavedWord,
    round: WordReviewRound,
    from: String,
    onEditNote: () -> Unit,
    onLookUp: () -> Unit,
) {
    Text(
        text = BookmarksCopy.roundProgress(round.position, round.total, from),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AppProgressBar(
        fraction = round.known.toFloat() / round.total.coerceAtLeast(1),
        modifier = Modifier.fillMaxWidth(),
    )
    AppPanel(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AppLayout.dialogInset),
            verticalArrangement = Arrangement.spacedBy(AppLayout.gridGap),
        ) {
            Text(
                text = BookmarksCopy.SavedWordLabel.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = word.word,
                style = MaterialTheme.typography.displayMedium.copy(fontSize = WordSize),
                fontFamily = rememberQuoteFontFamily(),
                fontWeight = FontWeight.Bold,
            )
            word.sentence?.let { sentence -> SentenceQuote(sentence = sentence, word = word.word) }
            Text(
                text = listOfNotNull(word.book.title, word.book.author, BookmarksCopy.saved(word.createdAtEpochMillis))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            NoteCard(note = word.note, onClick = onEditNote)
            AppTonalButton(onClick = onLookUp) {
                Icon(imageVector = AppIcons.Search, contentDescription = null, modifier = Modifier.size(SmallIcon))
                Spacer(Modifier.width(AppLayout.headingGap))
                Text(BookmarksCopy.LookUp)
            }
        }
    }
}

/** The sentence the word came from, in the book's face, with the word picked out. */
@Composable
private fun SentenceQuote(sentence: String, word: String) {
    Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AppLayout.menuInset)) {
        Spacer(
            modifier = Modifier
                .width(QuoteBarWidth)
                .fillMaxHeight()
                .padding(vertical = QuoteBarInset)
                .background(MarkTint.Bookmark),
        )
        Text(
            text = markedSentence(sentence, word, MaterialTheme.colorScheme.primary),
            style = MaterialTheme.typography.titleMedium.copy(lineHeight = SentenceLineHeight),
            fontFamily = rememberQuoteFontFamily(),
            fontWeight = FontWeight.Normal,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "“…solar panels on the roof…”" with the word in bold and the accent. */
private fun markedSentence(sentence: String, word: String, accent: Color): AnnotatedString {
    val text = "“${sentence.oneLine()}”"
    val at = text.indexOf(word, ignoreCase = true)
    return buildAnnotatedString {
        if (at < 0) {
            append(text)
        } else {
            append(text.substring(0, at))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Normal, color = accent)) {
                append(text.substring(at, at + word.length))
            }
            append(text.substring(at + word.length))
        }
    }
}

@Composable
private fun NoteCard(note: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(AppLayout.headingGap),
    ) {
        Text(
            text = BookmarksCopy.YourNote,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (note == null) {
            Text(
                text = BookmarksCopy.NotePlaceholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            NoteBox(note)
        }
    }
}

@Composable
internal fun WordNoteDialog(word: SavedWord, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable(word.id) { mutableStateOf(word.note.orEmpty()) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("${BookmarksCopy.NoteDialogTitle}: ${word.word}") },
        text = {
            AppTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(BookmarksCopy.NoteFieldPlaceholder) },
                minLines = NoteFieldLines,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        dismissButton = { AppDialogDismissButton(text = BookmarksCopy.Cancel, onClick = onDismiss) },
        confirmButton = { AppDialogConfirmButton(text = BookmarksCopy.Save, onClick = { onSave(text) }) },
    )
}

/** A round of every saved word, due or not. */
private fun everyWord(words: List<SavedWord>): WordReviewRound =
    WordReviewRound.of(WordReviewSchedule.round(words, System.currentTimeMillis(), all = true))

private const val NoteFieldLines = 3
private val WordSize = 52.sp
private val SentenceLineHeight = 28.sp
private val AnswerHeight = 56.dp
private val QuoteBarWidth = 3.dp
private val QuoteBarInset = 2.dp
