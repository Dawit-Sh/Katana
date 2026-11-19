package com.example.unifiedreader.feature.reader.epub

/** A piece of an EPUB CFI as foliate-js's tokenizer reads it. */
internal sealed interface CfiToken {
    data class Step(val index: Int) : CfiToken

    data class Offset(val offset: Int) : CfiToken

    /** `[...]`: an element's id after a step, quoted text after an offset (escapes read, parameters left out). */
    data class Assertion(val id: String) : CfiToken

    data object Indirection : CfiToken

    data object Comma : CfiToken

    /** A temporal or spatial offset, which highlights don't use. */
    data object Ignored : CfiToken
}

/** The tokens of a CFI's inside (without `epubcfi(` and `)`), or null when it isn't one. */
internal fun tokenizeCfi(text: String): List<CfiToken>? {
    val tokens = mutableListOf<CfiToken>()
    var position = 0
    while (position < text.length) {
        val read = readCfiToken(text, position) ?: return null
        read.token?.let(tokens::add)
        position = read.next
    }
    return tokens
}

/** A path, as its parts between `!`s: the package's steps, then the chapter's. */
internal fun parseCfiPath(tokens: List<CfiToken>): List<List<CfiStep>> =
    tokens.splitAt { it is CfiToken.Indirection }.map { part ->
        val steps = mutableListOf<CfiStep>()
        var lastWasStep = false
        part.forEach { token ->
            when (token) {
                is CfiToken.Step -> steps += CfiStep(token.index)
                is CfiToken.Offset -> steps.lastOrNull()?.let { steps[steps.lastIndex] = it.copy(offset = token.offset) }
                // After a step it names the element's id; after an offset it quotes the text.
                is CfiToken.Assertion -> if (lastWasStep && token.id.isNotEmpty()) {
                    steps.lastOrNull()?.let { steps[steps.lastIndex] = it.copy(id = token.id) }
                }
                CfiToken.Ignored, CfiToken.Comma, CfiToken.Indirection -> Unit
            }
            lastWasStep = token is CfiToken.Step
        }
        steps
    }

/** foliate-js's concatArrays: the parent's last part goes on with the start's (or end's) first. */
internal fun joinCfiPaths(parent: List<List<CfiStep>>, local: List<List<CfiStep>>): List<List<CfiStep>> = when {
    parent.isEmpty() -> local
    local.isEmpty() -> parent
    else -> parent.dropLast(1) + listOf(parent.last() + local.first()) + local.drop(1)
}

internal fun <T> List<T>.splitAt(isSeparator: (T) -> Boolean): List<List<T>> {
    val parts = mutableListOf(mutableListOf<T>())
    forEach { item -> if (isSeparator(item)) parts += mutableListOf<T>() else parts.last() += item }
    return parts
}

private class ReadCfiToken(val token: CfiToken?, val next: Int)

private fun readCfiToken(text: String, at: Int): ReadCfiToken? {
    val next = at + 1
    return when (val char = text[at]) {
        '/', ':' -> text.numberAt(next)?.let { (number, end) ->
            ReadCfiToken(if (char == '/') CfiToken.Step(number) else CfiToken.Offset(number), end)
        }
        '~', '@' -> ReadCfiToken(CfiToken.Ignored, text.skipping(next) { it.isDigit() || it in ".:" })
        '[' -> text.bracketContent(next)?.let { (content, end) -> ReadCfiToken(CfiToken.Assertion(content), end) }
        '!' -> ReadCfiToken(CfiToken.Indirection, next)
        ',' -> ReadCfiToken(CfiToken.Comma, next)
        else -> ReadCfiToken(null, next).takeIf { char.isWhitespace() }
    }
}

private fun String.numberAt(from: Int): Pair<Int, Int>? {
    val end = skipping(from, Char::isDigit)
    return substring(from, end).toIntOrNull()?.let { number -> number to end }
}

private fun String.skipping(from: Int, skipped: (Char) -> Boolean): Int {
    var end = from
    while (end < length && skipped(this[end])) end += 1
    return end
}

// The id in `[id;s=b]` or the first text of `[text,text]`, unescaped; and where the bracket ends.
private fun String.bracketContent(from: Int): Pair<String, Int>? {
    val content = StringBuilder()
    var stopped = false
    var position = from
    var end: Int? = null
    while (position < length && end == null) {
        val char = this[position]
        position += 1
        when {
            char == '^' && position < length -> {
                if (!stopped) content.append(this[position])
                position += 1
            }
            char == ']' -> end = position
            char == ';' || char == ',' -> stopped = true
            !stopped -> content.append(char)
        }
    }
    return end?.let { content.toString() to it }
}
