package com.example.unifiedreader.core.ebookconvert

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Converts real books from the folder `-PebookSamples` names and checks each EPUB opens as a ZIP
 * with chapters. Skipped without the property: the books aren't in the repository.
 */
class RealBooksConversionTest {
    @Test
    fun convertsEverySampleBook() {
        val folder = System.getProperty("ebookSamples").orEmpty().takeIf(String::isNotBlank)?.let(::File)
        assumeTrue(folder != null && folder.isDirectory)
        val books = folder!!.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in SampleExtensions }
        assumeTrue(books.isNotEmpty())
        val output = File(folder, "converted").apply { mkdirs() }
        books.sortedBy { it.name }.forEach { file ->
            val bytes = file.readBytes()
            val kind = EbookConverter.detectKind(bytes) ?: error("Not recognised: ${file.name}")
            val started = System.nanoTime()
            val epub = File(output, file.nameWithoutExtension + ".epub")
            epub.outputStream().use { EbookConverter.convertToEpub(kind, bytes, it) }
            val millis = (System.nanoTime() - started) / 1_000_000
            val metadata = EbookConverter.readMetadata(kind, bytes)
            ZipFile(epub).use { zip ->
                val chapters = zip.entries().toList().count { it.name.endsWith(".xhtml") }
                println("${file.name}: $kind ${millis}ms ${epub.length()} bytes, $chapters xhtml, " +
                    "title=${metadata.title} authors=${metadata.authors} cover=${metadata.cover?.mediaType}")
                assertTrue(chapters > 1)
            }
        }
    }

    private companion object {
        val SampleExtensions = setOf("mobi", "azw", "azw3", "prc", "fb2", "zip")
    }
}
