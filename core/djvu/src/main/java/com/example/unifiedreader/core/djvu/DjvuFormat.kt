package com.example.unifiedreader.core.djvu

import kotlin.math.max

/** How many bytes from the start of a file [isDjvuSignature] needs. */
const val DJVU_SIGNATURE_SIZE = 16

private val DJVU_MAGIC = "AT&TFORM".toByteArray(Charsets.US_ASCII)
private const val FORM_TYPE_OFFSET = 12
private const val FORM_TYPE_LENGTH = 4
private val DJVU_FORM_TYPES = setOf("DJVU", "DJVM", "DJVI")

/**
 * True when [header], the first bytes of a file, starts like a DjVu file: "AT&TFORM", a 4-byte length, then
 * the form type DJVU (one page), DJVM (several pages) or DJVI (shared data). Needs [DJVU_SIGNATURE_SIZE] bytes.
 */
fun isDjvuSignature(header: ByteArray): Boolean =
    header.size >= DJVU_SIGNATURE_SIZE &&
        DJVU_MAGIC.indices.all { header[it] == DJVU_MAGIC[it] } &&
        String(header, FORM_TYPE_OFFSET, FORM_TYPE_LENGTH, Charsets.US_ASCII) in DJVU_FORM_TYPES

/**
 * The bitmap size, as (width, height), that draws [page] with its longer edge [targetLongEdgePx] pixels long
 * and its proportions kept. Each side is at least 1 pixel. A page with no size gets a square.
 */
fun djvuRenderSize(page: DjvuPageSize, targetLongEdgePx: Int): Pair<Int, Int> {
    val target = max(1, targetLongEdgePx)
    val longEdge = max(page.width, page.height)
    if (longEdge <= 0) return target to target
    return scaledEdge(page.width, target, longEdge) to scaledEdge(page.height, target, longEdge)
}

private fun scaledEdge(edge: Int, target: Int, longEdge: Int): Int {
    val scaled = (max(0, edge).toLong() * target + longEdge / 2) / longEdge
    return max(1L, scaled).toInt()
}
