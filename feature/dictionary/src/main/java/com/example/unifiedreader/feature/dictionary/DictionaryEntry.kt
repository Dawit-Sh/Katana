package com.example.unifiedreader.feature.dictionary

/** A part of speech as the pack stores it ([code]) and the card names it ([label]). */
enum class PartOfSpeech(val code: String, val label: String) {
    Noun("n", "noun"),
    Verb("v", "verb"),
    Adjective("a", "adjective"),
    Adverb("r", "adverb"),
    ;

    companion object {
        fun fromCode(code: String): PartOfSpeech? = entries.firstOrNull { it.code == code }
    }
}

/** One sense: its definition and, when WordNet has one, an example of it in use. */
data class DictionarySense(
    val definition: String,
    val example: String?,
)

/** The senses of one base form ([lemma]) as one part of speech, the most common first. */
data class DictionaryMeaning(
    val lemma: String,
    val partOfSpeech: PartOfSpeech,
    val senses: List<DictionarySense>,
)

/** What the dictionary says about a selected [word]: its meanings, by base form and part of speech. */
data class DictionaryEntry(
    val word: String,
    val meanings: List<DictionaryMeaning>,
) {
    /** The first definition, which Add to My words keeps as the word's meaning. */
    val firstDefinition: String?
        get() = meanings.firstOrNull()?.senses?.firstOrNull()?.definition
}

/** The answer to a lookup. */
sealed interface DictionaryLookup {
    /** No dictionary on this device: the card offers Settings. */
    data object NotInstalled : DictionaryLookup

    data class NotFound(val word: String) : DictionaryLookup

    data class Found(val entry: DictionaryEntry) : DictionaryLookup
}
