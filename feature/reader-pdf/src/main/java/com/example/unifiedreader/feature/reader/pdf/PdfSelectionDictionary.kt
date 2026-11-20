package com.example.unifiedreader.feature.reader.pdf

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.unifiedreader.feature.annotations.AnnotatedBook
import com.example.unifiedreader.feature.annotations.PageTextSpot
import com.example.unifiedreader.feature.annotations.ReaderAnnotationStore
import com.example.unifiedreader.feature.annotations.addToMyWords
import com.example.unifiedreader.feature.annotations.pageHighlightPlace
import com.example.unifiedreader.feature.dictionary.DictionaryRequest
import com.example.unifiedreader.feature.dictionary.WordForms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The selection menu's Dictionary in a PDF or DjVu book: the card on the selected words, whose Add
 * to My words saves them with their sentence and page, as the EPUB reader's does.
 */
@Stable
internal class PdfSelectionDictionary(
    private val book: AnnotatedBook,
    private val store: ReaderAnnotationStore,
    private val scope: CoroutineScope,
    private val pageCount: () -> Int,
) {
    /** The word the Dictionary card shows, null while it is closed. */
    var request by mutableStateOf<DictionaryRequest?>(null)

    fun lookUp(selection: PdfTextSelection) {
        val word = selection.text.trim().trim { !it.isLetterOrDigit() }
        if (WordForms.normalize(word).isEmpty()) return
        val spot = PageTextSpot(
            pageIndex = selection.pageIndex,
            start = selection.start,
            end = selection.end,
            rects = selection.rects,
            turn = selection.page.turn,
        )
        val place = pageHighlightPlace(spot, pageCount = pageCount(), pageCharCount = selection.page.length)
        val sentence = sentenceAround(selection.page.text, selection.start, selection.end)
        request = DictionaryRequest(word = word) { meaning ->
            scope.launch {
                store.addToMyWords(book = book, word = word, sentence = sentence, place = place, meaning = meaning)
            }
        }
    }
}

/** The sentence on the page around [start]..[end]: from the last sentence end before it to the next after it. */
internal fun sentenceAround(text: String, start: Int, end: Int): String? {
    if (text.isEmpty()) return null
    val from = start.coerceIn(0, text.length)
    val to = end.coerceIn(from, text.length)
    val begin = (from - 1 downTo 0).firstOrNull { text[it] in SentenceEnds }?.plus(1) ?: 0
    val finish = (to until text.length).firstOrNull { text[it] in SentenceEnds }?.plus(1) ?: text.length
    return text.substring(begin, finish).replace(Whitespace, " ").trim().takeIf(String::isNotEmpty)
}

private const val SentenceEnds = ".!?…"
private val Whitespace = Regex("\\s+")
