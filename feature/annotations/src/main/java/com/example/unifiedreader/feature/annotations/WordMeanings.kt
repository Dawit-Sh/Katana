package com.example.unifiedreader.feature.annotations

/** What a saved word means, for My words: from a dictionary on this device, or nothing. */
interface WordMeanings {
    /** The word's meaning in [language] (a code such as "en"), or null when none is known. */
    suspend fun meaning(word: String, language: String): String?

    /** Knows no meanings: My words shows the word without one. */
    object None : WordMeanings {
        override suspend fun meaning(word: String, language: String): String? = null
    }
}
