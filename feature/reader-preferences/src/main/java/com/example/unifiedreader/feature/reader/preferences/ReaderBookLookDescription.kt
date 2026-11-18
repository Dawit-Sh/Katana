package com.example.unifiedreader.feature.reader.preferences

/** One setting of a saved look, in the reading settings' words: "Font", "Literata". */
data class ReaderBookLookLine(
    val label: String,
    val value: String,
)

/**
 * The look "Use these for new books" saved, as Settings shows it: [summary] for the hub's row
 * ("Literata, 110%, Normal margins") and [lines] for its page. Empty lines: nothing is saved.
 */
data class ReaderBookLookDescription(
    val summary: String,
    val lines: List<ReaderBookLookLine>,
) {
    val isSaved: Boolean
        get() = lines.isNotEmpty()

    companion object {
        val NotSaved = ReaderBookLookDescription(summary = "", lines = emptyList())
    }
}
