package com.example.unifiedreader.feature.dictionary

/**
 * WordNet's morphy: the base forms a word may be an inflection of. The pack holds the irregular
 * forms (geese -> goose, ran -> run); the regular endings are these detachment rules.
 */
object WordForms {
    private val Rules: Map<PartOfSpeech, List<Pair<String, String>>> = mapOf(
        PartOfSpeech.Noun to listOf(
            "s" to "",
            "ses" to "s",
            "xes" to "x",
            "zes" to "z",
            "ches" to "ch",
            "shes" to "sh",
            "men" to "man",
            "ies" to "y",
        ),
        PartOfSpeech.Verb to listOf(
            "s" to "",
            "ies" to "y",
            "es" to "e",
            "es" to "",
            "ed" to "e",
            "ed" to "",
            "ing" to "e",
            "ing" to "",
        ),
        PartOfSpeech.Adjective to listOf("er" to "", "est" to "", "er" to "e", "est" to "e"),
        PartOfSpeech.Adverb to emptyList(),
    )

    /** The selected text as the pack keys it: lowercase, straight apostrophes, no edge punctuation. */
    fun normalize(text: String): String = text
        .replace(RightQuote, '\'')
        .replace(LeftQuote, '\'')
        .replace('_', ' ')
        .trim()
        .trim { it in EdgePunctuation }
        .removeSuffix("'s")
        .replace(Whitespace, " ")
        .lowercase()

    /**
     * The base forms [form] may come from, as [part] of speech, in morphy's order: the irregular
     * forms first, then each rule's result. Doesn't check them against the pack, and leaves out
     * [form] itself.
     */
    fun bases(form: String, part: PartOfSpeech, irregular: List<String>): List<String> {
        val ruled = Rules.getValue(part).mapNotNull { (suffix, ending) ->
            if (form.length > suffix.length && form.endsWith(suffix)) form.dropLast(suffix.length) + ending else null
        }
        return (irregular + ruled).filter { it.isNotEmpty() && it != form }.distinct()
    }

    private const val RightQuote = '’'
    private const val LeftQuote = '‘'
    private const val EdgePunctuation = ".,;:!?\"'()[]{}“”-—–*_"
    private val Whitespace = Regex("\\s+")
}
