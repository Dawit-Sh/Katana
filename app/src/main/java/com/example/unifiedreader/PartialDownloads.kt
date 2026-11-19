package com.example.unifiedreader

import com.example.unifiedreader.core.model.moveReplacing
import com.example.unifiedreader.core.network.PartialDownloadFile
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads in progress for one folder of books, kept in [directory], apart from the books themselves, so a
 * half file is never taken for a book. A download that breaks off (the Wi-Fi drops, the system stops the work)
 * goes on from its bytes the next time the same book is downloaded or opened.
 */
internal class PartialDownloads(
    private val directory: File,
) {
    /** Where the download of [bookFile] keeps its bytes until it is whole. */
    fun forBook(bookFile: File): PartialDownloadFile =
        PartialDownloadFile(File(directory, "${bookFile.name}$PARTIAL_DOWNLOAD_SUFFIX"))

    /**
     * A download that broke off and was never tried again (a book opened once, then not) would keep its bytes
     * for good: what nothing has touched for a week goes, apart from [keep]. A queued download's bytes go with
     * its entry when it's canceled or removed.
     */
    fun forgetAbandoned(keep: PartialDownloadFile) {
        val cutoff = System.currentTimeMillis() - ABANDONED_PARTIAL_DOWNLOAD_AGE_MILLIS
        directory.listFiles().orEmpty()
            .filter { file -> file != keep.dataFile && file != keep.infoFile && file.lastModified() < cutoff }
            .forEach(File::delete)
    }
}

/** Moves the whole download into [destination], replacing what is there, and forgets the download. */
internal fun PartialDownloadFile.moveInto(
    destination: File,
    targetDescription: String,
) {
    destination.parentFile?.mkdirs()
    try {
        dataFile.moveReplacing(destination)
    } catch (exception: IOException) {
        throw IllegalStateException("Unable to finalize $targetDescription.", exception)
    }
    delete()
}

private const val PARTIAL_DOWNLOAD_SUFFIX = ".part"
private val ABANDONED_PARTIAL_DOWNLOAD_AGE_MILLIS = TimeUnit.DAYS.toMillis(7)
