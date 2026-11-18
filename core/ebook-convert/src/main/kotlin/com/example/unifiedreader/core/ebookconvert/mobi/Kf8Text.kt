package com.example.unifiedreader.core.ebookconvert.mobi

import com.example.unifiedreader.core.ebookconvert.EbookConversionException

/** A KF8 skeleton: one XHTML file's frame, which its fragments fill in. */
internal class Kf8Skeleton(
    val fileNumber: Int,
    val fragmentCount: Int,
    val start: Int,
    val length: Int,
)

/** A KF8 fragment: a piece of text and where in its skeleton it goes. */
internal class Kf8Fragment(
    var insertPosition: Int,
    val selector: String,
    val start: Int,
    val length: Int,
)

/** One XHTML file of the KF8 book, put back together; [start] and [end] are its place in the text. */
internal class Kf8Part(
    val fileName: String,
    val start: Int,
    val end: Int,
    val bytes: ByteArray,
)

/** Where a `kindle:pos` position points: a file and, when one is found, an anchor in it. */
internal data class Kf8Anchor(
    val fileName: String,
    val id: String?,
)

/**
 * The text of a KF8 book: its flows (the XHTML text first, then stylesheets and SVG), the XHTML
 * files rebuilt from skeletons and fragments, and positions turned into file anchors.
 */
internal class Kf8Text(
    book: MobiBook,
    header: MobiHeader,
) {
    private val raw: ByteArray = book.text(header)
    val flows: List<ByteArray> = splitFlows(book.database, header.fdstRecord, raw)
    private val skeletons: List<Kf8Skeleton> = readSkeletons(MobiIndex.read(book.database, header.skeletonIndex))
    private val fragments: List<Kf8Fragment> = readFragments(MobiIndex.read(book.database, header.fragmentIndex))
    val parts: List<Kf8Part> = assembleParts(flows.firstOrNull() ?: ByteArray(0))

    /** The `aid` attributes some link points at, which become ids in the EPUB. */
    val linkedAids: MutableSet<String> = HashSet()

    init {
        if (parts.isEmpty()) {
            throw EbookConversionException(EbookConversionException.Reason.Damaged, "The KF8 book has no files")
        }
    }

    /** Where `kindle:pos:fid:[fid]:off:[offset]` points. */
    fun anchorForPosFid(
        fid: Int,
        offset: Int,
    ): Kf8Anchor? {
        val fragment = fragments.getOrNull(fid) ?: return null
        return anchorForPosition(fragment.insertPosition + offset)
    }

    /** Where a position in the text points: the file holding it, and the nearest anchor before it. */
    fun anchorForPosition(position: Int): Kf8Anchor? {
        val part = parts.firstOrNull { position >= it.start && position < it.end } ?: return null
        val id = anchorBefore(part.bytes, position - part.start)
        return Kf8Anchor(part.fileName, id)
    }

    // The id (or name, or aid) of the last tag that starts before [position], or null at the top of the body.
    private fun anchorBefore(
        bytes: ByteArray,
        position: Int,
    ): String? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val anchorTag = tagsBefore(text, endOfTagAt(text, position.coerceIn(0, text.length)))
            .takeWhile { tag -> !tag.startsWith("<body", ignoreCase = true) }
            .filterNot { tag -> tag.startsWith("<meta", ignoreCase = true) }
            .firstOrNull { tag -> AnchorAttributes.any { attribute -> attribute.containsMatchIn(tag) } }
        val named = anchorTag?.let { tag -> (IdAttribute.find(tag) ?: NameAttribute.find(tag))?.groupValues?.get(1) }
        val aid = anchorTag?.takeIf { named == null }?.let { tag -> AidAttribute.find(tag)?.groupValues?.get(1) }
        aid?.let(linkedAids::add)
        return named ?: aid?.let { "aid-$it" }
    }

    private fun assembleParts(text: ByteArray): List<Kf8Part> {
        var fragmentIndex = 0
        return skeletons.map { skeleton ->
            var base = skeleton.start + skeleton.length
            var assembled = text.slice(skeleton.start, base)
            repeat(skeleton.fragmentCount) {
                val fragment = fragments.getOrNull(fragmentIndex) ?: return@repeat
                fragmentIndex += 1
                val piece = text.slice(base, base + fragment.length)
                var insertAt = fragment.insertPosition - skeleton.start
                if (insertsInsideTag(assembled, insertAt)) {
                    insertAt = afterTagWithAid(assembled, fragment)?.also { fragment.insertPosition = it + skeleton.start }
                        ?: insertAt
                }
                insertAt = insertAt.coerceIn(0, assembled.size)
                assembled = assembled.copyOfRange(0, insertAt) + piece + assembled.copyOfRange(insertAt, assembled.size)
                base += fragment.length
            }
            Kf8Part("part%04d.xhtml".format(skeleton.fileNumber), skeleton.start, base, assembled)
        }
    }

    // Some books' fragment tables point into the middle of a tag; the selector's aid says where it belongs.
    private fun insertsInsideTag(
        assembled: ByteArray,
        insertAt: Int,
    ): Boolean {
        if (insertAt !in 0..assembled.size) return true
        val head = String(assembled, 0, insertAt, Charsets.ISO_8859_1)
        val tail = String(assembled, insertAt, assembled.size - insertAt, Charsets.ISO_8859_1)
        return tail.indexOf('>') < tail.indexOf('<') || head.lastIndexOf('>') < head.lastIndexOf('<')
    }

    private fun afterTagWithAid(
        assembled: ByteArray,
        fragment: Kf8Fragment,
    ): Int? {
        val aid = SelectorAid.find(fragment.selector)?.groupValues?.get(1)
        val text = String(assembled, Charsets.ISO_8859_1)
        val attribute = aid?.let { Regex("""\said\s*=\s*['"]${Regex.escape(it)}['"]""").find(text) }
        val tagEnd = attribute?.let { text.indexOf('>', it.range.last) } ?: -1
        return if (tagEnd < 0) null else tagEnd + 1 + fragment.start
    }

    private companion object {
        val IdAttribute = Regex("""^<[^>]*\sid\s*=\s*['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)
        val NameAttribute = Regex("""^<[^>]*\sname\s*=\s*['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)
        val AidAttribute = Regex("""^<[^>]+\said\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        val AnchorAttributes = listOf(IdAttribute, NameAttribute, AidAttribute)
        val SelectorAid = Regex("""aid\s*=\s*['"]([^'"]+)['"]""")
    }
}

// A position inside a tag counts from the tag's end.
private fun endOfTagAt(
    text: String,
    position: Int,
): Int {
    val nextOpen = text.indexOf('<', position)
    val nextClose = text.indexOf('>', position)
    val insideTag = nextOpen == position || nextClose != -1 && (nextOpen == -1 || nextClose < nextOpen)
    return if (insideTag) nextClose + 1 else position
}

// The whole tags that end before [end], last first.
private fun tagsBefore(
    text: String,
    end: Int,
): Sequence<String> = generateSequence(TagSpan.before(text, end - 1)) { span -> TagSpan.before(text, span.start - 1) }
    .map { span -> text.substring(span.start, span.end + 1) }

private class TagSpan(
    val start: Int,
    val end: Int,
) {
    companion object {
        fun before(
            text: String,
            cursor: Int,
        ): TagSpan? {
            val tagEnd = if (cursor < 0) -1 else text.lastIndexOf('>', cursor)
            val tagStart = if (tagEnd < 0) -1 else text.lastIndexOf('<', tagEnd)
            return if (tagStart < 0) null else TagSpan(tagStart, tagEnd)
        }
    }
}

private fun ByteArray.slice(
    from: Int,
    to: Int,
): ByteArray = copyOfRange(from.coerceIn(0, size), to.coerceIn(from.coerceIn(0, size), size))

// The FDST record splits the text into flows; without one the whole text is the one flow.
private fun splitFlows(
    database: PalmDatabase,
    fdstRecord: Int,
    raw: ByteArray,
): List<ByteArray> {
    val fdst = database.recordOrNull(fdstRecord)?.takeIf { it.ascii(0, FDST_MAGIC_LENGTH) == "FDST" }
        ?: return listOf(raw)
    val count = fdst.u32(FDST_COUNT_FIELD).toInt()
    val starts = (0 until count).map { index -> fdst.u32(FDST_TABLE_OFFSET + index * FDST_ENTRY_SIZE).toInt() }
    val bounds = starts + raw.size
    return (0 until count).map { index -> raw.slice(bounds[index], bounds[index + 1]) }
}

private fun readSkeletons(index: MobiIndex): List<Kf8Skeleton> = index.entries.mapIndexed { number, entry ->
    Kf8Skeleton(
        fileNumber = number,
        fragmentCount = entry.value(TAG_FRAGMENT_COUNT)?.toInt() ?: 0,
        start = entry.value(TAG_POSITION, 0)?.toInt() ?: 0,
        length = entry.value(TAG_POSITION, 1)?.toInt() ?: 0,
    )
}

private fun readFragments(index: MobiIndex): List<Kf8Fragment> = index.entries.map { entry ->
    Kf8Fragment(
        insertPosition = entry.textAscii().trim().toIntOrNull() ?: 0,
        selector = index.label(entry.value(TAG_SELECTOR), Charsets.UTF_8).orEmpty(),
        start = entry.value(TAG_POSITION, 0)?.toInt() ?: 0,
        length = entry.value(TAG_POSITION, 1)?.toInt() ?: 0,
    )
}

private const val TAG_FRAGMENT_COUNT = 1
private const val TAG_SELECTOR = 2
private const val TAG_POSITION = 6
private const val FDST_MAGIC_LENGTH = 4
private const val FDST_COUNT_FIELD = 8
private const val FDST_TABLE_OFFSET = 12
private const val FDST_ENTRY_SIZE = 8
