package com.example.unifiedreader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportedBookBytesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `counts imported covers, converted copies and comic copies, not the servers' covers`() {
        val files = folder.newFolder("files")
        val cache = folder.newFolder("cache")
        write(File(files, "imported-cover-cache/a.jpg"), 1_000)
        write(File(cache, "epub-imports/book.epub"), 20_000)
        write(File(cache, "djvu-imports/scan.djvu"), 300_000)
        write(File(cache, "comic-zip-archives/c.cbz"), 4_000)
        write(File(cache, "comic-archives/r.cbr"), 5_000)
        write(File(cache, "comic-archives-solid/x/page"), 600)
        write(File(files, "kavita-covers/cover.jpg"), 70_000)

        assertEquals(330_600L, importedBookBytes(filesDir = files, cacheDir = cache))
    }

    @Test
    fun `nothing imported is zero`() {
        assertEquals(0L, importedBookBytes(filesDir = folder.newFolder("files"), cacheDir = folder.newFolder("cache")))
    }

    private fun write(file: File, size: Int) {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size))
    }
}
