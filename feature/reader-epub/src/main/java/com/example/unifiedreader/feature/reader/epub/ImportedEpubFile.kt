package com.example.unifiedreader.feature.reader.epub

import android.content.Context
import android.net.Uri
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.isConvertedToEpub
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reader's own copy of [documentUri] as an EPUB: the file itself for an EPUB, or the EPUB made
 * from a MOBI, AZW, AZW3 or FB2 book (kept and reused while the book's file is the same).
 */
internal suspend fun prepareImportedEpubFile(
    context: Context,
    item: ReadableItem,
    documentUri: Uri,
): File = withContext(Dispatchers.IO) {
    val outputDirectory = File(context.cacheDir, "epub-imports").apply { mkdirs() }
    val outputFile = File(outputDirectory, "${item.id}.epub")
    if (item.format.isConvertedToEpub()) {
        val book = context.contentResolver.openInputStream(documentUri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("The book at $documentUri can't be read.")
        return@withContext convertedEpubFile(book = book, format = item.format, outputFile = outputFile)
    }
    context.contentResolver.openInputStream(documentUri)?.use { input ->
        FileOutputStream(outputFile).use { output ->
            input.copyTo(output)
        }
    } ?: throw FileNotFoundException("The EPUB file at $documentUri can't be read.")
    outputFile
}
