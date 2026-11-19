package com.example.unifiedreader.feature.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageImageCacheTest {
    private class FakeImage(val bytes: Long) {
        var recycled = false
    }

    private fun cache(budget: Long) = PdfPageImageCache<FakeImage>(
        budgetBytes = budget,
        sizeOf = FakeImage::bytes,
        recycle = { image -> image.recycled = true },
    )

    private fun key(page: Int, width: Int = 1000) = PdfPageImageKey(pageIndex = page, widthPx = width)

    @Test
    fun `pages off screen go least recently used first once the budget is passed`() {
        val cache = cache(budget = 300)
        val pages = (0..3).map { FakeImage(100) }
        pages.forEachIndexed { index, image ->
            cache.put(key(index), image)
            cache.release(key(index))
        }

        assertTrue(pages[0].recycled)
        assertFalse(pages[1].recycled)
        assertEquals(300L, cache.totalBytes)
        assertEquals(3, cache.size)
    }

    @Test
    fun `a page on screen is never recycled, even over the budget`() {
        val cache = cache(budget = 150)
        val held = FakeImage(100)
        val other = FakeImage(100)
        cache.put(key(0), held)
        cache.put(key(1), other)

        assertFalse(held.recycled)
        assertFalse(other.recycled)
        assertEquals(200L, cache.totalBytes)

        cache.release(key(0))
        assertTrue(held.recycled)
        assertFalse(other.recycled)
    }

    @Test
    fun `using a page again keeps it longer than an older one`() {
        val cache = cache(budget = 200)
        val first = FakeImage(100)
        val second = FakeImage(100)
        cache.put(key(0), first)
        cache.release(key(0))
        cache.put(key(1), second)
        cache.release(key(1))
        cache.acquire(key(0))
        cache.release(key(0))

        cache.put(key(2), FakeImage(100))

        assertFalse(first.recycled)
        assertTrue(second.recycled)
    }

    @Test
    fun `a page drawn twice keeps the first drawing and recycles the second`() {
        val cache = cache(budget = 1000)
        val first = FakeImage(100)
        val second = FakeImage(100)
        cache.put(key(0), first)

        val shown = cache.put(key(0), second)

        assertSame(first, shown)
        assertTrue(second.recycled)
        assertEquals(100L, cache.totalBytes)
    }

    @Test
    fun `the widest drawing of a page is what shows while a sharper one is drawn`() {
        val cache = cache(budget = 1000)
        val plain = FakeImage(100)
        val zoomed = FakeImage(400)
        cache.put(key(4, width = 1000), plain, hold = false)
        cache.put(key(4, width = 2000), zoomed, hold = false)

        val (widestKey, widest) = requireNotNull(cache.acquireWidest(4))

        assertEquals(2000, widestKey.widthPx)
        assertSame(zoomed, widest)
        assertNull(cache.acquireWidest(5))
    }

    @Test
    fun `closing recycles what is not held, then each held page as it is let go`() {
        val cache = cache(budget = 1000)
        val held = FakeImage(100)
        val loose = FakeImage(100)
        cache.put(key(0), held)
        cache.put(key(1), loose, hold = false)

        cache.close()
        assertTrue(loose.recycled)
        assertFalse(held.recycled)

        cache.release(key(0))
        assertTrue(held.recycled)
        assertEquals(0L, cache.totalBytes)

        val late = FakeImage(100)
        assertNull(cache.put(key(2), late))
        assertTrue(late.recycled)
    }
}
