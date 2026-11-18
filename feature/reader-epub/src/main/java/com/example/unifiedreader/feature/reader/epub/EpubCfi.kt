package com.example.unifiedreader.feature.reader.epub

/**
 * One step of an EPUB CFI path (https://idpf.org/epub/linking/cfi/): `/index`, the id the element
 * there should have (`[id]`), and on the last step of a text position its character `:offset`.
 * Even indexes are elements (2 the first), odd ones the text between them (1 before the first).
 */
internal data class CfiStep(val index: Int, val id: String? = null, val offset: Int? = null)

/**
 * A range CFI as highlights use them, `epubcfi(/6/8!/4/2[ch1]/10,/1:5,/3:2)`: the spine item it is
 * in ([spineIndex], counting every item of the package's spine from 0) and the paths from the
 * chapter's root element to where the words start and end.
 */
internal data class EpubCfiRange(
    val spineIndex: Int,
    val start: List<CfiStep>,
    val end: List<CfiStep>,
)

/**
 * Reads and writes EPUB CFIs the way Grimmory's web reader does (foliate-js's `epubcfi.js`): a
 * range's shared steps go first, then the start's and the end's own, with the elements' ids
 * asserted, so a highlight made here reads the same there. The KOReader plugin's CFIs (no ids)
 * read too. Temporal, spatial and text assertions are read and ignored.
 */
internal object EpubCfi {
    fun parse(cfi: String): EpubCfiRange? {
        val inner = cfi.trim().removeSurrounding("epubcfi(", ")").takeIf { it.isNotEmpty() && it != cfi.trim() } ?: return null
        val tokens = tokenize(inner) ?: return null
        val sections = tokens.splitAt { it is CfiToken.Comma }
        val (start, end) = when (sections.size) {
            1 -> parsePath(sections[0]).let { path -> path to path }
            RANGE_SECTIONS -> {
                val parent = parsePath(sections[0])
                joinPaths(parent, parsePath(sections[1])) to joinPaths(parent, parsePath(sections[2]))
            }
            else -> return null
        }
        val startPackage = start.getOrNull(0)?.lastOrNull() ?: return null
        val endPackage = end.getOrNull(0)?.lastOrNull() ?: return null
        val startContent = start.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        val endContent = end.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        if (start.size > 2 || end.size > 2 || startPackage.index != endPackage.index) return null
        if (startPackage.index < 2 || startPackage.index % 2 != 0) return null
        return EpubCfiRange(spineIndex = startPackage.index / 2 - 1, start = startContent, end = endContent)
    }

    /** The range CFI from [packageSteps] (the spine and the item in it) to the [start] and [end] paths in the chapter. */
    fun formatRange(packageSteps: List<CfiStep>, start: List<CfiStep>, end: List<CfiStep>): String {
        val shared = mutableListOf<CfiStep>()
        var index = 0
        while (index < minOf(start.size, end.size)) {
            val a = start[index]
            val b = end[index]
            // As foliate-js: a step with an offset (even 0) or a different index ends the shared part.
            if (a.index != b.index || a.offset.isSet() || b.offset.isSet()) break
            shared += a
            index += 1
        }
        return buildString {
            append("epubcfi(")
            packageSteps.forEach { append(it.format()) }
            append('!')
            shared.forEach { append(it.format()) }
            append(',')
            start.drop(index).forEach { append(it.format()) }
            append(',')
            end.drop(index).forEach { append(it.format()) }
            append(')')
        }
    }

    private fun Int?.isSet(): Boolean = this != null && this != 0

    private fun CfiStep.format(): String = buildString {
        append('/').append(index)
        id?.takeIf(String::isNotEmpty)?.let { append('[').append(escape(it)).append(']') }
        if (offset != null && index % 2 == 1) append(':').append(offset)
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            if (char in ESCAPED) append('^')
            append(char)
        }
    }

    // A path is its parts between `!`s: the package's steps, then the chapter's.
    private fun parsePath(tokens: List<CfiToken>): List<List<CfiStep>> =
        tokens.splitAt { it is CfiToken.Indirection }.map { part ->
            val steps = mutableListOf<CfiStep>()
            var lastWasStep = false
            part.forEach { token ->
                when (token) {
                    is CfiToken.Step -> {
                        steps += CfiStep(token.index)
                        lastWasStep = true
                    }
                    is CfiToken.Offset -> {
                        steps.lastOrNull()?.let { steps[steps.lastIndex] = it.copy(offset = token.offset) }
                        lastWasStep = false
                    }
                    is CfiToken.Assertion -> {
                        // After a step it names the element's id; after an offset it quotes the text.
                        if (lastWasStep && token.id.isNotEmpty()) {
                            steps.lastOrNull()?.let { steps[steps.lastIndex] = it.copy(id = token.id) }
                        }
                        lastWasStep = false
                    }
                    CfiToken.Ignored -> lastWasStep = false
                    CfiToken.Comma, CfiToken.Indirection -> Unit
                }
            }
            steps
        }

    // foliate-js's concatArrays: the parent's last part continues with the start's (or end's) first.
    private fun joinPaths(parent: List<List<CfiStep>>, local: List<List<CfiStep>>): List<List<CfiStep>> {
        if (parent.isEmpty()) return local
        if (local.isEmpty()) return parent
        return parent.dropLast(1) + listOf(parent.last() + local.first()) + local.drop(1)
    }

    private fun tokenize(text: String): List<CfiToken>? {
        val tokens = mutableListOf<CfiToken>()
        var position = 0
        while (position < text.length) {
            val char = text[position]
            position += 1
            when (char) {
                '/', ':' -> {
                    val digitsEnd = text.digitsEnd(position)
                    val number = text.substring(position, digitsEnd).toIntOrNull() ?: return null
                    tokens += if (char == '/') CfiToken.Step(number) else CfiToken.Offset(number)
                    position = digitsEnd
                }
                '~', '@' -> {
                    while (position < text.length && (text[position].isDigit() || text[position] in ".:")) position += 1
                    tokens += CfiToken.Ignored
                }
                '[' -> {
                    val (content, next) = text.bracketContent(position) ?: return null
                    tokens += CfiToken.Assertion(content)
                    position = next
                }
                '!' -> tokens += CfiToken.Indirection
                ',' -> tokens += CfiToken.Comma
                else -> if (!char.isWhitespace()) return null
            }
        }
        return tokens
    }

    private fun String.digitsEnd(from: Int): Int {
        var end = from
        while (end < length && this[end].isDigit()) end += 1
        return end
    }

    // The id in `[id;s=b]` or the first text of `[text,text]`, unescaped; and where the bracket ends.
    private fun String.bracketContent(from: Int): Pair<String, Int>? {
        val content = StringBuilder()
        var stopped = false
        var position = from
        while (position < length) {
            val char = this[position]
            position += 1
            when {
                char == '^' && position < length -> {
                    if (!stopped) content.append(this[position])
                    position += 1
                }
                char == ']' -> return content.toString() to position
                char == ';' || char == ',' -> stopped = true
                !stopped -> content.append(char)
            }
        }
        return null
    }

    private fun <T> List<T>.splitAt(isSeparator: (T) -> Boolean): List<List<T>> {
        val parts = mutableListOf(mutableListOf<T>())
        forEach { item -> if (isSeparator(item)) parts += mutableListOf<T>() else parts.last() += item }
        return parts
    }

    private const val RANGE_SECTIONS = 3
    private const val ESCAPED = "^[](),;="
}

private sealed interface CfiToken {
    data class Step(val index: Int) : CfiToken

    data class Offset(val offset: Int) : CfiToken

    data class Assertion(val id: String) : CfiToken

    data object Indirection : CfiToken

    data object Comma : CfiToken

    data object Ignored : CfiToken
}
