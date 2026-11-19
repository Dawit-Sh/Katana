package com.example.unifiedreader.feature.annotations

/**
 * Whether two places in EPUB CFI form are the same words, though written differently: Grimmory's
 * web reader asserts element ids (`/2[ch1]`) where the grimmory.koplugin doesn't, and the two may
 * split a range's shared steps differently. Compares the full start and end paths, ids left out.
 */
internal fun sameCfiPlace(a: String?, b: String?): Boolean {
    val first = cfiPlaceKey(a) ?: return false
    return first == cfiPlaceKey(b)
}

/** The chapter (spine item) a CFI is in, as its package steps without ids: `/6/14`. */
internal fun cfiChapterKey(cfi: String?): String? = cfiPlaceKey(cfi)?.substringBefore('!')?.takeIf(String::isNotEmpty)

/** A highlight's words with spaces and letter case left out, to tell the same words in two copies. */
internal fun wordsKey(text: String): String = buildString(text.length) {
    text.forEach { char -> if (!char.isLeftOutOfWords()) append(char.lowercaseChar()) }
}

// Spaces, soft hyphens and zero-width marks, which two copies of the same words may differ in.
private fun Char.isLeftOutOfWords(): Boolean = when (this) {
    '\u00AD', '\uFEFF', in '\u200B'..'\u200D' -> true
    else -> isWhitespace()
}

// "start;end", each the full path without id or text assertions; null for what isn't a CFI.
private fun cfiPlaceKey(cfi: String?): String? {
    val inner = cfi?.trim()?.takeIf { it.startsWith("epubcfi(") && it.endsWith(")") }
        ?.removePrefix("epubcfi(")?.removeSuffix(")")
        ?: return null
    val parts = withoutAssertions(inner).split(',')
    return when (parts.size) {
        1 -> "${parts[0]};${parts[0]}"
        RANGE_PARTS -> "${parts[0]}${parts[1]};${parts[0]}${parts[2]}"
        else -> null
    }
}

// The CFI without its `[...]` assertions (escapes with `^` read), so ids and quoted text don't count.
private fun withoutAssertions(cfi: String): String = buildString {
    var inBrackets = false
    var position = 0
    while (position < cfi.length) {
        val char = cfi[position]
        when {
            char == '^' -> position += 1
            char == '[' -> inBrackets = true
            char == ']' -> inBrackets = false
            !inBrackets && !char.isWhitespace() -> append(char)
        }
        position += 1
    }
}

private const val RANGE_PARTS = 3
