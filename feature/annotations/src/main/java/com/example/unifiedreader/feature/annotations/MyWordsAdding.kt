package com.example.unifiedreader.feature.annotations

/**
 * The Dictionary card's Add to My words: saves [word] with its sentence and place, as Add to
 * dictionary does, and keeps [meaning] (the dictionary's first definition) as its meaning.
 */
suspend fun ReaderAnnotationStore.addToMyWords(
    book: AnnotatedBook,
    word: String,
    sentence: String?,
    place: AnnotationPlace?,
    meaning: String?,
): SavedWord {
    val saved = saveWord(book = book, word = word, sentence = sentence, place = place)
    val kept = meaning?.trim()?.takeIf(String::isNotEmpty) ?: return saved
    setWordNote(saved.id, kept)
    return saved.copy(note = kept)
}
