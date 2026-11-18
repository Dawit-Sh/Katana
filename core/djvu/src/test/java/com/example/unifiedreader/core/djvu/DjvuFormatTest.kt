package com.example.unifiedreader.core.djvu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DjvuFormatTest {
    private fun header(formType: String, magic: String = "AT&TFORM"): ByteArray =
        magic.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x9b.toByte(), 0x2a, 0x10) +
            formType.toByteArray(Charsets.US_ASCII)

    @Test
    fun `recognizes single page, bundled and include files`() {
        assertTrue(isDjvuSignature(header("DJVU")))
        assertTrue(isDjvuSignature(header("DJVM")))
        assertTrue(isDjvuSignature(header("DJVI")))
    }

    @Test
    fun `accepts a longer header`() {
        assertTrue(isDjvuSignature(header("DJVM") + "DIRM".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `rejects other forms and other files`() {
        assertFalse(isDjvuSignature(header("BM44")))
        assertFalse(isDjvuSignature(header("djvu")))
        assertFalse(isDjvuSignature(header("DJVU", magic = "AT&TFOR ")))
        assertFalse(isDjvuSignature("%PDF-1.7\n%âãÏÓ\n1 0".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(isDjvuSignature(ByteArray(DJVU_SIGNATURE_SIZE)))
    }

    @Test
    fun `rejects a header that is too short`() {
        assertFalse(isDjvuSignature(ByteArray(0)))
        assertFalse(isDjvuSignature(header("DJVU").copyOf(DJVU_SIGNATURE_SIZE - 1)))
    }

    @Test
    fun `render size puts the long edge on the target`() {
        assertEquals(1000 to 1500, djvuRenderSize(DjvuPageSize(width = 2000, height = 3000, dpi = 300), 1500))
        assertEquals(1500 to 1000, djvuRenderSize(DjvuPageSize(width = 3000, height = 2000, dpi = 300), 1500))
        assertEquals(800 to 800, djvuRenderSize(DjvuPageSize(width = 400, height = 400, dpi = 100), 800))
    }

    @Test
    fun `render size rounds to the nearest pixel`() {
        // 2550 x 3300 at 1000: 2550 * 1000 / 3300 = 772.7
        assertEquals(773 to 1000, djvuRenderSize(DjvuPageSize(width = 2550, height = 3300, dpi = 300), 1000))
    }

    @Test
    fun `render size keeps at least one pixel per side`() {
        assertEquals(1 to 1000, djvuRenderSize(DjvuPageSize(width = 1, height = 100_000, dpi = 300), 1000))
        assertEquals(1 to 1, djvuRenderSize(DjvuPageSize(width = 2000, height = 3000, dpi = 300), 0))
        assertEquals(64 to 64, djvuRenderSize(DjvuPageSize(width = 0, height = 0, dpi = 300), 64))
    }
}
