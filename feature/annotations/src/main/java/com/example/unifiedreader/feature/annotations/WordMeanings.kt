package com.example.unifiedreader.feature.annotations

/**
 * Where a saved word's short meaning comes from, for the one line under it on My words. Nothing
 * gives one yet ([None]); a dictionary can plug in here later without the page changing.
 */
interface WordMeanings {
    /** A one-line meaning of [word] in [language] (an ISO 639 code such as "en"; null when unknown); null when there is none. */
    suspend fun meaning(word: String, language: String?): String?

    /** No source: every word goes without a meaning line. */
    object None : WordMeanings {
        override suspend fun meaning(word: String, language: String?): String? = null
    }
}

/** The meanings [WordMeanings] has for [words], by word id, each on one line; words without one are left out. */
suspend fun WordMeanings.meaningsOf(words: List<SavedWord>, language: String?): Map<String, String> =
    if (this === WordMeanings.None) {
        emptyMap()
    } else {
        words.mapNotNull { word ->
            meaning(word.word, language)?.oneLine()?.takeIf(String::isNotBlank)?.let { meaning -> word.id to meaning }
        }.toMap()
    }
