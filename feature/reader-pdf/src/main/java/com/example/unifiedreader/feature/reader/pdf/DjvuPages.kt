package com.example.unifiedreader.feature.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.unifiedreader.core.djvu.DjvuDocument
import com.example.unifiedreader.core.djvu.djvuRenderSize
import com.example.unifiedreader.core.model.ReadableItem
import com.example.unifiedreader.core.model.runSuspendCatching
import com.example.unifiedreader.feature.reader.core.ReaderOpenFailure
import com.example.unifiedreader.feature.reader.core.readerFileMissingFailure
import com.example.unifiedreader.feature.reader.core.readerOpenFailureOf
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * DjVu books in the page reader: pages drawn by DjVuLibre instead of PdfRenderer. DjVuLibre reads a
 * file path, so a book behind a content URI is copied to the cache first. The open document stays
 * open while its book is read ([OpenDjvuDocument]), since opening one reads its whole page directory.
 */
internal suspend fun inspectDjvuDocument(
    context: Context,
    item: ReadableItem,
    documentUri: Uri,
): PdfDocumentInfoState = runSuspendCatching {
    val pageCount = OpenDjvuDocument.use(djvuFile(context, item, documentUri)) { document -> document.pageCount }
    if (pageCount <= 0) PdfDocumentInfoState.Error(ReaderOpenFailure.NoPdfPages) else PdfDocumentInfoState.Ready(pageCount)
}.getOrElse { error ->
    val failure = if (error is FileNotFoundException) readerFileMissingFailure(item) else readerOpenFailureOf(error, item)
    PdfDocumentInfoState.Error(failure.takeUnless { it == ReaderOpenFailure.Unknown } ?: ReaderOpenFailure.DjvuUnreadable)
}

internal suspend fun renderDjvuPage(
    context: Context,
    item: ReadableItem,
    documentUri: Uri,
    pageIndex: Int,
    targetLongEdgePx: Int,
): PdfPageRenderState {
    var bitmap: Bitmap? = null
    return runSuspendCatching {
        val file = djvuFile(context, item, documentUri)
        OpenDjvuDocument.use(file) { document ->
            val safePageIndex = pageIndex.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0))
            val (width, height) = djvuRenderSize(document.pageSize(safePageIndex), targetLongEdgePx)
            val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
            document.renderPage(safePageIndex, page)
            safePageIndex
        }
    }.fold(
        onSuccess = { safePageIndex ->
            currentCoroutineContext().ensureActive()
            PdfPageRenderState.Ready(bitmap = requireNotNull(bitmap), pageIndex = safePageIndex)
        },
        onFailure = {
            bitmap?.recycle()
            PdfPageRenderState.Error("Unable to show this DjVu page.")
        },
    )
}

/** Closes the DjVu document a closed reader leaves open. */
internal fun releaseDjvuDocument() {
    OpenDjvuDocument.close()
}

// The book as a file DjVuLibre can open: the file itself, or a cached copy of what a content URI holds.
private fun djvuFile(
    context: Context,
    item: ReadableItem,
    documentUri: Uri,
): File = if (documentUri.scheme == "file") {
    File(requireNotNull(documentUri.path)).takeIf(File::isFile)
        ?: throw FileNotFoundException("The DjVu file at $documentUri is gone.")
} else {
    cachedDjvuCopy(context, item, documentUri)
}

// A copy kept while it has the size of the file behind the URI.
private fun cachedDjvuCopy(
    context: Context,
    item: ReadableItem,
    documentUri: Uri,
): File {
    val directory = File(context.cacheDir, "djvu-imports").apply { mkdirs() }
    val copy = File(directory, "${item.id}.djvu")
    val size = context.contentResolver.openFileDescriptor(documentUri, "r")?.use { it.statSize }
    if (size != null && copy.isFile && copy.length() == size) return copy
    val partial = File(directory, "${item.id}.djvu.part")
    context.contentResolver.openInputStream(documentUri)?.use { input ->
        partial.outputStream().use { output -> input.copyTo(output) }
    } ?: throw FileNotFoundException("The DjVu file at $documentUri can't be read.")
    OpenDjvuDocument.close()
    copy.delete()
    if (!partial.renameTo(copy)) partial.copyTo(copy, overwrite = true).also { partial.delete() }
    return copy
}

/** The one DjVu document open at a time, for the book being read. */
private object OpenDjvuDocument {
    private var path: String? = null
    private var document: DjvuDocument? = null

    @Synchronized
    fun <T> use(
        file: File,
        block: (DjvuDocument) -> T,
    ): T {
        val open = document?.takeIf { path == file.path } ?: run {
            close()
            DjvuDocument.open(file).also {
                document = it
                path = file.path
            }
        }
        return block(open)
    }

    @Synchronized
    fun isOpen(file: File): Boolean = document != null && path == file.path

    @Synchronized
    fun close() {
        document?.close()
        document = null
        path = null
    }
}
